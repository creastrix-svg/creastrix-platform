package com.creastrix.platform.authentication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import static com.creastrix.platform.authentication.CognitoEmailConfirmationService.Operation.CONFIRM;
import static com.creastrix.platform.authentication.CognitoEmailConfirmationService.Operation.RESEND;
import static com.creastrix.platform.authentication.CognitoEmailConfirmationService.Result.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Synthetic and loopback-only proofs; no AWS credentials, SDK, provider account, or live runtime. */
@Timeout(20)
class CognitoEmailConfirmationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AuthenticationProperties COGNITO = properties(AuthenticationProperties.Provider.COGNITO);
    private final List<CognitoEmailConfirmationService> services = new ArrayList<>();

    @AfterEach void closeServices() {
        SecurityContextHolder.clearContext();
        services.forEach(CognitoEmailConfirmationService::close);
        services.forEach(service -> await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(service.counts().workers()).isZero()));
    }

    private static AuthenticationProperties properties(AuthenticationProperties.Provider provider) {
        return new AuthenticationProperties("https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_Test",
                "test-client", "test-secret", "http://localhost:3000", Set.of(), false, provider,
                provider == AuthenticationProperties.Provider.COGNITO);
    }
    private CognitoEmailConfirmationService service(CognitoEmailConfirmationService.Transport transport,
            AtomicLong ticker, CognitoEmailConfirmationService.Limits limits) {
        var service = new CognitoEmailConfirmationService(COGNITO, transport, ticker::get, limits);
        services.add(service);
        return service;
    }
    private CognitoEmailConfirmationService service(CognitoEmailConfirmationService.Transport transport) {
        return service(transport, new AtomicLong(), CognitoEmailConfirmationService.Limits.standard());
    }
    private static CognitoEmailConfirmationService.ProviderResponse response(int status, String body) {
        return new CognitoEmailConfirmationService.ProviderResponse(status, body.getBytes(StandardCharsets.UTF_8));
    }
    private static CognitoEmailConfirmationService.Input input(String email) {
        return new CognitoEmailConfirmationService.Input(email, "123456");
    }

    @Test void fixedEndpointAndCapsAreFiniteAndNotCallerConfiguration() {
        assertThat(CognitoEmailConfirmationService.ENDPOINT.toString()).isEqualTo("https://cognito-idp.eu-central-1.amazonaws.com/");
        assertThat(CognitoEmailConfirmationService.MAX_BYTES).isEqualTo(16_384);
        assertThat(CognitoEmailConfirmationService.MAX_WORKERS).isEqualTo(4);
        assertThat(CognitoEmailConfirmationService.MAX_TARGETS).isEqualTo(256);
        assertThat(CognitoEmailConfirmationService.GLOBAL_LIMIT).isEqualTo(20);
        assertThat(CognitoEmailConfirmationService.TARGET_LIMIT).isEqualTo(3);
        assertThat(CognitoEmailConfirmationService.DEADLINE).isEqualTo(Duration.ofSeconds(5));
    }

    @Test void payloadPreservesCaseComputesExactSecretHashAndCannotTransferAliasOrContinueSession() throws Exception {
        var service = service((op, body, deadline) -> response(200, "{}"));
        String email = "Mixed.Case@example.test";
        var payload = JSON.readTree(service.payload(CONFIRM, input(email)));
        assertThat(payload.size()).isEqualTo(5);
        assertThat(payload.get("Username").asString().equals(email)).isTrue();
        assertThat(payload.get("ClientId").asString().equals(COGNITO.clientId())).isTrue();
        assertThat(payload.get("ForceAliasCreation").asBoolean()).isFalse();
        assertThat(payload.get("ConfirmationCode").asString().equals("123456")).isTrue();
        Mac independent = Mac.getInstance("HmacSHA256");
        independent.init(new SecretKeySpec(COGNITO.clientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(independent.doFinal((email + COGNITO.clientId()).getBytes(StandardCharsets.UTF_8)));
        assertThat(payload.get("SecretHash").asString().equals(expected)).isTrue();
        assertThat(JSON.readTree(service.payload(CONFIRM, input(email.toLowerCase(java.util.Locale.ROOT))))
                .get("SecretHash").asString().equals(expected)).isFalse();
        for (String forbidden : List.of("Session", "ClientMetadata", "UserContextData", "AnalyticsMetadata", "UserPoolId")) {
            assertThat(payload.has(forbidden)).isFalse();
        }
        var resend = JSON.readTree(service.payload(RESEND, input(email)));
        assertThat(resend.size()).isEqualTo(3);
        assertThat(resend.has("ConfirmationCode") || resend.has("ForceAliasCreation") || resend.has("Session")).isFalse();
        assertThat(input(email).toString()).isEqualTo("Input[redacted]");
        assertThat(response(200, "private-session").toString()).isEqualTo("ProviderResponse[redacted]");
    }

    static Stream<String> malformedInputs() {
        return Stream.of("", "null", "[]", "{}", "{\"email\":1,\"code\":\"1\"}",
                "{\"email\":\"a@b\",\"code\":1}", "{\"email\":\"a@b\",\"code\":null}",
                "{\"email\":\"a@b\"}", "{\"email\":\"a@b\",\"code\":\"1\",\"endpoint\":\"https://attacker.test\"}",
                "{\"email\":\"a@b\",\"email\":\"other@b\",\"code\":\"1\"}",
                "{\"email\":\"a@b\",\"code\":\"1\"} {}", "{\"email\":\"a@b\",\"code\":\"\"}",
                "{\"email\":\"a@b\",\"code\":\"1 2\"}", "{\"email\":\" a@b\",\"code\":\"1\"}",
                "{\"email\":\"a@b\",\"code\":\"1\\u00a02\"}",
                "{\"email\":\"a@@b\",\"code\":\"1\"}", "{\"email\":\"alias\",\"code\":\"1\"}",
                "{\"email\":\"a@b\",\"code\":\"1\",}",
                "{\"email\":\"" + "x".repeat(127) + "@b\",\"code\":\"1\"}",
                "{\"email\":\"a@b\",\"code\":\"" + "1".repeat(2049) + "\"}",
                "{\"email\":\"a@b\",\"code\":\"\\ud800\"}",
                "{\"email\":\"a\\u00a0@b\",\"code\":\"1\"}", "{\"email\":\"a@b\",\"code\":\"1\\u00a0\"}",
                "{\"email\":\"a\\u2007@b\",\"code\":\"1\"}", "{\"email\":\"a@b\",\"code\":\"1\\u2007\"}",
                "{\"email\":\"a\\u202f@b\",\"code\":\"1\"}", "{\"email\":\"a@b\",\"code\":\"1\\u202f\"}",
                "{\"email\":\"a\\ufeff@b\",\"code\":\"1\"}", "{\"email\":\"a@b\",\"code\":\"1\\ufeff\"}");
    }
    @ParameterizedTest @MethodSource("malformedInputs")
    void strictBoundedRawInputRejectsMalformedDuplicateUnknownAndCoercedFields(String body) {
        assertThatThrownBy(() -> CognitoEmailConfirmationService.parse(body.getBytes(StandardCharsets.UTF_8), CONFIRM))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid confirmation input").hasNoCause();
    }

    @Test void exactInputLimitsAndResendFieldsAreValidatedWithoutNormalization() {
        String email = "A".repeat(126) + "@b";
        String code = "1".repeat(2048);
        var parsed = CognitoEmailConfirmationService.parse(JSON.writeValueAsBytes(Map.of("email", email, "code", code)), CONFIRM);
        assertThat(parsed.email().equals(email) && parsed.code().equals(code)).isTrue();
        assertThat(CognitoEmailConfirmationService.parse(JSON.writeValueAsBytes(Map.of("email", "Mixed@b")), RESEND).email())
                .isEqualTo("Mixed@b");
        assertThatThrownBy(() -> CognitoEmailConfirmationService.parse(JSON.writeValueAsBytes(Map.of("email", "a@b", "code", "1")), RESEND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CognitoEmailConfirmationService.parse(new byte[CognitoEmailConfirmationService.MAX_BYTES + 1], RESEND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> outcomes() {
        var cases = new ArrayList<Arguments>();
        cases.add(Arguments.of(200, "{}", CONFIRMED, CODE_REQUEST_ACCEPTED));
        cases.add(Arguments.of(200, "{\"Session\":\"private-session\",\"CodeDeliveryDetails\":{\"Destination\":\"private-recipient\"}}",
                CONFIRMED, CODE_REQUEST_ACCEPTED));
        for (String error : List.of("UserNotFoundException", "NotAuthorizedException", "CodeMismatchException",
                "ExpiredCodeException", "AliasExistsException", "InvalidParameterException", "UserLambdaValidationException",
                "TooManyFailedAttemptsException", "TooManyRequestsException", "LimitExceededException")) {
            cases.add(Arguments.of(400, "{\"__type\":\"com.amazonaws.cognito#" + error + "\",\"message\":\"private-provider-detail\"}",
                    NOT_CONFIRMED, CODE_REQUEST_ACCEPTED));
        }
        for (String error : List.of("InternalErrorException", "CodeDeliveryFailureException", "InvalidEmailRoleAccessPolicyException",
                "UnexpectedLambdaException", "ForbiddenException", "UnknownProviderError")) {
            cases.add(Arguments.of(400, "{\"__type\":\"" + error + "\",\"message\":\"private-provider-detail\"}", UNAVAILABLE, UNAVAILABLE));
        }
        for (String body : List.of("", "<html>private</html>", "null", "[]", "{} {}", "{", "{\"Session\":1,\"Session\":2}")) {
            cases.add(Arguments.of(200, body, UNAVAILABLE, UNAVAILABLE));
        }
        cases.add(Arguments.of(200, "{\"__type\":\"UserNotFoundException\"}", UNAVAILABLE, UNAVAILABLE));
        cases.add(Arguments.of(400, "{}", UNAVAILABLE, UNAVAILABLE));
        cases.add(Arguments.of(400, "{\"__type\":123}", UNAVAILABLE, UNAVAILABLE));
        cases.add(Arguments.of(400, "{\"__type\":{\"value\":\"UserNotFoundException\"}}", UNAVAILABLE, UNAVAILABLE));
        cases.add(Arguments.of(400, "{\"__type\":\"NotAuthorizedException\",\"message\":\"Already confirmed\"}", NOT_CONFIRMED, CODE_REQUEST_ACCEPTED));
        cases.add(Arguments.of(400, "{\"__type\":\"NotAuthorizedException\",\"message\":\"User disabled\"}", NOT_CONFIRMED, CODE_REQUEST_ACCEPTED));
        cases.add(Arguments.of(500, "{}", UNAVAILABLE, UNAVAILABLE));
        cases.add(Arguments.of(302, "{}", UNAVAILABLE, UNAVAILABLE));
        return cases.stream();
    }
    @ParameterizedTest @MethodSource("outcomes")
    void closedProviderOutcomesNeverExposeSessionDeliveryRecipientOrMessages(int status, String body,
            CognitoEmailConfirmationService.Result confirm, CognitoEmailConfirmationService.Result resend) {
        var service = service((op, payload, deadline) -> response(status, body));
        assertThat(service.execute(CONFIRM, input("first@b"))).isEqualTo(confirm);
        assertThat(service.execute(RESEND, input("second@b"))).isEqualTo(resend);
    }

    @Test void legacyProviderIsRejectedBeforeAnyRecoveryServiceOrOperation() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> {
            var rejected = properties(AuthenticationProperties.Provider.configured("AUTH0"));
            try (var service = new CognitoEmailConfirmationService(rejected,
                    (op, body, deadline) -> { calls.incrementAndGet(); return response(200, "{}"); },
                    System::nanoTime, CognitoEmailConfirmationService.Limits.standard())) {
                service.execute(CONFIRM, input("a@b"));
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("creastrix.auth.provider must be COGNITO");
        assertThat(calls).hasValue(0);
    }

    @Test void transportFailureIsUnknownAndHasNoApplicationRetry() {
        var calls = new AtomicInteger();
        var service = service((op, body, deadline) -> {
            calls.incrementAndGet();
            throw new IOException("private-provider-transport-detail");
        });
        assertThat(service.execute(CONFIRM, input("a@b"))).isEqualTo(UNAVAILABLE);
        assertThat(calls).hasValue(1);
        assertThat(service.counts().workers()).isZero();
    }

    @Test void actualAdmissionLockContentionIsBoundedAndNeverStartsNetwork() throws Exception {
        var calls = new AtomicInteger();
        var service = service((op, body, deadline) -> { calls.incrementAndGet(); return response(200, "{}"); });
        var field = CognitoEmailConfirmationService.class.getDeclaredField("admission");
        field.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) field.get(service);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofVirtual().start(() -> {
            lock.lock();
            try {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { lock.unlock(); }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            assertThat(service.execute(CONFIRM, input("a@b"))).isEqualTo(UNAVAILABLE);
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(1).toNanos());
            assertThat(calls).hasValue(0);
        }
        finally { release.countDown(); holder.join(2000); }
        assertThat(holder.isAlive()).isFalse();
        assertThat(service.counts().targets()).isZero();
    }

    @Test void targetWindowIsExactSharedAcrossOperationsAndCannotBeResetByCaseVariants() {
        var time = new AtomicLong();
        var calls = new AtomicInteger();
        var service = service((op, body, deadline) -> { calls.incrementAndGet(); return response(200, "{}"); }, time,
                CognitoEmailConfirmationService.Limits.standard());
        assertThat(service.execute(CONFIRM, input("Mixed@b"))).isEqualTo(CONFIRMED);
        assertThat(service.execute(RESEND, input("Mixed@b"))).isEqualTo(CODE_REQUEST_ACCEPTED);
        assertThat(service.execute(CONFIRM, input("Mixed@b"))).isEqualTo(CONFIRMED);
        assertThat(service.execute(CONFIRM, input("Mixed@b"))).isEqualTo(RATE_LIMITED);
        time.set(CognitoEmailConfirmationService.WINDOW.toNanos() - 1);
        assertThat(service.execute(RESEND, input("Mixed@b"))).isEqualTo(RATE_LIMITED);
        assertThat(service.execute(CONFIRM, input("mixed@b"))).isEqualTo(RATE_LIMITED);
        time.incrementAndGet();
        assertThat(service.execute(CONFIRM, input("Mixed@b"))).isEqualTo(CONFIRMED);
        assertThat(calls).hasValue(4);
        assertThat(service.retainedTargetKeys()).hasSize(1).allMatch(key -> key.matches("[A-Za-z0-9+/]{43}="));
        assertThat(service.retainedTargetKeys().stream().noneMatch(key -> key.contains("Mixed") || key.contains("@"))).isTrue();
    }

    @Test void globalWindowIsIndependentOfTargetAndExactAtBoundary() {
        var time = new AtomicLong();
        var service = service((op, body, deadline) -> response(200, "{}"), time, CognitoEmailConfirmationService.Limits.standard());
        for (int index = 0; index < CognitoEmailConfirmationService.GLOBAL_LIMIT; index++) {
            assertThat(service.execute(CONFIRM, input("target" + index + "@b"))).isEqualTo(CONFIRMED);
        }
        assertThat(service.execute(CONFIRM, input("different@b"))).isEqualTo(RATE_LIMITED);
        time.set(CognitoEmailConfirmationService.WINDOW.toNanos() - 1);
        assertThat(service.execute(CONFIRM, input("different@b"))).isEqualTo(RATE_LIMITED);
        time.incrementAndGet();
        assertThat(service.execute(CONFIRM, input("different@b"))).isEqualTo(CONFIRMED);
    }

    @Test void finiteTargetCapacityNeverEvictsLiveRecordsAndExpiresAtExactTtl() {
        var time = new AtomicLong();
        var limits = new CognitoEmailConfirmationService.Limits(4, 2, 20, 3, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1));
        var service = service((op, body, deadline) -> response(200, "{}"), time, limits);
        assertThat(service.execute(CONFIRM, input("first@b"))).isEqualTo(CONFIRMED);
        assertThat(service.execute(CONFIRM, input("second@b"))).isEqualTo(CONFIRMED);
        Set<String> original = service.retainedTargetKeys();
        assertThat(service.execute(CONFIRM, input("third@b"))).isEqualTo(RATE_LIMITED);
        assertThat(service.retainedTargetKeys()).isEqualTo(original);
        time.set(limits.ttl().toNanos() - 1);
        assertThat(service.execute(CONFIRM, input("third@b"))).isEqualTo(RATE_LIMITED);
        time.incrementAndGet();
        assertThat(service.execute(CONFIRM, input("third@b"))).isEqualTo(CONFIRMED);
        assertThat(service.retainedTargetKeys()).hasSize(1).doesNotContainAnyElementsOf(original);
    }

    @Test void all256RetainedTargetSlotsAreBoundedWithoutEviction() {
        var time = new AtomicLong();
        var limits = new CognitoEmailConfirmationService.Limits(4, CognitoEmailConfirmationService.MAX_TARGETS, 20, 3,
                Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofSeconds(1));
        var service = service((op, body, deadline) -> response(200, "{}"), time, limits);
        for (int index = 0; index < CognitoEmailConfirmationService.MAX_TARGETS; index++) {
            if (index % CognitoEmailConfirmationService.GLOBAL_LIMIT == 0) time.addAndGet(CognitoEmailConfirmationService.WINDOW.toNanos());
            // A separate large TTL keeps all actual standard-capacity target records live in this test.
            // Standard TTL expiry is covered separately; capacity behavior uses the same admission algorithm.
            assertThat(service.counts().targets()).isLessThanOrEqualTo(CognitoEmailConfirmationService.MAX_TARGETS);
            assertThat(service.execute(CONFIRM, input("target" + index + "@b"))).isEqualTo(CONFIRMED);
        }
        assertThat(service.counts().targets()).isEqualTo(CognitoEmailConfirmationService.MAX_TARGETS);
        Set<String> original = service.retainedTargetKeys();
        assertThat(service.execute(CONFIRM, input("capacity-refused@b"))).isEqualTo(RATE_LIMITED);
        assertThat(service.retainedTargetKeys()).isEqualTo(original);
    }

    @Test void timedOutUnfinishedWorkersRemainChargedAcrossWindowTtlAndCloseWithoutQueue() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var time = new AtomicLong();
        var limits = new CognitoEmailConfirmationService.Limits(4, 4, 20, 3, Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(120));
        var service = service((op, body, deadline) -> {
            calls.incrementAndGet();
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(10, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* Deliberately uncooperative test transport. */ }
            }
            return response(200, "{}");
        }, time, limits);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> callers.submit(() -> service.execute(CONFIRM, input("held" + index + "@b")))).toList();
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (var future : futures) assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(UNAVAILABLE);
                assertThat(service.counts().workers()).isEqualTo(4);
                assertThat(service.counts().targets()).isEqualTo(4);
                time.set(limits.ttl().toNanos() * 10);
                assertThat(service.execute(CONFIRM, input("new@b"))).isEqualTo(RATE_LIMITED);
                assertThat(service.counts().workers()).isEqualTo(4);
                assertThat(service.counts().targets()).isEqualTo(4);
                assertThat(calls).hasValue(4);
                service.close();
                assertThat(service.counts().workers()).isEqualTo(4);
                assertThat(service.execute(CONFIRM, input("new@b"))).isEqualTo(UNAVAILABLE);
            }
            finally { release.countDown(); }
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(service.counts().workers()).isZero());
    }

    @Test void callerInterruptionIsUnknownAndDoesNotReleaseAnUnfinishedWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service((op, body, deadline) -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(10, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { }
            }
            return response(200, "{}");
        });
        var result = new AtomicReference<CognitoEmailConfirmationService.Result>();
        var caller = Thread.ofVirtual().start(() -> result.set(service.execute(CONFIRM, input("held@b"))));
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isEqualTo(UNAVAILABLE);
            assertThat(service.counts().workers()).isEqualTo(1);
        }
        finally { release.countDown(); }
    }

    @Test void concurrentExactTargetAdmissionCannotExceedThree() throws Exception {
        var calls = new AtomicInteger();
        var service = service((op, body, deadline) -> { calls.incrementAndGet(); return response(200, "{}"); });
        var start = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 30).mapToObj(index -> callers.submit(() -> {
                start.await();
                return service.execute(CONFIRM, input("same@b"));
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(3, TimeUnit.SECONDS) == CONFIRMED) successes++;
            assertThat(successes).isEqualTo(3);
            assertThat(calls).hasValue(3);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"drop", "redirect", "auth-challenge", "declared-oversize", "chunked-oversize", "encoded", "malformed", "multiple", "slow-headers", "slow-body", "truncated"})
    void actualLoopbackTransportHasOneSubmissionNoRedirectBoundedBytesAndWholeDeadline(String behavior) throws Exception {
        try (var server = new RawProvider(behavior)) {
            var limits = new CognitoEmailConfirmationService.Limits(4, 256, 20, 3, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMillis(180));
            var service = service(new CognitoEmailConfirmationService.HttpTransport(server.endpoint()), new AtomicLong(), limits);
            service.payload(CONFIRM, input("warmup@b")); // Warm serializer/cryptography, not provider work or throttle state.
            long started = System.nanoTime();
            assertThat(service.execute(CONFIRM, input("loopback@b"))).isEqualTo(UNAVAILABLE);
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(2).toNanos());
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(server.requests).hasValue(1));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(service.counts().workers()).isZero());
            assertThat(server.requests).hasValue(1);
            assertThat(server.target.get()).isEqualTo(CONFIRM.target);
            assertThat(server.contentType.get()).isEqualTo("application/x-amz-json-1.0");
        }
    }

    @Test void actualLoopbackSuccessIsKnownAndErrorIsCollapsed() throws Exception {
        try (var success = new RawProvider("success"); var error = new RawProvider("recipient-error"); var cap = new RawProvider("exact-cap")) {
            var first = service(new CognitoEmailConfirmationService.HttpTransport(success.endpoint()));
            var second = service(new CognitoEmailConfirmationService.HttpTransport(error.endpoint()));
            assertThat(first.execute(CONFIRM, input("a@b"))).isEqualTo(CONFIRMED);
            assertThat(second.execute(CONFIRM, input("a@b"))).isEqualTo(NOT_CONFIRMED);
            assertThat(second.execute(RESEND, input("other@b"))).isEqualTo(CODE_REQUEST_ACCEPTED);
            assertThat(success.requests).hasValue(1);
            assertThat(error.requests).hasValue(2);
            assertThat(service(new CognitoEmailConfirmationService.HttpTransport(cap.endpoint()))
                    .execute(CONFIRM, input("cap@b"))).isEqualTo(CONFIRMED);
            assertThat(cap.requests).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/auth/confirm-email", "/auth/resend-confirmation-code", "/%61uth/%63onfirm-email"})
    void disabledModeDeniesRecoveryBeforeCsrfSessionMaterialization(String path) {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
                .withUserConfiguration(AuthenticationConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    var request = new MockHttpServletRequest("POST", path);
                    request.setServletPath(URI.create(path).getPath());
                    var response = new MockHttpServletResponse();
                    context.getBean("springSecurityFilterChain", Filter.class).doFilter(request, response,
                            (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Disabled recovery reached application"); });
                    assertThat(response.getStatus()).isEqualTo(403);
                    assertThat(request.getSession(false)).isNull();
                });
    }

    @Test void authenticatedEarlyBoundaryDoesNotMaterializeCsrfOrChangeAnySessionState() throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/confirm-email");
        request.setServletPath("/auth/confirm-email");
        request.addHeader("Host", "localhost:3000");
        request.addHeader("Origin", "http://localhost:3000");
        var session = new MockHttpSession();
        Object sentinel = new Object();
        session.setAttribute("sentinel", sentinel);
        request.setSession(session);
        var authentication = UsernamePasswordAuthenticationToken.authenticated("test-principal", "", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try (var contexts = new BrowserSessionContextRegistry(Clock.systemUTC())) {
            var boundary = new AuthenticationConfiguration.RequestBoundary(COGNITO, Clock.systemUTC(),
                    new AuthenticationConfiguration.TimedAuthorizationRequests(Clock.systemUTC()), contexts);
            var response = new MockHttpServletResponse();
            boundary.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Authenticated recovery reached CSRF"); });
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(java.util.Collections.list(session.getAttributeNames())).containsExactly("sentinel");
            assertThat(session.getAttribute("sentinel") == sentinel).isTrue();
            assertThat(SecurityContextHolder.getContext().getAuthentication() == authentication).isTrue();
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        }
    }

    private static final class RawProvider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> target = new AtomicReference<>();
        final AtomicReference<String> contentType = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ServerSocket listener;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final String behavior;
        RawProvider(String behavior) throws IOException {
            this.behavior = behavior;
            listener = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        executor.submit(() -> serve(socket));
                    }
                    catch (IOException closed) { if (!listener.isClosed()) failure.compareAndSet(null, closed); }
                }
            });
        }
        URI endpoint() { return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/"); }
        void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(2000);
                InputStream input = socket.getInputStream();
                String first = line(input);
                if (first == null) return;
                int length = 0;
                for (String header; !(header = line(input)).isEmpty();) {
                    int split = header.indexOf(':');
                    String name = header.substring(0, split);
                    String value = header.substring(split + 1).strip();
                    if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
                    if (name.equalsIgnoreCase("X-Amz-Target")) target.set(value);
                    if (name.equalsIgnoreCase("Content-Type")) contentType.set(value);
                    if (name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Authorization")) throw new AssertionError("Unexpected provider credentials");
                }
                if (input.readNBytes(length).length != length) throw new AssertionError("Provider request was not completely submitted");
                requests.incrementAndGet();
                if (behavior.equals("drop")) return;
                var output = socket.getOutputStream();
                if (behavior.equals("redirect")) {
                    output.write(("HTTP/1.1 307 Temporary Redirect\r\nLocation: " + endpoint() + "redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                }
                else if (behavior.equals("auth-challenge")) {
                    output.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=loopback\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                }
                else if (behavior.equals("declared-oversize")) {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + (CognitoEmailConfirmationService.MAX_BYTES + 1) + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                }
                else if (behavior.equals("chunked-oversize")) {
                    output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] bytes = ("{\"padding\":\"" + "x".repeat(CognitoEmailConfirmationService.MAX_BYTES) + "\"}").getBytes(StandardCharsets.UTF_8);
                    output.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(bytes);
                    output.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                else if (behavior.equals("slow-headers")) {
                    for (byte value : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII)) {
                        output.write(value);
                        output.flush();
                        Thread.sleep(30);
                    }
                }
                else if (behavior.equals("slow-body")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 32\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    for (byte value : ("{\"padding\":\"" + "x".repeat(18) + "\"}").getBytes(StandardCharsets.US_ASCII)) {
                        output.write(value);
                        output.flush();
                        Thread.sleep(40);
                    }
                }
                else if (behavior.equals("truncated")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
                }
                else {
                    String body = switch (behavior) {
                        case "malformed" -> "<html>private-provider-detail</html>";
                        case "multiple" -> "{} {}";
                        case "recipient-error" -> "{\"__type\":\"UserNotFoundException\",\"message\":\"private-provider-detail\"}";
                        case "exact-cap" -> "{\"Session\":\"" + "x".repeat(CognitoEmailConfirmationService.MAX_BYTES - 14) + "\"}";
                        default -> "{\"Session\":\"private-session\"}";
                    };
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    output.write(("HTTP/1.1 " + (behavior.equals("recipient-error") ? "400 Bad Request" : "200 OK")
                            + "\r\nContent-Type: application/x-amz-json-1.0\r\n" + (behavior.equals("encoded") ? "Content-Encoding: gzip\r\n" : "")
                            + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(bytes);
                }
                output.flush();
            }
            catch (IOException expectedDisconnect) { /* Deadline/byte-cap rejection may close the loopback socket. */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (RuntimeException | AssertionError unexpected) { failure.compareAndSet(null, unexpected); }
        }
        static String line(InputStream input) throws IOException {
            var bytes = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();
                if (bytes.size() > 8192) throw new IOException("Loopback header limit exceeded");
                bytes.write(value);
            }
            return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.US_ASCII);
        }
        @Override public void close() throws Exception {
            listener.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
        }
    }
}
