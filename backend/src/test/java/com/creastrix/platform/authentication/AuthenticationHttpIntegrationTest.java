package com.creastrix.platform.authentication;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.creastrix.platform.CreastrixApplication;
import com.creastrix.platform.support.OidcTestProvider;
import com.creastrix.platform.support.OidcTestProvider.Scenario;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.UserStatus;
import com.nimbusds.jose.util.JSONObjectUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.creastrix.platform.support.OidcTestProvider.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Real local backend HTTP + signed test-IdP protocol + PostgreSQL proofs (B).
 * The driver stops at frontend Location; it does not implement React, a proxy,
 * browser SameSite/HttpOnly enforcement, or an actual Auth0 walkthrough (F/P).
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationHttpIntegrationTest {

    private static final String CLIENT_ID = "backend-test-client";
    private static final String CLIENT_SECRET = "test-only-confidential-client-secret";
    private static final MutableClock CLOCK = new MutableClock();
    private static final Faults FAULTS = new Faults();
    private static final AtomicInteger NEXT_SUBJECT = new AtomicInteger();
    private static final Set<String> PILOT_SUBJECTS = IntStream.range(0, 500)
            .mapToObj(number -> "auth0|pilot-" + number).collect(Collectors.toUnmodifiableSet());
    private static int port;
    private static String origin;
    private static OidcTestProvider provider;
    private static ConfigurableApplicationContext application;
    private static Set<String> admitted = PILOT_SUBJECTS;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private final List<Browser> browsers = new ArrayList<>();
    private String subject;
    private JdbcTemplate jdbc;
    private Counts before;

    @BeforeAll
    static void startOwnedServers() throws Exception {
        try (ServerSocket reservation = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        // A bind race fails startup rather than selecting another origin silently.
        origin = "http://localhost:" + port;
        provider = new OidcTestProvider(CLIENT_ID, CLIENT_SECRET,
                origin + "/login/oauth2/code/auth0", Clock.systemUTC());
        try {
            startBackend();
        }
        catch (Throwable failedStartup) {
            provider.close();
            throw failedStartup;
        }
    }

    private static void startBackend() {
        application = new SpringApplicationBuilder(TestWiring.class, CreastrixApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=" + port,
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.hikari.connection-timeout=1000",
                        "--creastrix.auth.enabled=true",
                        "--server.servlet.session.cookie.secure=false",
                        "--server.servlet.session.cookie.http-only=true",
                        "--server.servlet.session.cookie.same-site=lax",
                        "--server.servlet.session.cookie.path=/",
                        "--server.servlet.session.tracking-modes=cookie",
                        "--spring.main.banner-mode=off");
    }

    @AfterAll
    static void stopOwnedServers() {
        try {
            if (application != null) {
                application.close();
            }
        }
        finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @BeforeEach
    void freshFlow() {
        CLOCK.reset();
        FAULTS.reset();
        provider.reset();
        subject = "auth0|pilot-" + NEXT_SUBJECT.getAndIncrement();
        provider.scenario(Scenario.verified(subject));
        jdbc = application.getBean(JdbcTemplate.class);
        before = counts();
    }

    @AfterEach
    void noLeakedFixtureFailureOrWorkspace() {
        FAULTS.reset();
        CLOCK.reset();
        try {
            assertThat(provider.failure()).isNull();
            assertThat(counts().workspaces()).isZero();
            assertThat(counts().profiles()).isEqualTo(counts().users());
        }
        finally {
            browsers.forEach(Browser::close);
        }
    }

    @Test
    void fullCodeTokenJwksUserInfoFlowRotatesSessionAndReturnsOnlyOwnAccount(CapturedOutput logs)
            throws Exception {
        Browser browser = browser();
        String csrf = csrf(browser);
        String anonymousId = browser.sessionId();
        HttpResponse<String> initiation = loginPost(browser, csrf, Map.of(
                "Forwarded", "host=attacker.test;proto=https", "X-Forwarded-Host", "attacker.test"));
        assertRedirect(initiation, origin + "/oauth2/authorization/auth0");
        URI callback = authorize(browser, initiation);
        assertRedirect(browser.request("GET", callback, "", Map.of(
                "Forwarded", "host=attacker.test;proto=https", "X-Forwarded-Host", "attacker.test")),
                origin + "/account");
        String authenticatedId = browser.sessionId();
        assertThat(authenticatedId).isNotEqualTo(anonymousId);
        assertThat(probe().sessions).doesNotContainKey(anonymousId).containsKey(authenticatedId);
        assertThat(hasAuthentication(probe().sessions.get(authenticatedId))).isTrue();
        assertThat(hasAuthorizedClient(probe().sessions.get(authenticatedId))).isTrue();
        HttpResponse<String> account = browser.get("/api/me?userId=" + UUID.randomUUID());
        assertThat(account.statusCode()).isEqualTo(200);
        assertThat(json(account).keySet()).containsExactlyInAnyOrder("id", "status");
        assertThat(json(account).get("status")).isEqualTo("ACTIVE");
        assertThat(json(account).get("id")).isEqualTo(bindingId().toString());
        assertNoPrivateProtocolData(account);
        assertThat(account.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        String rotatedCsrf = csrf(browser);
        assertThat(rotatedCsrf).isNotEqualTo(csrf);
        assertThat(counts()).isEqualTo(before.plusAccount());

        Map<String, String> authorization = provider.authorizationRequests().getFirst();
        Map<String, String> token = provider.tokenRequests().getFirst();
        assertThat(authorization).containsEntry("redirect_uri", origin + "/login/oauth2/code/auth0")
                .containsEntry("client_id", CLIENT_ID).containsEntry("code_challenge_method", "S256")
                .containsEntry("prompt", "login").containsEntry("max_age", "0");
        assertThat(token).containsEntry("redirect_uri", origin + "/login/oauth2/code/auth0");
        assertThat(token.get("code_verifier")).isNotBlank();
        assertThat(authorization).doesNotContainKeys("client_secret", "code_verifier", "access_token");
        assertThat(provider.jwksRequests()).isPositive();
        assertThat(provider.userInfoRequests()).isEqualTo(1);
        assertThat(provider.issuedTokenValues().size()).isEqualTo(2);
        var privateValues = new ArrayList<>(provider.issuedTokenValues());
        privateValues.addAll(List.of(CLIENT_SECRET, subject, csrf, rotatedCsrf, anonymousId, authenticatedId,
                OidcTestProvider.parameters(callback.getRawQuery()).get("code"),
                authorization.get("state"), authorization.get("nonce"), token.get("code_verifier")));
        // Boolean assertions deliberately avoid printing token/cookie values if this proof fails.
        assertThat(privateValues.stream().allMatch(value -> !value.isBlank()))
                .as("All log non-disclosure controls contain actual generated values").isTrue();
        assertThat(privateValues.stream().noneMatch(logs.getAll()::contains))
                .as("Captured logs contain no issued tokens, cookies, CSRF or OIDC flow credentials").isTrue();
    }

    static Stream<Arguments> rawClaimFailures() {
        List<Arguments> cases = new ArrayList<>();
        for (String surface : List.of("id", "userinfo")) {
            for (Object value : new Object[] {"true", 1, null, false}) {
                cases.add(Arguments.of(surface, "email_verified", value, false));
            }
            cases.add(Arguments.of(surface, "email_verified", null, true));
            for (Object value : new Object[] {"not-a-number", "1", true, null, 1.5, -1}) {
                cases.add(Arguments.of(surface, "auth_time", value, false));
            }
            cases.add(Arguments.of(surface, "email", "", false));
            cases.add(Arguments.of(surface, "email", 17, false));
        }
        cases.add(Arguments.of("id", "auth_time", null, true));
        return cases.stream();
    }

    @ParameterizedTest(name = "raw {0} {1}={2}, missing={3}")
    @MethodSource("rawClaimFailures")
    void rawClaimsAreRejectedBeforeAnyAccountSideEffects(String surface, String claim,
            Object value, boolean missing) throws Exception {
        Scenario scenario = Scenario.verified(subject);
        if (surface.equals("id")) {
            scenario = missing ? scenario.withoutIdClaim(claim) : scenario.idClaim(claim, value);
        }
        else {
            scenario = missing ? scenario.withoutUserInfoClaim(claim) : scenario.userInfoClaim(claim, value);
        }
        assertRejectedWithoutAccount(scenario);
        assertThat(provider.tokenRequests()).hasSize(1);
        if (surface.equals("userinfo")) {
            assertThat(provider.userInfoRequests()).isEqualTo(1);
        }
    }

    static Stream<String> protocolFailures() {
        return Stream.of("signature", "key", "algorithm", "issuer", "issuer-missing", "audience", "audience-missing",
                "azp", "azp-number", "multi-audience-no-azp", "expired", "expiry-missing", "issued-in-future", "iat-missing",
                "nonce", "nonce-missing", "state", "token", "userinfo-subject", "subject-blank",
                "subject-number", "subject-missing", "auth-time-stale", "auth-time-future", "admission");
    }

    @ParameterizedTest
    @MethodSource("protocolFailures")
    void protocolAndFreshnessFailuresNeverReachBinding(String fault) throws Exception {
        long now = Instant.now().getEpochSecond();
        Scenario valid = Scenario.verified(subject);
        Scenario invalid = switch (fault) {
            case "signature" -> valid.badSignature();
            case "key" -> valid.unknownKey();
            case "algorithm" -> valid.unacceptedAlgorithm();
            case "issuer" -> valid.idClaim("iss", "https://other.example.test/");
            case "issuer-missing" -> valid.withoutIdClaim("iss");
            case "audience" -> valid.idClaim("aud", "another-client");
            case "audience-missing" -> valid.withoutIdClaim("aud");
            case "azp" -> valid.idClaim("azp", "another-client");
            case "azp-number" -> valid.idClaim("azp", 42);
            case "multi-audience-no-azp" -> valid.idClaim("aud", List.of(CLIENT_ID, "another-client"));
            case "expired" -> valid.idClaim("exp", now - 3600);
            case "expiry-missing" -> valid.withoutIdClaim("exp");
            case "issued-in-future" -> valid.idClaim("iat", now + 3600);
            case "iat-missing" -> valid.withoutIdClaim("iat");
            case "nonce" -> valid.idClaim("nonce", "not-the-request-nonce");
            case "nonce-missing" -> valid.withoutIdClaim("nonce");
            case "state" -> valid.badState();
            case "token" -> valid.tokenFailure();
            case "userinfo-subject" -> valid.userInfoClaim("sub", "auth0|another-person");
            case "subject-blank" -> valid.idClaim("sub", " ");
            case "subject-number" -> valid.idClaim("sub", 123);
            case "subject-missing" -> valid.withoutIdClaim("sub");
            case "auth-time-stale" -> valid.idClaim("auth_time", now - 120);
            case "auth-time-future" -> valid.idClaim("auth_time", now + 120);
            case "admission" -> Scenario.verified("auth0|not-admitted");
            default -> throw new AssertionError(fault);
        };
        assertRejectedWithoutAccount(invalid);
    }

    @Test
    void expiredAuthorizationFlowFailsBeforeAccountCreation() throws Exception {
        Browser browser = browser();
        URI callback = begin(browser);
        CLOCK.advance(Duration.ofMinutes(6));
        assertRedirect(browser.get(callback), origin + "/login?auth=failed");
        assertNoAccountOrAuthentication(browser);
    }

    @Test
    void verificationRequiresNewFlowAndExactSubjectRepeatKeepsUuidWithoutEmailMerging() throws Exception {
        assertRejectedWithoutAccount(Scenario.verified(subject).idClaim("email_verified", false));
        provider.scenario(Scenario.verified(subject));
        Browser browser = browser();
        complete(browser);
        UUID id = bindingId();
        logout(browser);
        complete(browser);
        assertThat(bindingId()).isEqualTo(id);
        assertThat(counts()).isEqualTo(before.plusAccount());

        provider.scenario(Scenario.verified("auth0|pilot-499"));
        Browser other = browser();
        complete(other);
        assertThat(json(other.get("/api/me")).get("id")).isNotEqualTo(id.toString());
        assertThat(counts()).isEqualTo(before.plusAccount().plusAccount());
    }

    @Test
    void replayedCallbackAndConsumedCodeCannotCreateAnotherAccount() throws Exception {
        Browser browser = browser();
        URI callback = begin(browser);
        assertRedirect(browser.get(callback), origin + "/account");
        UUID id = bindingId();
        Map<String, String> token = provider.tokenRequests().getFirst();
        String body = token.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> repeatedToken = browser.post(URI.create(provider.endpoint("token")), body,
                Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8))));
        assertThat(repeatedToken.statusCode()).isEqualTo(400);
        assertThat(json(repeatedToken).get("error")).isEqualTo("invalid_grant");
        String session = browser.sessionId();
        int exchanges = provider.tokenRequests().size();
        OAuth2AuthorizedClient client = authorizedClient(probe().sessions.get(session));
        HttpResponse<String> replay = browser.get(callback);
        assertRedirect(replay, origin + "/login?auth=failed");
        assertThat(replay.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(browser.sessionId()).isEqualTo(session);
        assertSessionIdentity(browser, id, client);
        assertThat(provider.tokenRequests()).hasSize(exchanges);
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(id.toString());
        assertThat(bindingId()).isEqualTo(id);
        assertThat(counts()).isEqualTo(before.plusAccount());
    }

    @Test
    void directEntryAndUnsolicitedCallbackCannotCreateAnAccount() throws Exception {
        Browser browser = browser();
        assertJsonError(browser.get("/oauth2/authorization/auth0"), 403);
        assertRedirect(browser.get("/login/oauth2/code/auth0?code=not-issued&state=not-saved"),
                origin + "/login?auth=failed");
        assertNoAccountOrAuthentication(browser);
        assertThat(provider.tokenRequests()).isEmpty();
    }

    @Test
    void csrfBootstrapMatchesNativeFormContractAndCookieHeaders() throws Exception {
        Browser browser = browser();
        HttpResponse<String> bootstrap = browser.get("/auth/csrf");
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        assertThat(json(bootstrap)).containsEntry("parameterName", "_csrf")
                .containsEntry("headerName", "X-CSRF-TOKEN");
        assertThat(json(bootstrap).keySet()).containsExactlyInAnyOrder("token", "parameterName", "headerName");
        assertThat(bootstrap.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        String cookie = bootstrap.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly", "Path=/", "SameSite=Lax")
                .doesNotContain("Domain=", "Secure");
        assertThat(hasAuthentication(probe().sessions.get(browser.sessionId()))).isFalse();
        assertRedirect(loginPost(browser, (String) json(bootstrap).get("token"), Map.of()),
                origin + "/oauth2/authorization/auth0");
        assertThat(counts()).isEqualTo(before);
    }

    static Stream<Arguments> unsafeFailures() {
        return Stream.of(Arguments.of("missing-origin", null, true),
                Arguments.of("null-origin", "null", true),
                Arguments.of("foreign-origin", "https://attacker.example.test", true),
                Arguments.of("missing-csrf", "same", false),
                Arguments.of("wrong-csrf", "same", true));
    }

    @ParameterizedTest
    @MethodSource("unsafeFailures")
    void unsafeOriginAndCsrfFailuresAreActual403WithoutRedirectOrSideEffects(
            String label, String requestedOrigin, boolean sendToken) throws Exception {
        Browser browser = browser();
        String token = csrf(browser);
        var headers = new LinkedHashMap<String, String>();
        if (requestedOrigin != null) {
            headers.put("Origin", "same".equals(requestedOrigin) ? origin : requestedOrigin);
        }
        String body = sendToken ? "_csrf=" + encode(label.equals("wrong-csrf") ? "not-the-token" : token) : "";
        assertJsonError(browser.post(URI.create(origin + "/auth/login"), body, headers), 403);
        assertJsonError(browser.post(URI.create(origin + "/auth/logout"), body, headers), 403);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertNoAccountOrAuthentication(browser);
    }

    @Test
    void staleCsrfAfterLoginFailsAndAuthenticatedLoginCannotReplacePrincipal() throws Exception {
        Browser browser = browser();
        String oldCsrf = csrf(browser);
        assertRedirect(browser.get(authorize(browser, loginPost(browser, oldCsrf, Map.of()))), origin + "/account");
        assertJsonError(browser.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", oldCsrf)), 403);
        assertJsonError(loginPost(browser, csrf(browser), Map.of()), 409);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
        assertThat(counts()).isEqualTo(before.plusAccount());
    }

    @Test
    void logoutDestroysServerSessionAndClientStateAndOldCookieCannotReturn() throws Exception {
        Browser browser = browser();
        complete(browser);
        String oldId = browser.sessionId();
        assertThat(hasAuthorizedClient(probe().sessions.get(oldId))).isTrue();
        assertJsonError(browser.get("/auth/logout"), 403);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
        HttpResponse<String> response = logout(browser);
        String deletion = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        // Tomcat represents immediate deletion with a past Expires, not necessarily Max-Age=0.
        HttpCookie deleted = HttpCookie.parse(deletion).getFirst();
        assertThat(deleted.getValue()).isEmpty();
        assertThat(deleted.getMaxAge()).isZero();
        assertThat(deleted.getPath()).isEqualTo("/");
        assertThat(deleted.getDomain()).isNull();
        assertThat(deleted.isHttpOnly()).isTrue();
        assertThat(deletion).contains("SameSite=Lax");
        assertThat(probe().sessions).doesNotContainKey(oldId);
        Browser stale = browser();
        assertJsonError(stale.get("/api/me", Map.of("Cookie", "JSESSIONID=" + oldId)), 401);
        assertJsonError(browser.get("/api/me"), 401);
    }

    @Test
    void suspendedAccountInvalidatesSessionAndReactivationDoesNotResurrectIt() throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID id = bindingId();
        String oldSession = browser.sessionId();
        application.getBean(UserService.class).changeStatus(id, UserStatus.SUSPENDED);
        assertJsonError(browser.get("/api/me"), 403);
        assertThat(probe().sessions).doesNotContainKey(oldSession);
        application.getBean(UserService.class).changeStatus(id, UserStatus.ACTIVE);
        assertJsonError(browser.get("/api/me"), 401);
        assertJsonError(browser.get("/api/me", Map.of("Cookie", "JSESSIONID=" + oldSession)), 401);
        logout(browser);
        complete(browser);
        assertThat(bindingId()).isEqualTo(id);
        assertThat(counts()).isEqualTo(before.plusAccount());
    }

    @Test
    void inactiveAccountCanStillObtainCsrfAndLogoutWithoutPrivateAuthority() throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID id = bindingId();
        String oldSession = browser.sessionId();
        application.getBean(UserService.class).changeStatus(id, UserStatus.DEACTIVATED);
        logout(browser);
        assertThat(probe().sessions).doesNotContainKey(oldSession);
        assertJsonError(browser.get("/api/me"), 401);
        assertRejectedWithoutAccount(Scenario.verified(subject), before.plusAccount());
        assertThat(application.getBean(UserService.class).findUser(id).status()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void idleAndAbsoluteExpiryDestroyClientStateAndNeverReturnPrivateData() throws Exception {
        Browser idle = browser();
        complete(idle);
        String idleId = idle.sessionId();
        HttpSession idleSession = probe().sessions.get(idleId);
        assertThat(idleSession.getMaxInactiveInterval()).isEqualTo(1800);
        // Exercise the real container idle clock without a 30-minute test sleep.
        // Only this test-owned session's TTL is shortened; production stays 1800s.
        idleSession.setMaxInactiveInterval(1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertJsonError(idle.get("/api/me"), 401));
        assertThat(probe().sessions).doesNotContainKey(idleId);
        Browser absolute = browser();
        complete(absolute);
        String absoluteId = absolute.sessionId();
        for (int count = 0; count < 24; count++) {
            CLOCK.advance(Duration.ofMinutes(19));
            assertThat(absolute.get("/api/me").statusCode()).isEqualTo(200);
        }
        CLOCK.advance(Duration.ofMinutes(25));
        assertJsonError(absolute.get("/api/me"), 401);
        assertThat(probe().sessions).doesNotContainKey(absoluteId);
    }

    @Test
    void databaseAcquisitionFailureFailsClosedAndDoesNotPretendSessionWasDestroyed() throws Exception {
        Browser browser = browser();
        complete(browser);
        String id = browser.sessionId();
        FAULTS.failAcquisition.set(true);
        try {
            assertJsonError(browser.get("/api/me"), 503);
            assertThat(probe().sessions).containsKey(id);
        }
        finally {
            FAULTS.failAcquisition.set(false);
        }
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
    }

    @Test
    void failedLogoutIsNotReportedAsSuccessAndDoesNotInvalidateSession() throws Exception {
        Browser browser = browser();
        complete(browser);
        String oldId = browser.sessionId();
        assertJsonError(browser.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", "invalid")), 403);
        assertThat(probe().sessions).containsKey(oldId);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
    }

    @Test
    void actualCommitAcknowledgementLossResolvesThroughNewFullLoginOfSameIdentity() throws Exception {
        Browser browser = browser();
        URI callback = begin(browser);
        FAULTS.loseNextCommitAcknowledgement.set(true);
        assertRedirect(browser.get(callback), origin + "/login?auth=failed");
        assertThat(FAULTS.committedThenFailed.get()).isEqualTo(1);
        assertThat(counts()).isEqualTo(before.plusAccount());
        UUID committedId = bindingId();
        assertJsonError(browser.get("/api/me"), 401);
        int oldTokenCalls = provider.tokenRequests().size();
        complete(browser);
        assertThat(provider.tokenRequests()).hasSize(oldTokenCalls + 1);
        assertThat(bindingId()).isEqualTo(committedId);
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(committedId.toString());
        assertThat(counts()).isEqualTo(before.plusAccount());
        assertThat(provider.tokenRequests().get(0).get("code"))
                .isNotEqualTo(provider.tokenRequests().get(1).get("code"));
    }

    @ParameterizedTest(name = "new full login while first transaction commits={0}")
    @ValueSource(booleans = {true, false})
    void newFullLoginWaitsForExactInFlightIdentityAndResolvesCommitOrRollback(boolean commitFirst)
            throws Exception {
        Browser firstBrowser = browser();
        Browser secondBrowser = browser();
        URI firstCallback = begin(firstBrowser);
        BindingRace race = new BindingRace(commitFirst);
        FAULTS.bindingRace = race;
        var executor = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> first = null;
        Future<HttpResponse<String>> second = null;
        try {
            first = executor.submit(() -> firstBrowser.get(firstCallback));
            assertThat(race.firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.isDone()).isFalse();
            assertThat(race.firstPid.get()).isNotNull();
            // The first real INSERT is still uncommitted; this is not evidence of rollback.
            assertThat(counts()).isEqualTo(before);
            URI secondCallback = begin(secondBrowser);
            second = executor.submit(() -> secondBrowser.get(secondCallback));
            assertThat(race.secondAttempted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(race.secondPid.get()).isNotNull().isNotEqualTo(race.firstPid.get());
            Future<HttpResponse<String>> firstPending = first;
            Future<HttpResponse<String>> secondPending = second;
            JdbcTemplate observer = new JdbcTemplate(jdbc.getDataSource());
            observer.setQueryTimeout(1);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(firstPending.isDone()).isFalse();
                assertThat(secondPending.isDone()).isFalse();
                assertThat(observer.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity waiter
                            JOIN pg_stat_activity blocker ON blocker.pid = ?
                            WHERE waiter.pid = ? AND waiter.datname = current_database()
                              AND blocker.datname = current_database()
                              AND waiter.wait_event_type = 'Lock'
                              AND ? = ANY(pg_blocking_pids(waiter.pid)))
                        """, Boolean.class, race.firstPid.get(), race.secondPid.get(), race.firstPid.get()))
                        .isTrue();
            });
            race.releaseFirst.countDown();
            assertRedirect(first.get(10, TimeUnit.SECONDS),
                    origin + (commitFirst ? "/account" : "/login?auth=failed"));
            assertRedirect(second.get(10, TimeUnit.SECONDS), origin + "/account");
            assertThat(race.attempts.get()).isEqualTo(2);
            assertThat(race.firstCommits.get()).isEqualTo(commitFirst ? 1 : 0);
            assertThat(race.firstRollbacks.get()).isEqualTo(commitFirst ? 0 : 1);
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(provider.tokenRequests().get(0).get("code"))
                    .isNotEqualTo(provider.tokenRequests().get(1).get("code"));
            UUID winner = commitFirst ? race.firstUser.get() : race.secondUser.get();
            UUID rolledBack = commitFirst ? race.secondUser.get() : race.firstUser.get();
            assertThat(winner).isNotNull().isNotEqualTo(rolledBack);
            assertThat(bindingId()).isEqualTo(winner);
            assertThat(json(secondBrowser.get("/api/me")).get("id")).isEqualTo(winner.toString());
            if (commitFirst) {
                assertThat(json(firstBrowser.get("/api/me")).get("id")).isEqualTo(winner.toString());
            }
            else {
                assertJsonError(firstBrowser.get("/api/me"), 401);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, rolledBack))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id = ?",
                    Long.class, rolledBack)).isZero();
            assertThat(counts()).isEqualTo(before.plusAccount());
        }
        finally {
            race.releaseFirst.countDown();
            if (first != null && !first.isDone()) {
                first.cancel(true);
            }
            if (second != null && !second.isDone()) {
                second.cancel(true);
            }
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            finally {
                FAULTS.bindingRace = null;
            }
        }
    }

    @Test
    void realBackendRestartClearsSessionsAndRestartedAdmissionRejectsOldIdentity() throws Exception {
        Browser browser = browser();
        complete(browser);
        String oldSession = browser.sessionId();
        UUID original = bindingId();
        application.close();
        admitted = Set.of();
        try {
            startBackend();
            jdbc = application.getBean(JdbcTemplate.class);
            assertThat(probe().sessions).doesNotContainKey(oldSession);
            assertJsonError(browser.get("/api/me"), 401);
            assertRejectedWithoutAccount(Scenario.verified(subject), before.plusAccount());
            assertThat(bindingId()).isEqualTo(original);
        }
        finally {
            application.close();
            admitted = PILOT_SUBJECTS;
            startBackend();
            jdbc = application.getBean(JdbcTemplate.class);
        }
        assertJsonError(browser.get("/api/me"), 401);
        complete(browser);
        assertThat(bindingId()).isEqualTo(original);
    }

    @Test
    void unknownRoutesFrontendPagesAssetsAndManagementDoNotExposeHtmlOrPrivateContent() throws Exception {
        Browser browser = browser();
        for (String path : List.of("/login", "/account", "/login.html", "/account.html",
                "/assets/auth.js", "/assets/auth.css", "/unknown", "/actuator/info", "/actuator/env",
                "/actuator/health/liveness", "/actuator/health/readiness")) {
            HttpResponse<String> response = browser.get(path);
            assertThat(response.statusCode()).as(path).isIn(401, 403, 404);
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertThat(response.body().toLowerCase()).doesNotContain("<html", "<form", "<script", "password");
            assertNoPrivateProtocolData(response);
        }
        assertJsonError(browser.get("/api/me"), 401);
        HttpResponse<String> health = browser.get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json");
        Map<String, Object> healthBody = JSONObjectUtils.parse(health.body());
        // Boot 4.1 enables these probe group names by default; no component details are exposed.
        assertThat(healthBody).containsExactlyInAnyOrderEntriesOf(
                Map.of("status", "UP", "groups", List.of("liveness", "readiness")));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void hostAndCorsNegativesCannotChangeTrustedRedirectOrAuthorizeCrossOrigin() throws Exception {
        String raw = rawRequest("GET /auth/csrf HTTP/1.1\r\nHost: attacker.test\r\n"
                + "Forwarded: host=localhost:" + port + ";proto=http\r\nConnection: close\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 403").doesNotContain("Location:", CLIENT_SECRET);
        Browser browser = browser();
        HttpResponse<String> preflight = browser.request("OPTIONS", URI.create(origin + "/auth/login"), "",
                Map.of("Origin", "https://attacker.test", "Access-Control-Request-Method", "POST"));
        assertThat(preflight.statusCode()).isIn(401, 403);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        HttpResponse<String> response = loginPost(browser, csrf(browser),
                Map.of("Forwarded", "host=attacker.test;proto=https", "X-Forwarded-Proto", "https"));
        assertRedirect(response, origin + "/oauth2/authorization/auth0");
        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61pi/me", "/a%70i/me", "/api/%6de"})
    void routeSuspendedAccountCannotDisclosePrivateBody(String path) throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID id = bindingId();
        String session = browser.sessionId();
        application.getBean(UserService.class).changeStatus(id, UserStatus.SUSPENDED);
        assertJsonError(browser.get(path), 403);
        assertThat(probe().sessions).doesNotContainKey(session);
        assertJsonError(browser.get("/api/me"), 401);
        assertThat(counts()).isEqualTo(before.plusAccount());
        assertThat(provider.tokenRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61pi/me", "/a%70i/me", "/api/%6de"})
    void routeAbsoluteExpiryCannotDisclosePrivateBody(String path) throws Exception {
        Browser browser = browser();
        complete(browser);
        String session = browser.sessionId();
        CLOCK.advance(AuthenticationProperties.ABSOLUTE_LIFETIME);
        assertJsonError(browser.get(path), 401);
        assertThat(probe().sessions).doesNotContainKey(session);
        assertJsonError(browser.get("/api/me"), 401);
        assertThat(counts()).isEqualTo(before.plusAccount());
        assertThat(provider.tokenRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%6fauth2/authorization/auth0", "/oauth2/%61uthorization/auth0",
            "/oauth2/authorization/%61uth0"})
    void routeEntryRequiresExplicitIntentBeforeProviderNavigation(String path) throws Exception {
        Browser browser = browser();
        csrf(browser);
        assertJsonError(browser.get(path), 403);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(provider.tokenRequests()).isEmpty();
        assertNoAccountOrAuthentication(browser);
        complete(browser);
        assertThat(counts()).isEqualTo(before.plusAccount());
        logout(browser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%6fauth2/authorization/auth0", "/oauth2/%61uthorization/auth0",
            "/oauth2/authorization/%61uth0"})
    void routeAuthenticatedEntryCannotReplacePrincipalWithoutLogout(String path) throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID original = bindingId();
        String session = browser.sessionId();
        String otherSubject = "auth0|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS).contains(otherSubject);
        provider.scenario(Scenario.verified(otherSubject));
        assertJsonError(browser.get(path), 409);
        assertThat(browser.sessionId()).isEqualTo(session);
        assertThat(hasAuthentication(probe().sessions.get(session))).isTrue();
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before.plusAccount());
        assertThat(provider.authorizationRequests()).hasSize(1);
        assertThat(provider.tokenRequests()).hasSize(1);
        logout(browser);
        complete(browser);
        assertThat(json(browser.get("/api/me")).get("id")).isNotEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before.plusAccount().plusAccount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61uth/csrf", "/a%75th/csrf", "/auth/%63srf"})
    void routeWrongHostCannotBootstrapSession(String path) throws Exception {
        Set<String> sessions = Set.copyOf(probe().sessions.keySet());
        String raw = rawRequest("GET " + path + " HTTP/1.1\r\nHost: attacker.test\r\n"
                + "Forwarded: host=localhost:" + port + ";proto=http\r\nConnection: close\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 403");
        String headers = raw.substring(0, raw.indexOf("\r\n\r\n")).toLowerCase();
        assertThat(headers).doesNotContain("location:", "set-cookie:")
                .contains("content-type: application/json", "cache-control: no-store");
        assertThat(raw.substring(raw.indexOf("\r\n\r\n") + 4).trim())
                .isEqualTo("{\"error\":\"request_not_completed\"}");
        assertThat(probe().sessions.keySet()).containsExactlyInAnyOrderElementsOf(sessions);
        assertThat(counts()).isEqualTo(before);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(provider.tokenRequests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61uth/login", "/a%75th/login", "/auth/%6cogin"})
    void routeWrongOriginCannotCreateIntentWithValidCsrf(String path) throws Exception {
        Browser browser = browser();
        String token = csrf(browser);
        assertJsonError(browser.post(URI.create(origin + path), "_csrf=" + encode(token),
                Map.of("Origin", "https://attacker.test")), 403);
        assertJsonError(browser.get("/oauth2/authorization/auth0"), 403);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(provider.tokenRequests()).isEmpty();
        assertNoAccountOrAuthentication(browser);
        // The same token succeeds with the correct Origin: bad CSRF is not the negative's cause.
        assertRedirect(browser.get(authorize(browser, loginPost(browser, token, Map.of()))), origin + "/account");
        assertThat(counts()).isEqualTo(before.plusAccount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%6cogin/oauth2/code/auth0", "/login/%6fauth2/code/auth0",
            "/login/oauth2/%63ode/auth0"})
    void routePostCallbackIsRejectedBeforeTokenExchangeWithValidFlowAndCsrf(String path) throws Exception {
        Browser browser = browser();
        URI callback = begin(browser);
        String token = csrf(browser);
        URI alias = URI.create(origin + path + "?" + callback.getRawQuery());
        assertJsonError(browser.post(alias, "_csrf=" + encode(token), Map.of("Origin", origin)), 403);
        assertThat(provider.authorizationRequests()).hasSize(1);
        assertThat(provider.tokenRequests()).isEmpty();
        assertNoAccountOrAuthentication(browser);
        // The exact saved state/code remain usable with the allowed method.
        assertRedirect(browser.get(callback), origin + "/account");
        assertThat(provider.tokenRequests()).hasSize(1);
        assertThat(counts()).isEqualTo(before.plusAccount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login/oauth2/code/auth0", "/%6cogin/oauth2/code/auth0",
            "/login/oauth2/code/%61uth0"})
    void routeAlreadyPendingSecondCallbackCannotReplaceAuthenticatedPrincipal(String path) throws Exception {
        Browser browser = browser();
        URI firstCallback = begin(browser);
        BindingRace race = new BindingRace(true);
        FAULTS.bindingRace = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> first = null;
        URI secondCallback;
        String anonymousSession = browser.sessionId();
        String otherSubject = "auth0|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS).contains(otherSubject);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity_bindings WHERE issuer = ? AND subject = ?",
                Long.class, provider.issuer().toString(), otherSubject)).isZero();
        try {
            first = executor.submit(() -> browser.get(firstCallback));
            assertThat(race.firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.isDone()).isFalse();
            assertThat(counts()).isEqualTo(before);
            assertThat(race.attempts.get()).isEqualTo(1);
            assertThat(hasAuthentication(probe().sessions.get(browser.sessionId()))).isFalse();
            provider.scenario(Scenario.verified(otherSubject));
            secondCallback = begin(browser);
            assertThat(first.isDone()).isFalse();
            assertThat(provider.authorizationRequests()).hasSize(2);
            assertThat(provider.tokenRequests()).hasSize(1);
            assertSavedFlow(browser, secondCallback);
            race.releaseFirst.countDown();
            assertRedirect(first.get(10, TimeUnit.SECONDS), origin + "/account");
            assertThat(race.firstCommits.get()).isEqualTo(1);
        }
        finally {
            race.releaseFirst.countDown();
            if (first != null && !first.isDone()) {
                first.cancel(true);
            }
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            finally {
                FAULTS.bindingRace = null;
            }
        }
        UUID original = bindingId();
        String session = browser.sessionId();
        assertThat(session).isNotEqualTo(anonymousSession);
        assertThat(counts()).isEqualTo(before.plusAccount());
        assertSavedFlow(browser, secondCallback);
        OAuth2AuthorizedClient client = authorizedClient(probe().sessions.get(session));
        HttpResponse<String> conflict = browser.get(URI.create(origin + path + "?" + secondCallback.getRawQuery()));
        assertRedirect(conflict, origin + "/login?auth=failed");
        assertThat(conflict.headers().allValues("Set-Cookie")).isEmpty();
        assertFlowNotSaved(browser, secondCallback);
        assertSessionIdentity(browser, original, client);
        assertThat(provider.tokenRequests()).hasSize(1);
        assertThat(race.attempts.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity_bindings WHERE issuer = ? AND subject = ?",
                Long.class, provider.issuer().toString(), otherSubject)).isZero();
        assertThat(browser.sessionId()).isEqualTo(session);
        assertThat(hasAuthentication(probe().sessions.get(session))).isTrue();
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before.plusAccount());
        logout(browser);
        complete(browser);
        assertThat(json(browser.get("/api/me")).get("id")).isNotEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before.plusAccount().plusAccount());
    }

    private static void assertSavedFlow(Browser browser, URI callback) {
        HttpSession session = probe().sessions.get(browser.sessionId());
        assertThat(session).isNotNull();
        String state = OidcTestProvider.parameters(callback.getRawQuery()).get("state");
        assertThat(Collections.list(session.getAttributeNames()).stream().map(session::getAttribute)
                .anyMatch(value -> value instanceof OAuth2AuthorizationRequest saved
                        && saved.getState().equals(state))).as("Exact pending OAuth flow remains saved").isTrue();
    }

    private static void assertFlowNotSaved(Browser browser, URI callback) {
        HttpSession session = probe().sessions.get(browser.sessionId());
        assertThat(session).isNotNull();
        String state = OidcTestProvider.parameters(callback.getRawQuery()).get("state");
        assertThat(Collections.list(session.getAttributeNames()).stream().map(session::getAttribute)
                .noneMatch(value -> value instanceof OAuth2AuthorizationRequest saved
                        && saved.getState().equals(state))).as("Only the rejected exact flow is consumed").isTrue();
    }

    static Stream<Arguments> routeAliases() {
        return Stream.of(
                Arguments.of("/%61uth/csrf", "/%61uth/login", "/%6fauth2/authorization/auth0",
                        "/%6cogin/oauth2/code/auth0", "/%61pi/me", "/%61uth/logout"),
                Arguments.of("/a%75th/csrf", "/a%75th/login", "/oauth2/%61uthorization/auth0",
                        "/login/%6fauth2/code/auth0", "/a%70i/me", "/a%75th/logout"),
                Arguments.of("/auth/%63srf", "/auth/%6cogin", "/oauth2/authorization/%61uth0",
                        "/login/oauth2/code/%61uth0", "/api/%6de", "/auth/%6cogout"));
    }

    @ParameterizedTest
    @MethodSource("routeAliases")
    void routeAuthorizedAliasesPreserveOidcQueryAndLogout(String csrfPath, String loginPath, String entryPath,
            String callbackPath, String privatePath, String logoutPath) throws Exception {
        Browser browser = browser();
        String token = (String) json(browser.get(csrfPath)).get("token");
        String anonymousSession = browser.sessionId();
        assertRedirect(browser.post(URI.create(origin + loginPath), "_csrf=" + encode(token),
                Map.of("Origin", origin)), origin + "/oauth2/authorization/auth0");
        HttpResponse<String> entry = browser.get(entryPath);
        assertThat(entry.statusCode()).isEqualTo(302);
        URI callback = URI.create(location(browser.get(URI.create(location(entry)))));
        assertRedirect(browser.get(URI.create(origin + callbackPath + "?" + callback.getRawQuery())),
                origin + "/account");
        assertThat(browser.sessionId()).isNotEqualTo(anonymousSession);
        assertThat(probe().sessions).doesNotContainKey(anonymousSession);
        assertThat(json(browser.get(privatePath))).containsExactlyInAnyOrderEntriesOf(
                Map.of("id", bindingId().toString(), "status", "ACTIVE"));
        assertThat(provider.tokenRequests()).hasSize(1);
        assertThat(provider.tokenRequests().getFirst().get("code"))
                .isEqualTo(OidcTestProvider.parameters(callback.getRawQuery()).get("code"));
        assertThat(counts()).isEqualTo(before.plusAccount());
        String session = browser.sessionId();
        HttpResponse<String> loggedOut = browser.post(URI.create(origin + logoutPath), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", csrf(browser)));
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        assertThat(loggedOut.body()).isEmpty();
        assertThat(probe().sessions).doesNotContainKey(session);
        assertJsonError(browser.get(privatePath), 401);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCallbackFirstAdmissionExcludesOtherIdentity(boolean reverseIdentityOrder) throws Exception {
        Browser browser = browser();
        String otherSubject = "auth0|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS).contains(otherSubject);
        String winnerSubject = reverseIdentityOrder ? otherSubject : subject;
        String loserSubject = reverseIdentityOrder ? subject : otherSubject;
        provider.scenario(Scenario.verified(winnerSubject));
        URI winnerCallback = begin(browser);
        BindingRace race = new BindingRace(true);
        FAULTS.bindingRace = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> winner = null;
        try {
            winner = executor.submit(() -> browser.get(winnerCallback));
            assertThat(race.firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(winner.isDone()).isFalse();
            assertThat(counts()).isEqualTo(before);
            provider.scenario(Scenario.verified(loserSubject));
            URI loserCallback = begin(browser);
            HttpResponse<String> rejected = browser.get(loserCallback);
            // A real callback has already passed anonymous admission and inserted its binding.
            // The other identity must finish rejection BEFORE that transaction is released.
            assertRedirect(rejected, origin + "/login?auth=failed");
            assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(winner.isDone()).isFalse();
            assertThat(provider.tokenRequests()).hasSize(1);
            assertThat(race.attempts.get()).isEqualTo(1);
            assertThat(counts()).isEqualTo(before);
            race.releaseFirst.countDown();
            assertRedirect(winner.get(10, TimeUnit.SECONDS), origin + "/account");
            UUID winnerId = jdbc.queryForObject(
                    "SELECT user_id FROM user_identity_bindings WHERE issuer = ? AND subject = ?",
                    UUID.class, provider.issuer().toString(), winnerSubject);
            assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(winnerId.toString());
            assertSessionIdentity(browser, winnerId, authorizedClient(probe().sessions.get(browser.sessionId())));
            assertThat(bindingCount(loserSubject)).isZero();
            assertFlowNotSaved(browser, loserCallback);
            assertThat(counts()).isEqualTo(before.plusAccount());
            assertThat(race.firstCommits.get()).isEqualTo(1);
            assertThat(provider.tokenRequests()).hasSize(1);
        }
        finally {
            race.releaseFirst.countDown();
            try { finishWorkers(executor, winner); }
            finally { FAULTS.bindingRace = null; }
        }
    }

    @Test
    void independentBrowserSessionCompletesWhileOtherCallbackIsHeld() throws Exception {
        Browser heldBrowser = browser();
        Browser independent = browser();
        URI heldCallback = begin(heldBrowser);
        BindingRace race = new BindingRace(true);
        FAULTS.bindingRace = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> held = null;
        try {
            held = executor.submit(() -> heldBrowser.get(heldCallback));
            assertThat(race.firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            String independentSubject = nextSubject();
            provider.scenario(Scenario.verified(independentSubject));
            complete(independent);
            assertThat(held.isDone()).isFalse();
            assertThat(counts()).isEqualTo(before.plusAccount());
            UUID independentId = bindingId(independentSubject);
            OAuth2AuthorizedClient client = authorizedClient(probe().sessions.get(independent.sessionId()));
            assertSessionIdentity(independent, independentId, client);
            race.releaseFirst.countDown();
            assertRedirect(held.get(10, TimeUnit.SECONDS), origin + "/account");
            assertSessionIdentity(heldBrowser, bindingId(), authorizedClient(probe().sessions.get(heldBrowser.sessionId())));
            assertSessionIdentity(independent, independentId, client);
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(counts()).isEqualTo(before.plusAccount().plusAccount());
        }
        finally {
            race.releaseFirst.countDown();
            try { finishWorkers(executor, held); }
            finally { FAULTS.bindingRace = null; }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackFailurePreservesNewerSavedFlowAndReleasesAdmission(boolean databaseFailure) throws Exception {
        Browser browser = browser();
        provider.scenario(databaseFailure ? Scenario.verified(subject) : Scenario.verified(subject).tokenFailure());
        URI failedCallback = begin(browser);
        var tokenGate = databaseFailure ? null : provider.holdTokenResponse(subject);
        BindingRace race = databaseFailure ? new BindingRace(false) : null;
        FAULTS.bindingRace = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> failed = null;
        try {
            failed = executor.submit(() -> browser.get(failedCallback));
            assertThat(databaseFailure ? race.firstInserted.await(5, TimeUnit.SECONDS)
                    : tokenGate.awaitEntered(5, TimeUnit.SECONDS)).isTrue();
            String session = browser.sessionId();
            String nextSubject = nextSubject();
            provider.scenario(Scenario.verified(nextSubject));
            URI nextCallback = begin(browser);
            assertSavedFlow(browser, nextCallback);
            assertThat(failed.isDone()).isFalse();
            if (databaseFailure) race.releaseFirst.countDown();
            else tokenGate.close();
            HttpResponse<String> rejected = failed.get(10, TimeUnit.SECONDS);
            assertRedirect(rejected, origin + "/login?auth=failed");
            assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(browser.sessionId()).isEqualTo(session);
            assertSavedFlow(browser, nextCallback);
            assertThat(counts()).isEqualTo(before);
            if (databaseFailure) {
                assertThat(race.firstRollbacks.get()).isEqualTo(1);
                assertThat(race.firstCommits.get()).isZero();
            }
            assertRedirect(browser.get(nextCallback), origin + "/account");
            assertSessionIdentity(browser, bindingId(nextSubject), authorizedClient(probe().sessions.get(browser.sessionId())));
            assertThat(bindingCount(subject)).isZero();
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(counts()).isEqualTo(before.plusAccount());
        }
        finally {
            if (tokenGate != null) tokenGate.close();
            if (race != null) race.releaseFirst.countDown();
            try { finishWorkers(executor, failed); }
            finally { FAULTS.bindingRace = null; }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"token-success", "token-failure", "committed-success", "invalidated-token-success"})
    void sessionTerminationCompletesBeforeHeldCallbackAndLateCompletionPreservesNewLogin(String heldAt) throws Exception {
        boolean committed = heldAt.equals("committed-success");
        boolean tokenFailure = heldAt.equals("token-failure");
        boolean directInvalidation = heldAt.equals("invalidated-token-success");
        Browser browser = browser();
        provider.scenario(tokenFailure ? Scenario.verified(subject).tokenFailure() : Scenario.verified(subject));
        URI oldCallback = begin(browser);
        String oldSession = browser.sessionId();
        var tokenGate = committed ? null : provider.holdTokenResponse(subject);
        CommitPause commitPause = committed ? new CommitPause() : null;
        FAULTS.commitPause = commitPause;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> old = null;
        try {
            old = executor.submit(() -> browser.get(oldCallback));
            assertThat(committed ? commitPause.committed.await(5, TimeUnit.SECONDS)
                    : tokenGate.awaitEntered(5, TimeUnit.SECONDS)).isTrue();
            assertThat(old.isDone()).isFalse();
            assertThat(counts()).isEqualTo(committed ? before.plusAccount() : before);
            UUID committedId = committed ? bindingId() : null;
            // Termination must finish while the callback is held, not after fixture release.
            if (directInvalidation) {
                probe().sessions.get(oldSession).invalidate();
            }
            else {
                logout(browser);
            }
            assertThat(old.isDone()).isFalse();
            assertThat(probe().sessions).doesNotContainKey(oldSession);
            String newSubject = nextSubject();
            provider.scenario(Scenario.verified(newSubject));
            complete(browser);
            String newSession = browser.sessionId();
            UUID newId = bindingId(newSubject);
            OAuth2AuthorizedClient client = authorizedClient(probe().sessions.get(newSession));
            assertSessionIdentity(browser, newId, client);
            assertThat(old.isDone()).isFalse();
            if (committed) commitPause.release.countDown();
            else tokenGate.close();
            HttpResponse<String> late = old.get(10, TimeUnit.SECONDS);
            assertRedirect(late, origin + "/login?auth=failed");
            assertThat(late.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(browser.sessionId()).isEqualTo(newSession);
            assertThat(probe().sessions).doesNotContainKey(oldSession);
            assertSessionIdentity(browser, newId, client);
            assertThat(bindingCount(subject)).isEqualTo(tokenFailure ? 0 : 1);
            assertThat(counts()).isEqualTo(tokenFailure ? before.plusAccount() : before.plusAccount().plusAccount());
            assertThat(provider.tokenRequests()).hasSize(2);
            if (committed) {
                assertThat(bindingId()).isEqualTo(committedId);
                assertThat(commitPause.observedCommits.get()).isEqualTo(1);
                logout(browser);
                provider.scenario(Scenario.verified(subject));
                complete(browser);
                assertSessionIdentity(browser, committedId, authorizedClient(probe().sessions.get(browser.sessionId())));
                assertThat(counts()).isEqualTo(before.plusAccount().plusAccount());
            }
        }
        finally {
            if (tokenGate != null) tokenGate.close();
            if (commitPause != null) commitPause.release.countDown();
            try { finishWorkers(executor, old); }
            finally { FAULTS.commitPause = null; }
        }
    }

    @ParameterizedTest(name = "real rotation cancelled, unrelated cookie controls={0}")
    @ValueSource(booleans = {false, true})
    void cancelledRotationFailurePreservesNewLoginAndUnrelatedCookieScopes(boolean unrelatedCookies) throws Exception {
        Browser browser = browser();
        URI oldCallback = begin(browser);
        String anonymousId = browser.sessionId();
        HttpSession original = probe().sessions.get(anonymousId);
        assertThat(original).isNotNull();
        RotationPause pause = new RotationPause(original, unrelatedCookies);
        FAULTS.rotationPause = pause;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> old = null;
        try {
            old = executor.submit(() -> browser.get(oldCallback));
            assertThat(pause.rotated.await(5, TimeUnit.SECONDS))
                    .as("The exact old session reached the real container ID-change listener").isTrue();
            assertThat(pause.fixtureFailure.get()).isNull();
            assertThat(pause.listenerCalls.get()).isEqualTo(1);
            assertThat(pause.rotatedId.get() != null && !pause.rotatedId.get().equals(anonymousId)).isTrue();
            assertThat(probe().sessions.get(pause.rotatedId.get()) == original).isTrue();
            assertThat(hasAuthorizedClient(original)).isTrue();
            assertThat(hasAuthentication(original)).isFalse();
            assertThat(counts()).isEqualTo(before.plusAccount());
            UUID durableOldUser = bindingId();
            assertThat(old.isDone()).isFalse();

            // Genuine container invalidation completes before the listener is released.
            // The fixture does not write a stale cookie, manufacture a principal or hold Tomcat's monitor.
            original.invalidate();
            assertThatThrownBy(original::getAttributeNames).isInstanceOf(IllegalStateException.class);
            assertThat(probe().sessions).doesNotContainKeys(anonymousId, pause.rotatedId.get());
            assertThat(old.isDone()).isFalse();
            String newSubject = nextSubject();
            provider.scenario(Scenario.verified(newSubject));
            complete(browser);
            String newSession = browser.sessionId();
            UUID newUser = bindingId(newSubject);
            OAuth2AuthorizedClient newClient = authorizedClient(probe().sessions.get(newSession));
            assertSessionIdentity(browser, newUser, newClient);
            assertThat(newSession.equals(anonymousId) || newSession.equals(pause.rotatedId.get())).isFalse();
            assertThat(old.isDone()).isFalse();
            assertThat(provider.tokenRequests()).hasSize(2);

            pause.release.countDown();
            HttpResponse<String> late = old.get(10, TimeUnit.SECONDS);
            assertThat(pause.fixtureFailure.get()).isNull();
            assertThat(pause.nativeRotationReturned.get()).isEqualTo(1);
            assertThat(pause.actualPendingRotationCookie.get())
                    .as("Tomcat itself emitted the exact rotated cookie before cancellation cleanup").isTrue();
            assertThat(pause.uncommittedAfterRotation.get()).isTrue();
            assertRedirect(late, origin + "/login?auth=failed");
            assertThat(late.headers().allValues("Set-Cookie").stream().noneMatch(
                    AuthenticationHttpIntegrationTest::isHostOnlyRootSessionCookie))
                    .as("Rejected uncommitted callback has neither a setting nor deletion cookie in its own scope")
                    .isTrue();
            assertThat(late.headers().allValues("Set-Cookie"))
                    .containsExactlyInAnyOrderElementsOf(unrelatedCookies ? RotationPause.UNRELATED_COOKIES : List.of());
            assertThat(late.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(late.headers().firstValue("Pragma").orElseThrow()).isEqualTo("no-cache");
            assertThat(late.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
            assertThat(late.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
            assertThat(late.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
            assertThat(browser.sessionId().equals(newSession)).as("Late failure preserves the client's newer cookie")
                    .isTrue();
            assertThat(probe().sessions).doesNotContainKeys(anonymousId, pause.rotatedId.get());
            assertSessionIdentity(browser, newUser, newClient);
            assertThat(bindingId()).isEqualTo(durableOldUser);
            assertThat(counts()).isEqualTo(before.plusAccount().plusAccount());
            assertThat(provider.tokenRequests()).hasSize(2);
        }
        finally {
            pause.release.countDown();
            try { finishWorkers(executor, old); }
            finally { FAULTS.rotationPause = null; }
        }
    }

    private static boolean isHostOnlyRootSessionCookie(String header) {
        return HttpCookie.parse(header).stream().anyMatch(cookie -> cookie.getName().equals("JSESSIONID")
                && "/".equals(cookie.getPath()) && cookie.getDomain() == null);
    }

    @Test
    void staleAttemptFinallyCannotReleaseNewerBusyAdmission() throws Exception {
        Browser browser = browser();
        provider.scenario(Scenario.verified(subject).tokenFailure());
        URI oldCallback = begin(browser);
        var oldGate = provider.holdTokenResponse(subject);
        String newSubject = nextSubject();
        var newGate = provider.holdTokenResponse(newSubject);
        var executor = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> old = null;
        Future<HttpResponse<String>> current = null;
        try {
            old = executor.submit(() -> browser.get(oldCallback));
            assertThat(oldGate.awaitEntered(5, TimeUnit.SECONDS)).isTrue();
            logout(browser);
            assertThat(old.isDone()).isFalse();
            provider.scenario(Scenario.verified(newSubject));
            URI currentCallback = begin(browser);
            String newSession = browser.sessionId();
            current = executor.submit(() -> browser.get(currentCallback));
            assertThat(newGate.awaitEntered(5, TimeUnit.SECONDS)).isTrue();
            oldGate.close();
            HttpResponse<String> staleFailure = old.get(10, TimeUnit.SECONDS);
            assertRedirect(staleFailure, origin + "/login?auth=failed");
            assertThat(staleFailure.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(current.isDone()).isFalse();
            assertThat(browser.sessionId()).isEqualTo(newSession);
            String thirdSubject = nextSubject();
            provider.scenario(Scenario.verified(thirdSubject));
            URI competitor = begin(browser);
            HttpResponse<String> conflict = browser.get(competitor);
            assertRedirect(conflict, origin + "/login?auth=failed");
            assertThat(conflict.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(counts()).isEqualTo(before);
            assertThat(current.isDone()).isFalse();
            newGate.close();
            assertRedirect(current.get(10, TimeUnit.SECONDS), origin + "/account");
            assertSessionIdentity(browser, bindingId(newSubject), authorizedClient(probe().sessions.get(browser.sessionId())));
            assertThat(bindingCount(subject)).isZero();
            assertThat(bindingCount(thirdSubject)).isZero();
            assertThat(counts()).isEqualTo(before.plusAccount());
        }
        finally {
            oldGate.close();
            newGate.close();
            finishWorkers(executor, old, current);
        }
    }

    @SafeVarargs
    private static void finishWorkers(ExecutorService executor, Future<HttpResponse<String>>... futures) throws Exception {
        try {
            for (Future<HttpResponse<String>> future : futures) {
                if (future != null) future.get(10, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String nextSubject() {
        String next = "auth0|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS.contains(next)).isTrue();
        return next;
    }

    private void assertRejectedWithoutAccount(Scenario scenario) throws Exception {
        assertRejectedWithoutAccount(scenario, before);
    }

    private void assertRejectedWithoutAccount(Scenario scenario, Counts expected) throws Exception {
        provider.scenario(scenario);
        Browser browser = browser();
        assertRedirect(browser.get(begin(browser)), origin + "/login?auth=failed");
        assertThat(counts()).isEqualTo(expected);
        assertJsonError(browser.get("/api/me"), 401);
        HttpSession session = probe().sessions.get(browser.sessionId());
        assertThat(hasAuthentication(session)).isFalse();
        assertThat(hasAuthorizedClient(session)).isFalse();
    }

    private void assertNoAccountOrAuthentication(Browser browser) throws Exception {
        assertThat(counts()).isEqualTo(before);
        assertJsonError(browser.get("/api/me"), 401);
        assertThat(hasAuthentication(probe().sessions.get(browser.sessionId()))).isFalse();
    }

    private Browser browser() {
        Browser browser = new Browser();
        browsers.add(browser);
        return browser;
    }

    private static String csrf(Browser browser) throws Exception {
        HttpResponse<String> response = browser.get("/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return (String) json(response).get("token");
    }

    private static HttpResponse<String> loginPost(Browser browser, String csrf, Map<String, String> extra)
            throws Exception {
        var headers = new LinkedHashMap<>(extra);
        headers.put("Origin", origin);
        return browser.post(URI.create(origin + "/auth/login?returnTo=https%3A%2F%2Fattacker.test"),
                "_csrf=" + encode(csrf), headers);
    }

    private static URI begin(Browser browser) throws Exception {
        return authorize(browser, loginPost(browser, csrf(browser), Map.of()));
    }

    private static URI authorize(Browser browser, HttpResponse<String> initiation) throws Exception {
        assertRedirect(initiation, origin + "/oauth2/authorization/auth0");
        HttpResponse<String> authorization = browser.get(URI.create(location(initiation)));
        assertThat(authorization.statusCode()).isIn(302, 303);
        URI providerLocation = URI.create(location(authorization));
        assertThat(providerLocation.getScheme() + "://" + providerLocation.getRawAuthority())
                .isEqualTo(provider.issuer().getScheme() + "://" + provider.issuer().getRawAuthority());
        assertThat(providerLocation.getPath()).isEqualTo("/authorize");
        HttpResponse<String> issuedCode = browser.get(providerLocation);
        assertThat(issuedCode.statusCode()).isEqualTo(302);
        URI callback = URI.create(location(issuedCode));
        assertThat(callback.toString()).startsWith(origin + "/login/oauth2/code/auth0?");
        return callback;
    }

    private static void complete(Browser browser) throws Exception {
        // No Origin header on this top-level GET callback: state/nonce protect it.
        assertRedirect(browser.get(begin(browser)), origin + "/account");
    }

    private static HttpResponse<String> logout(Browser browser) throws Exception {
        HttpResponse<String> response = browser.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", csrf(browser)));
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        return response;
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElseThrow();
    }

    private static void assertRedirect(HttpResponse<String> response, String expected) {
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().allValues("Location")).containsExactly(expected);
        assertThat(location(response)).doesNotContain(";jsessionid", "error_description", "client_secret", "returnTo");
    }

    private static Map<String, Object> json(HttpResponse<String> response) throws Exception {
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        return JSONObjectUtils.parse(response.body());
    }

    private static void assertJsonError(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(json(response)).doesNotContainKeys("id", "status", "subject", "email", "stackTrace");
        assertNoPrivateProtocolData(response);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    private static void assertNoPrivateProtocolData(HttpResponse<String> response) {
        assertThat(response.body()).doesNotContain(CLIENT_SECRET, "access_token", "id_token", "refresh_token",
                "JSESSIONID", "auth0|", "SQLSTATE", "SQLException", "org.springframework", "<html", "<form");
    }

    private UUID bindingId() {
        return bindingId(subject);
    }

    private UUID bindingId(String identitySubject) {
        return jdbc.queryForObject("SELECT user_id FROM user_identity_bindings WHERE issuer = ? AND subject = ?",
                UUID.class, provider.issuer().toString(), identitySubject);
    }

    private long bindingCount(String identitySubject) {
        return jdbc.queryForObject("SELECT count(*) FROM user_identity_bindings WHERE issuer = ? AND subject = ?",
                Long.class, provider.issuer().toString(), identitySubject);
    }

    private Counts counts() {
        return new Counts(count("users"), count("user_profiles"), count("user_identity_bindings"), count("workspaces"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private record Counts(long users, long profiles, long bindings, long workspaces) {
        Counts plusAccount() { return new Counts(users + 1, profiles + 1, bindings + 1, workspaces); }
    }

    private static SessionProbe probe() { return application.getBean(SessionProbe.class); }

    private static boolean hasAuthentication(HttpSession session) {
        return session != null && Collections.list(session.getAttributeNames()).stream()
                .map(session::getAttribute).anyMatch(value -> value instanceof SecurityContext context
                        && context.getAuthentication() != null && context.getAuthentication().isAuthenticated());
    }

    private static boolean hasAuthorizedClient(HttpSession session) {
        return session != null && Collections.list(session.getAttributeNames()).stream()
                .map(session::getAttribute).anyMatch(value -> value instanceof OAuth2AuthorizedClient
                        || value instanceof Map<?, ?> values
                        && values.values().stream().anyMatch(OAuth2AuthorizedClient.class::isInstance));
    }

    private static OAuth2AuthorizedClient authorizedClient(HttpSession session) {
        assertThat(session).isNotNull();
        return Collections.list(session.getAttributeNames()).stream().map(session::getAttribute)
                .flatMap(value -> value instanceof Map<?, ?> values ? values.values().stream() : Stream.of(value))
                .filter(OAuth2AuthorizedClient.class::isInstance).map(OAuth2AuthorizedClient.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("No session authorized client"));
    }

    private static void assertSessionIdentity(Browser browser, UUID expected, OAuth2AuthorizedClient expectedClient)
            throws Exception {
        HttpSession session = probe().sessions.get(browser.sessionId());
        assertThat(session).isNotNull();
        SecurityContext context = Collections.list(session.getAttributeNames()).stream().map(session::getAttribute)
                .filter(SecurityContext.class::isInstance).map(SecurityContext.class::cast).findFirst().orElseThrow();
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(CreastrixPrincipal.class);
        assertThat(((CreastrixPrincipal) context.getAuthentication().getPrincipal()).userId()).isEqualTo(expected);
        OAuth2AuthorizedClient actual = authorizedClient(session);
        assertThat(actual.getPrincipalName().equals(expected.toString())).isTrue();
        assertThat(actual == expectedClient).as("The authenticated session retains its exact authorized client").isTrue();
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(expected.toString());
    }

    private static String rawRequest(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> get(String path) throws Exception { return get(path, Map.of()); }
        HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
            return request("GET", URI.create(origin + path), "", headers);
        }
        HttpResponse<String> get(URI uri) throws Exception { return request("GET", uri, "", Map.of()); }
        HttpResponse<String> post(URI uri, String body, Map<String, String> headers) throws Exception {
            return request("POST", uri, body, headers);
        }
        HttpResponse<String> request(String method, URI uri, String body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25));
            headers.forEach(request::header);
            if (method.equals("POST")) {
                request.header("Content-Type", "application/x-www-form-urlencoded");
            }
            return client.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        String sessionId() {
            // Same-name cookies at another Path are unrelated to the application's root session.
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID")
                            && "/".equals(cookie.getPath()))
                    .map(java.net.HttpCookie::getValue).findFirst().orElse("");
        }
        @Override public void close() { client.close(); }
    }

    static final class MutableClock extends Clock {
        private volatile Duration offset = Duration.ZERO;
        void reset() { offset = Duration.ZERO; }
        void advance(Duration duration) { offset = offset.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    static final class SessionProbe implements HttpSessionListener, HttpSessionIdListener {
        final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();
        @Override public void sessionCreated(HttpSessionEvent event) {
            sessions.put(event.getSession().getId(), event.getSession());
        }
        @Override public void sessionDestroyed(HttpSessionEvent event) {
            sessions.remove(event.getSession().getId());
        }
        @Override public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
            sessions.remove(oldSessionId);
            sessions.put(event.getSession().getId(), event.getSession());
            RotationPause pause = FAULTS.rotationPause;
            if (pause != null) pause.afterIdChanged(event.getSession());
        }
    }

    static final class RotationPause {
        static final List<String> UNRELATED_COOKIES = List.of(
                "r2-unrelated=retained; Path=/; HttpOnly; SameSite=Lax",
                "JSESSIONID=other-path; Path=/r2-cookie-control; HttpOnly; SameSite=Lax",
                "JSESSIONID=other-domain; Path=/; Domain=example.test; HttpOnly; SameSite=Lax");
        final HttpSession original;
        final boolean unrelatedCookies;
        final AtomicReference<String> rotatedId = new AtomicReference<>();
        final AtomicInteger listenerCalls = new AtomicInteger();
        final AtomicInteger nativeRotationReturned = new AtomicInteger();
        final AtomicBoolean actualPendingRotationCookie = new AtomicBoolean();
        final AtomicBoolean uncommittedAfterRotation = new AtomicBoolean();
        final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();
        final CountDownLatch rotated = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        RotationPause(HttpSession original, boolean unrelatedCookies) {
            this.original = original;
            this.unrelatedCookies = unrelatedCookies;
        }

        void afterIdChanged(HttpSession changed) {
            if (changed != original) return;
            listenerCalls.incrementAndGet();
            rotatedId.set(changed.getId());
            rotated.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    fixtureFailure.compareAndSet(null, new AssertionError("Test rotation barrier expired"));
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fixtureFailure.compareAndSet(null, interrupted);
            }
        }

        void afterNativeRotation(HttpServletResponse response, String changedId) {
            nativeRotationReturned.incrementAndGet();
            uncommittedAfterRotation.set(!response.isCommitted());
            actualPendingRotationCookie.set(changedId.equals(rotatedId.get())
                    && response.getHeaders("Set-Cookie").stream().anyMatch(header ->
                        isHostOnlyRootSessionCookie(header) && HttpCookie.parse(header).getFirst().getValue().equals(changedId)));
            // Add only unrelated controls AFTER Tomcat's real cookie write; do not inject its stale cookie.
            if (unrelatedCookies) UNRELATED_COOKIES.forEach(header -> response.addHeader("Set-Cookie", header));
        }
    }

    static final class Faults {
        final AtomicBoolean failAcquisition = new AtomicBoolean();
        final AtomicBoolean loseNextCommitAcknowledgement = new AtomicBoolean();
        final AtomicInteger committedThenFailed = new AtomicInteger();
        volatile BindingRace bindingRace;
        volatile CommitPause commitPause;
        volatile RotationPause rotationPause;
        void reset() {
            failAcquisition.set(false);
            loseNextCommitAcknowledgement.set(false);
            committedThenFailed.set(0);
        }
    }

    static final class CommitPause {
        final AtomicReference<Connection> bindingConnection = new AtomicReference<>();
        final AtomicInteger observedCommits = new AtomicInteger();
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void afterCommit(Connection connection) throws SQLException {
            if (connection != bindingConnection.get()) return;
            observedCommits.incrementAndGet();
            committed.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new SQLException("Test post-commit barrier expired");
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Test post-commit barrier interrupted", interrupted);
            }
        }
    }

    static final class BindingRace {
        final boolean commitFirst;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<Integer> firstPid = new AtomicReference<>();
        final AtomicReference<Integer> secondPid = new AtomicReference<>();
        final AtomicReference<UUID> firstUser = new AtomicReference<>();
        final AtomicReference<UUID> secondUser = new AtomicReference<>();
        final AtomicInteger firstCommits = new AtomicInteger();
        final AtomicInteger firstRollbacks = new AtomicInteger();
        final CountDownLatch firstInserted = new CountDownLatch(1);
        final CountDownLatch secondAttempted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        volatile Connection firstConnection;

        BindingRace(boolean commitFirst) { this.commitFirst = commitFirst; }

        int beforeInsert(Connection connection, UUID user) throws SQLException {
            int attempt = attempts.incrementAndGet();
            int pid;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(1);
                try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                    if (!result.next()) {
                        throw new SQLException("Test observer did not receive backend PID");
                    }
                    pid = result.getInt(1);
                }
            }
            if (attempt == 1) {
                firstConnection = connection;
                firstPid.set(pid);
                firstUser.set(user);
            }
            else if (attempt == 2) {
                secondPid.set(pid);
                secondUser.set(user);
                secondAttempted.countDown();
            }
            else {
                throw new SQLException("Unexpected extra binding INSERT in two-flow proof");
            }
            return attempt;
        }

        void afterInsert(int attempt) throws SQLException {
            if (attempt != 1) {
                return;
            }
            // The delegate statement has inserted the binding; application commit has not run.
            firstInserted.countDown();
            try {
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("Test binding barrier expired");
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Test binding barrier interrupted", interrupted);
            }
            if (!commitFirst) {
                throw new SQLException("Test-only failure after real binding INSERT", "XX000");
            }
        }

        void completed(Connection connection, String operation) {
            if (connection == firstConnection) {
                if (operation.equals("commit")) {
                    firstCommits.incrementAndGet();
                }
                else if (operation.equals("rollback")) {
                    firstRollbacks.incrementAndGet();
                }
            }
        }
    }

    static final class FaultDataSource extends DelegatingDataSource implements AutoCloseable {
        FaultDataSource(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException {
            if (FAULTS.failAcquisition.get()) {
                throw new SQLException("Test-only connection acquisition unavailable", "08006");
            }
            return wrap(super.getConnection());
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            if (FAULTS.failAcquisition.get()) {
                throw new SQLException("Test-only connection acquisition unavailable", "08006");
            }
            return wrap(super.getConnection(username, password));
        }
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            BindingRace race = FAULTS.bindingRace;
                            CommitPause commitPause = FAULTS.commitPause;
                            if (race != null) {
                                race.completed(connection, method.getName());
                            }
                            if (commitPause != null && method.getName().equals("commit")) {
                                // The real JDBC commit has returned; observer queries see its durable rows.
                                commitPause.afterCommit(connection);
                            }
                            if ((race != null || commitPause != null) && method.getName().equals("prepareStatement")
                                    && args != null && args[0] instanceof String sql
                                    && sql.startsWith("INSERT INTO user_identity_bindings ")) {
                                return observeBindingInsert(connection, (PreparedStatement) result, race, commitPause);
                            }
                            if (method.getName().equals("commit")
                                    && FAULTS.loseNextCommitAcknowledgement.compareAndSet(true, false)) {
                                // The delegate commit completed physically BEFORE acknowledgement is lost.
                                FAULTS.committedThenFailed.incrementAndGet();
                                throw new SQLException("Test-only lost commit acknowledgement", "08006");
                            }
                            return result;
                        }
                        catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        private PreparedStatement observeBindingInsert(Connection connection, PreparedStatement statement,
                BindingRace race, CommitPause commitPause) {
            AtomicReference<UUID> user = new AtomicReference<>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("setObject") && args != null
                                    && Integer.valueOf(3).equals(args[0]) && args[1] instanceof UUID id) {
                                user.set(id);
                            }
                            boolean executing = method.getName().equals("executeUpdate");
                            if (executing && commitPause != null) {
                                commitPause.bindingConnection.compareAndSet(null, connection);
                            }
                            int attempt = executing && race != null
                                    ? race.beforeInsert(connection, user.get()) : 0;
                            Object result = method.invoke(statement, args);
                            if (attempt != 0) {
                                race.afterInsert(attempt);
                            }
                            return result;
                        }
                        catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestWiring {
        @Bean @Primary AuthenticationProperties testAuthenticationProperties() {
            return new AuthenticationProperties(provider.issuer().toString(), CLIENT_ID, CLIENT_SECRET,
                    origin, admitted, false);
        }
        @Bean @Primary Clock testClock() { return CLOCK; }
        @Bean @Primary ClientRegistrationRepository testClientRegistrations() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("auth0")
                    .clientId(CLIENT_ID).clientSecret(CLIENT_SECRET)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(origin + "/login/oauth2/code/auth0")
                    .scope("openid", "profile", "email")
                    .authorizationUri(provider.endpoint("authorize")).tokenUri(provider.endpoint("token"))
                    .jwkSetUri(provider.endpoint("jwks")).userInfoUri(provider.endpoint("userinfo"))
                    .issuerUri(provider.issuer().toString()).userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName("Test-only local OIDC").build();
            return new InMemoryClientRegistrationRepository(registration);
        }
        @Bean SessionProbe sessionProbe() { return new SessionProbe(); }
        @Bean ServletListenerRegistrationBean<SessionProbe> sessionProbeRegistration(SessionProbe probe) {
            return new ServletListenerRegistrationBean<>(probe);
        }
        @Bean FilterRegistrationBean<Filter> testNativeRotationObserver() {
            Filter observer = (request, response, chain) -> {
                RotationPause pause = FAULTS.rotationPause;
                if (pause == null || !(request instanceof HttpServletRequest http)
                        || !(response instanceof HttpServletResponse output)
                        || !"/login/oauth2/code/auth0".equals(http.getRequestURI())
                        || http.getSession(false) != pause.original) {
                    chain.doFilter(request, response);
                    return;
                }
                chain.doFilter(new HttpServletRequestWrapper(http) {
                    @Override public String changeSessionId() {
                        String changedId = super.changeSessionId();
                        pause.afterNativeRotation(output, changedId);
                        return changedId;
                    }
                }, response);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(observer);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
        @Bean static BeanPostProcessor testDataSourceFaultBoundary() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource source && !(bean instanceof FaultDataSource)
                            ? new FaultDataSource(source) : bean;
                }
            };
        }
    }
}
