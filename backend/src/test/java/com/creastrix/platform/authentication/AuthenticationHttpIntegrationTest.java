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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.creastrix.platform.CreastrixApplication;
import com.creastrix.platform.support.OidcTestProvider;
import com.creastrix.platform.support.OidcTestProvider.Scenario;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.application.AuthenticatedUserService;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import com.creastrix.platform.user.domain.UserStatus;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.nimbusds.jose.util.JSONObjectUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Timeout;
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
import org.testcontainers.DockerClientFactory;
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
 * browser SameSite/HttpOnly enforcement, or an actual provider walkthrough (F/P).
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
class AuthenticationHttpIntegrationTest {

    private static final String CLIENT_ID = "backend-test-client";
    private static final String CLIENT_SECRET = "test-only-confidential-client-secret";
    private static final String OWNED_CONTAINER_LABEL = "creastrix.feasibility";
    private static final String OWNED_CONTAINER_VALUE = "auth-cookie-forward-port-s003";
    private static final MutableClock CLOCK = new MutableClock();
    private static final Faults FAULTS = new Faults();
    private static final AtomicReference<RuntimeException> ERROR_FAULT = new AtomicReference<>();
    private static final List<Map<String, Object>> ERROR_DISPATCHES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final ConfirmationStub CONFIRMATION = new ConfirmationStub();
    private static final AtomicLong CONFIRMATION_TIME = new AtomicLong();
    private static final AtomicInteger NEXT_SUBJECT = new AtomicInteger();
    private static final String MISSING_OWNER = "synthetic|admitted-without-current-binding";
    private static final Set<String> PILOT_SUBJECTS = Stream.concat(IntStream.range(0, 500)
            .mapToObj(number -> "synthetic|pilot-" + number), Stream.of(MISSING_OWNER)).collect(Collectors.toUnmodifiableSet());
    private static final Map<String, UUID> FIXTURE_OWNERS = new ConcurrentHashMap<>();
    private static int port;
    private static String origin;
    private static OidcTestProvider provider;
    private static ConfigurableApplicationContext application;
    private static Set<String> admitted = PILOT_SUBJECTS;
    private static final AuthenticationProperties.Provider providerMode = AuthenticationProperties.Provider.COGNITO;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withLabel(OWNED_CONTAINER_LABEL, OWNED_CONTAINER_VALUE)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withNetworkMode("bridge")
                    .withPublishAllPorts(false)
                    .withPortBindings(new Ports(ExposedPort.tcp(5432), Ports.Binding.bindIp("127.0.0.1"))));

    private final List<Browser> browsers = new ArrayList<>();
    private String subject;
    private JdbcTemplate jdbc;
    private Counts before;

    @BeforeAll
    static void startOwnedServers() throws Exception {
        verifyOwnedContainerBindings();
        try (ServerSocket reservation = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        // A bind race fails startup rather than selecting another origin silently.
        origin = "http://localhost:" + port;
        provider = new OidcTestProvider(CLIENT_ID, CLIENT_SECRET,
                origin + "/login/oauth2/code/cognito", Clock.systemUTC());
        try {
            startBackend();
            provisionExistingOwnerFixtures();
            verifyOwnedListenerBindings();
            System.out.println("COOKIE_CONTEXT_RUNTIME postgres_version=" + application.getBean(JdbcTemplate.class)
                    .queryForObject("SHOW server_version", String.class));
        }
        catch (Throwable failedStartup) {
            provider.close();
            throw failedStartup;
        }
    }

    private static void startBackend() {
        application = new SpringApplicationBuilder(TestWiring.class, ErrorTestWiring.class, CreastrixApplication.class)
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

    private static void provisionExistingOwnerFixtures() {
        var users = application.getBean(AuthenticatedUserService.class);
        JdbcTemplate setup = application.getBean(JdbcTemplate.class);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(application.getBean(DataSource.class)));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        for (int number = 0; number < 500; number++) {
            String fixtureSubject = "synthetic|pilot-" + number;
            Identity old = new Identity("https://retained.synthetic.example/", UUID.randomUUID().toString());
            // Setup uses the unchanged service/DB creation contract, not a permissive HTTP adapter.
            var owner = users.resolve(old, identity -> true);
            transaction.executeWithoutResult(status -> {
                setup.execute("SET LOCAL lock_timeout='5s'");
                setup.execute("SET LOCAL statement_timeout='10s'");
                setup.execute("SET LOCAL transaction_timeout='15s'");
                assertThat(setup.queryForObject("SELECT public.migrate_owner_identity(?,?,?,?,?,?,?)", String.class,
                        UUID.randomUUID(), owner.id(), 1L, old.issuer(), old.subject(),
                        provider.issuer().toString(), fixtureSubject)).isEqualTo("APPLIED");
            });
            FIXTURE_OWNERS.put(fixtureSubject, owner.id());
        }
        assertThat(FIXTURE_OWNERS).hasSize(500).doesNotContainKey(MISSING_OWNER);
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
    void freshFlow() throws Exception {
        CLOCK.reset();
        ERROR_FAULT.set(null);
        ERROR_DISPATCHES.clear();
        FAULTS.reset();
        CONFIRMATION.reset();
        // Advance only the provider-recovery test ticker. No limiter reset or live record eviction.
        CONFIRMATION_TIME.addAndGet(CognitoEmailConfirmationService.TARGET_TTL.toNanos() + 1);
        // One Cognito-only context: fresh cookies/claims, but pre-existing immutable owner identities.
        provider.reset();
        subject = "synthetic|pilot-" + NEXT_SUBJECT.getAndIncrement();
        provider.scenario(Scenario.verified(subject));
        jdbc = application.getBean(JdbcTemplate.class);
        before = counts();
    }

    private static void verifyOwnedContainerBindings() {
        // This portable fixture gate covers this exact PostgreSQL container only.
        // Ryuk and other test classes' containers are not inspected or reconfigured.
        var inspected = DockerClientFactory.instance().client()
                .inspectContainerCmd(POSTGRES.getContainerId()).exec();
        assertThat(inspected.getId()).isEqualTo(POSTGRES.getContainerId());
        assertThat(inspected.getState().getRunning()).isTrue();
        assertThat(inspected.getConfig().getLabels()).containsEntry(OWNED_CONTAINER_LABEL, OWNED_CONTAINER_VALUE);
        assertThat(inspected.getHostConfig().getNetworkMode()).isEqualTo("bridge");
        assertThat(inspected.getHostConfig().getPublishAllPorts()).isFalse();
        ExposedPort postgresPort = ExposedPort.tcp(5432);
        var requested = inspected.getHostConfig().getPortBindings().getBindings();
        assertThat(requested).as("Only the owned PostgreSQL port is requested").containsOnlyKeys(postgresPort);
        assertThat(requested.get(postgresPort)).hasSize(1);
        assertThat(requested.get(postgresPort)[0].getHostIp()).isEqualTo("127.0.0.1");
        var actual = inspected.getNetworkSettings().getPorts().getBindings();
        assertThat(actual).as("Only the owned PostgreSQL port is published").containsOnlyKeys(postgresPort);
        assertThat(actual.get(postgresPort)).hasSize(1);
        assertThat(actual.get(postgresPort)[0].getHostIp()).isEqualTo("127.0.0.1");
        assertThat(POSTGRES.getMappedPort(5432)).isBetween(1, 65535);
        assertThat(actual.get(postgresPort)[0].getHostPortSpec())
                .isEqualTo(Integer.toString(POSTGRES.getMappedPort(5432)));
        System.out.println("COOKIE_CONTEXT_RUNTIME owned_postgres_loopback=true ryuk_binding=NOT_VERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "authenticated", "disabled"})
    @Timeout(90)
    void unexpectedContainerErrorPreservesOriginalStatus(String mode) throws Exception {
        if ("disabled".equals(mode)) {
            try (ConfigurableApplicationContext disabled = disabledErrorBackend();
                    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var web = (org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext) disabled;
                int disabledPort = web.getWebServer().getPort();
                for (String fault : List.of("throw", "send")) {
                    ERROR_DISPATCHES.clear();
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                    + disabledPort + "/actuator/health?trace=true"))
                            .header("X-Error-Probe", fault).header("Accept", "text/html").GET()
                            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                    assertContainerError(response, fault, "/actuator/health");
                }
            }
        }
        else {
            Browser browser = browser();
            String path = "/actuator/health";
            if ("authenticated".equals(mode)) {
                complete(browser);
                path = "/api/me";
            }
            String sid = browser.sessionId();
            String q = browser.contextId();
            var session = probe().sessions.get(sid);
            var attributes = session == null ? Map.of() : sessionAttributes(session);
            for (String fault : List.of("throw", "send")) {
                ERROR_DISPATCHES.clear();
                assertContainerError(browser.get(path + "?trace=true",
                        Map.of("X-Error-Probe", fault, "Accept", "text/html")), fault, path);
                assertThat(browser.sessionId()).isEqualTo(sid);
                assertThat(browser.contextId()).isEqualTo(q);
                if (session != null) assertThat(sessionAttributes(session)).isEqualTo(attributes);
            }
            if (session == null) assertThat(browser.get("/actuator/health").statusCode()).isEqualTo(200);
            else assertAccount(browser.get("/api/me"), bindingId());
            assertThat(provider.authorizationRequests()).hasSize(session == null ? 0 : 1);
            assertThat(provider.tokenRequests()).hasSize(session == null ? 0 : 1);
        }
        assertThat(counts()).isEqualTo(before);
    }

    private static ConfigurableApplicationContext disabledErrorBackend() {
        return new SpringApplicationBuilder(ErrorTestWiring.class, CreastrixApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.hikari.connection-timeout=1000", "--creastrix.auth.enabled=false",
                        "--spring.main.banner-mode=off");
    }

    private static void assertContainerError(HttpResponse<String> response, String fault, String original) throws Exception {
        int status = "send".equals(fault) ? 422 : 500;
        assertThat(ERROR_DISPATCHES).containsExactly(Map.of("path", "/error", "original", original,
                "status", status, "same_fault", !"send".equals(fault)));
        assertJsonError(response, status);
        assertThat(response.body()).isEqualTo("{\"error\":\"request_not_completed\"}");
        assertThat(response.body()).doesNotContain("PRIVATE_ERROR_SENTINEL");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("X-Unrelated")).contains("retained");
        System.out.printf("OPAQUE_ERROR_PROOF origin=%s kind=%s dispatch=ERROR wire_status=%d%n", original, fault, status);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mvcControllerErrorsAreOpaqueWithoutSessionOrIdentityMutation(boolean authenticated) throws Exception {
        Browser browser = browser();
        if (authenticated) complete(browser);
        var sid = browser.sessionId();
        var q = browser.contextId();
        var session = probe().sessions.get(sid);
        var attributes = session == null ? Map.of() : sessionAttributes(session);
        var durable = confirmationDurableSnapshot();
        for (String fault : List.of("throw", "send")) {
            ERROR_DISPATCHES.clear();
            assertContainerError(browser.get("/auth/csrf?trace=true", Map.of("X-Error-Probe", "controller-" + fault,
                    "Accept", "text/html")), fault, "/auth/csrf");
            assertThat(browser.sessionId()).isEqualTo(sid);
            assertThat(browser.contextId()).isEqualTo(q);
            if (session != null) assertThat(sessionAttributes(session)).isEqualTo(attributes);
        }
        assertThat(confirmationDurableSnapshot()).isEqualTo(durable);
        assertThat(provider.authorizationRequests()).hasSize(authenticated ? 1 : 0);
        assertThat(provider.tokenRequests()).hasSize(authenticated ? 1 : 0);
        assertThat(CONFIRMATION.calls).hasValue(0);
        if (authenticated) assertAccount(browser.get("/api/me"), bindingId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"writer-utf8", "writer-utf16", "stream"})
    void nativePartialResponseChannelsRetainCharsetHeadersAndCookies(String channel) throws Exception {
        // Direct renderer invocation isolates retained native response-channel state;
        // genuine container ERROR routing/authorization has separate tests above.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/auth/csrf?trace=true"))
                    .header("X-Error-Probe", "render").header("X-Error-Channel", channel)
                    .header("Accept", "text/html").GET().timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            var charset = channel.endsWith("utf16") ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(new String(response.body(), charset)).isEqualTo("{\"error\":\"request_not_completed\"}");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("application/json;charset=" + charset.name());
            assertThat(response.headers().firstValue("X-Unrelated")).contains("retained");
            assertThat(response.headers().allValues("Set-Cookie")).containsExactly("unrelated=retained; Path=/");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            response.headers().firstValue("Content-Length").ifPresent(length ->
                    assertThat(Integer.parseInt(length)).isEqualTo(response.body().length));
            assertThat(ERROR_DISPATCHES).isEmpty();
        }
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(provider.tokenRequests()).isEmpty();
        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "authenticated", "disabled"})
    void errorDispatchCannotReachOrdinaryPermittedOrPrivateDestinations(String mode) throws Exception {
        ConfigurableApplicationContext disabled = "disabled".equals(mode) ? disabledErrorBackend() : null;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            Browser browser = browser();
            if ("authenticated".equals(mode)) complete(browser);
            String base = disabled == null ? origin : "http://127.0.0.1:"
                    + ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext) disabled)
                        .getWebServer().getPort();
            for (String destination : List.of("/actuator/health", "/api/me", "/unknown")) {
                ERROR_DISPATCHES.clear();
                Map<String, String> headers = Map.of("X-Error-Probe", "throw", "X-Error-Destination", destination);
                HttpResponse<String> response;
                if (disabled == null) response = browser.get("/actuator/health", headers);
                else response = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                        .header("X-Error-Probe", "throw").header("X-Error-Destination", destination).GET()
                        .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(ERROR_DISPATCHES).containsExactly(Map.of("path", destination, "original", "/actuator/health",
                        "status", 500, "same_fault", true));
                assertJsonError(response, "authenticated".equals(mode) ? 403 : 401);
                assertThat(response.body()).isEqualTo("{\"error\":\"request_not_completed\"}");
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            }
            ERROR_DISPATCHES.clear();
            if (disabled == null) {
                for (String path : List.of("/error", "/error/", "/error/extra", "/%65rror", "/ERROR")) {
                    assertJsonError(browser.get(path), "authenticated".equals(mode) ? 403 : 401);
                }
                assertJsonError(browser.get("/actuator/health", Map.of("X-Error-Probe", "forward")),
                        "authenticated".equals(mode) ? 403 : 401);
                if ("authenticated".equals(mode)) assertAccount(browser.get("/api/me"), bindingId());
            }
            else {
                for (String path : List.of("/error", "/error/", "/error/extra", "/%65rror", "/ERROR")) {
                    assertJsonError(client.send(HttpRequest.newBuilder(URI.create(base + path)).GET()
                            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()), 401);
                }
                assertJsonError(client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                        .header("X-Error-Probe", "forward").GET().timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString()), 401);
            }
            assertThat(ERROR_DISPATCHES).isEmpty();
        }
        finally { if (disabled != null) disabled.close(); }
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void absoluteExpiredIdleValidPrincipalKeepsEntryConflictUntilPrivateValidation() throws Exception {
        Browser browser = browser();
        complete(browser);
        String token = csrf(browser);
        String sid = browser.sessionId();
        String q = browser.contextId();
        HttpSession session = probe().sessions.get(sid);
        var attributes = sessionAttributes(session);
        var durable = confirmationDurableSnapshot();
        // Age the injected absolute clock only, not Tomcat's idle clock or this session's TTL.
        CLOCK.advance(AuthenticationProperties.ABSOLUTE_LIFETIME.plusSeconds(1));
        assertThat(session.getMaxInactiveInterval()).isEqualTo(1800);
        assertJsonError(loginPost(browser, token, Map.of()), 409);
        assertJsonError(browser.get("/oauth2/authorization/cognito"), 409);
        for (String path : List.of("/%6fauth2/authorization/cognito", "/oauth2/%61uthorization/cognito",
                "/oauth2/authorization/%63ognito")) {
            assertJsonError(browser.get(path), 409);
        }
        assertThat(probe().sessions.get(sid)).isSameAs(session);
        assertThat(sessionAttributes(session)).isEqualTo(attributes).doesNotContainKey(AuthenticationConfiguration.LOGIN_INTENT);
        assertThat(browser.sessionId()).isEqualTo(sid);
        assertThat(browser.contextId()).isEqualTo(q);
        assertThat(provider.authorizationRequests()).hasSize(1);
        assertThat(provider.tokenRequests()).hasSize(1);
        assertThat(confirmationDurableSnapshot()).isEqualTo(durable);
        assertJsonError(browser.get("/api/me"), 401);
        assertThat(probe().sessions).doesNotContainKey(sid);
        assertThat(confirmationDurableSnapshot()).isEqualTo(durable);
        assertThat(CONFIRMATION.calls).hasValue(0);
    }

    static final class HealthError extends RuntimeException { }
    static final class PrivateError extends RuntimeException { }
    static final class UnknownError extends RuntimeException { }

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.web.bind.annotation.RestController
    static class ErrorTestWiring {
        @org.springframework.web.bind.annotation.GetMapping(value = "/auth/csrf", headers = "X-Error-Probe=render")
        void nativeResponseControl(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setHeader("X-Unrelated", "retained");
            var cookie = new jakarta.servlet.http.Cookie("unrelated", "retained");
            cookie.setPath("/");
            response.addCookie(cookie);
            response.setContentLength(4096);
            String channel = request.getHeader("X-Error-Channel");
            if ("stream".equals(channel)) {
                response.getOutputStream().write("PRIVATE_ERROR_SENTINEL_PARTIAL".getBytes(StandardCharsets.UTF_8));
            }
            else {
                response.setCharacterEncoding("writer-utf16".equals(channel) ? "UTF-16" : "UTF-8");
                response.getWriter().write("PRIVATE_ERROR_SENTINEL_PARTIAL");
            }
            assertThat(response.isCommitted()).isFalse();
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 422);
            new OpaqueErrorController().error(request, response);
        }

        @Bean org.springframework.boot.web.error.ErrorPageRegistrar deniedErrorDestinations() {
            return registry -> registry.addErrorPages(
                    new org.springframework.boot.web.error.ErrorPage(HealthError.class, "/actuator/health"),
                    new org.springframework.boot.web.error.ErrorPage(PrivateError.class, "/api/me"),
                    new org.springframework.boot.web.error.ErrorPage(UnknownError.class, "/unknown"));
        }

        @org.springframework.web.bind.annotation.GetMapping(value = "/auth/csrf", headers = "X-Error-Probe=controller-throw")
        void controllerThrow(HttpServletResponse response) throws IOException {
            response.setHeader("X-Unrelated", "retained");
            response.getWriter().write("PRIVATE_ERROR_SENTINEL_PARTIAL");
            var failure = new IllegalStateException("PRIVATE_ERROR_SENTINEL_EXCEPTION");
            ERROR_FAULT.set(failure);
            throw failure;
        }

        @org.springframework.web.bind.annotation.GetMapping(value = "/auth/csrf", headers = "X-Error-Probe=controller-send")
        void controllerSend(HttpServletResponse response) throws IOException {
            response.setHeader("X-Unrelated", "retained");
            response.getWriter().write("PRIVATE_ERROR_SENTINEL_PARTIAL");
            response.sendError(422, "PRIVATE_ERROR_SENTINEL_MESSAGE");
        }

        @Bean FilterRegistrationBean<Filter> errorDispatchObserver() {
            Filter filter = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                if (http.getDispatcherType() == DispatcherType.ERROR) {
                    Throwable current = (Throwable) http.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                    boolean same = false;
                    for (int i = 0; current != null && i < 16; i++, current = current.getCause()) {
                        if (current == ERROR_FAULT.get()) same = true;
                    }
                    ERROR_DISPATCHES.add(Map.of("path", http.getRequestURI(),
                            "original", http.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                            "status", http.getAttribute(RequestDispatcher.ERROR_STATUS_CODE), "same_fault", same));
                }
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
            return registration;
        }

        @Bean FilterRegistrationBean<Filter> unexpectedErrorFault() {
            Filter filter = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                var output = (HttpServletResponse) response;
                String fault = http.getHeader("X-Error-Probe");
                if ("forward".equals(fault) && "/actuator/health".equals(http.getRequestURI())) {
                    http.getRequestDispatcher("/error").forward(request, response);
                    return;
                }
                if (Set.of("/actuator/health", "/api/me").contains(http.getRequestURI())
                        && ("throw".equals(fault) || "send".equals(fault))) {
                    output.setHeader("X-Unrelated", "retained");
                    output.getWriter().write("PRIVATE_ERROR_SENTINEL_PARTIAL");
                    if ("send".equals(fault)) output.sendError(422, "PRIVATE_ERROR_SENTINEL_MESSAGE");
                    else {
                        var failure = switch (http.getHeader("X-Error-Destination") == null ? "" : http.getHeader("X-Error-Destination")) {
                            case "/actuator/health" -> new HealthError();
                            case "/api/me" -> new PrivateError();
                            case "/unknown" -> new UnknownError();
                            default -> new IllegalStateException("PRIVATE_ERROR_SENTINEL_EXCEPTION");
                        };
                        ERROR_FAULT.set(failure);
                        throw failure;
                    }
                    return;
                }
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            return registration;
        }
    }

    private static void verifyOwnedListenerBindings() throws Exception {
        var web = (org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext) application;
        var tomcat = (org.springframework.boot.tomcat.TomcatWebServer) web.getWebServer();
        assertThat(application.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(tomcat.getPort()).isEqualTo(port);
        assertThat(tomcat.getTomcat().getService().findConnectors()).isNotEmpty();
        for (var connector : tomcat.getTomcat().getService().findConnectors()) {
            Object address = connector.getProperty("address");
            assertThat(address).as("Each actual Tomcat connector has an explicit binding").isNotNull();
            InetAddress resolved = address instanceof InetAddress value ? value
                    : InetAddress.getByName(address.toString());
            assertThat(resolved.getHostAddress()).as("Actual Tomcat binding is explicit IPv4 loopback")
                    .isEqualTo("127.0.0.1");
            assertThat(connector.getLocalPort()).as("Actual Tomcat connector uses the owned port").isEqualTo(port);
        }
        // OidcTestProvider's unchanged public issuer is constructed from its
        // explicitly 127.0.0.1-bound HttpServer's actual assigned port. This is
        // the owned fixture's binding contract, not an OS-wide listener inventory.
        assertThat(provider.issuer().getScheme()).isEqualTo("http");
        assertThat(provider.issuer().getHost()).isEqualTo("127.0.0.1");
        assertThat(provider.issuer().getPort()).isBetween(1, 65535);
        assertThat(provider.issuer().getPort()).isNotEqualTo(port);
        System.out.println("COOKIE_CONTEXT_RUNTIME actual_backend_loopback=true synthetic_idp_binding_contract=true");
    }

    @AfterEach
    void noLeakedFixtureFailureOrWorkspace() {
        FAULTS.reset();
        try {
            assertThat(provider.failure()).isNull();
            assertThat(counts().workspaces()).isZero();
            assertThat(counts().profiles()).isEqualTo(counts().users());
        }
        finally {
            try {
                // Teardown after every oracle and released worker uses actual hard-expiry
                // and captured cleanup; no registry reset or permissive fixture bypass.
                CLOCK.advance(Duration.ofHours(9));
                for (int attempt = 0; attempt < 16 && registry().counts().references() != 0; attempt++) {
                    registry().cleanup();
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            assertThat(registry().counts().cleanupClaims()).isZero());
                }
                assertThat(registry().counts().references()).as("Owned references physically cleaned").isZero();
                assertThat(registry().counts().contexts()).isZero();
                for (HttpSession session : List.copyOf(probe().sessions.values())) {
                    try { session.invalidate(); }
                    catch (IllegalStateException alreadyInvalid) { /* Exact captured fixture object. */ }
                }
                assertThat(probe().sessions).isEmpty();
            }
            finally {
                CLOCK.reset();
                browsers.forEach(Browser::close);
            }
        }
    }

    @Test
    @Timeout(60)
    void cookieContextRemediationCapacityReal65thBootstrapPreservesAll64ExactPairs() throws Exception {
        assertThat(registry().counts().contexts()).isZero();
        assertThat(registry().counts().references()).isZero();
        assertThat(probe().sessions.size()).isZero();
        Counts durableBefore = counts();
        int tokensBefore = provider.tokenRequests().size();
        var originalJars = new ArrayList<Browser>();
        var originalSessions = new ArrayList<HttpSession>();
        var originalStamps = new ArrayList<BrowserSessionContextRegistry.Stamp>();
        // All pairs come from real initial responses. Keep each original jar intact;
        // no manual Cookie selection, fabricated capacity exception or registry reset.
        for (int index = 0; index < BrowserSessionContextRegistry.MAX_CONTEXTS; index++) {
            Browser jar = browser();
            originalJars.add(jar);
            assertThat(jar.contextId().isEmpty() && jar.sessionId().isEmpty()).isTrue();
            assertSingleContextCookie(jar.get("/auth/csrf"));
            HttpSession session = probe().sessions.get(jar.sessionId());
            assertThat(session).isNotNull();
            var stamp = registry().capture(session);
            assertThat(stamp).isNotNull();
            assertThat(stamp.contextId().equals(jar.contextId())).isTrue();
            assertThat(registry().echo(jar.contextId(), stamp)).isTrue();
            originalSessions.add(session);
            originalStamps.add(stamp);
            assertThat(registry().counts().contexts()).isEqualTo(index + 1);
            assertThat(registry().counts().references()).isEqualTo(index + 1);
        }
        var full = registry().counts();
        assertThat(full.contexts()).isEqualTo(64);
        assertThat(full.references()).isEqualTo(64);
        assertThat(full.retiredReferences()).isZero();
        assertThat(full.owners()).isZero();
        assertThat(full.cleanupClaims()).isZero();
        Browser refused = browser();
        assertThat(refused.contextId().isEmpty() && refused.sessionId().isEmpty()).isTrue();
        HttpResponse<String> response = refused.get("/auth/csrf");
        System.out.println("COOKIE_CONTEXT_CAPACITY_HTTP kind=bootstrap65 status=" + response.statusCode()
                + " context_cookie_headers=" + response.headers().allValues("Set-Cookie").stream()
                        .filter(header -> header.startsWith(BrowserSessionContextRegistry.COOKIE_NAME + "=")).count()
                + " opaque_body=" + response.body().equals("{\"error\":\"request_not_completed\"}")
                + " registry_contexts=" + registry().counts().contexts()
                + " registry_references=" + registry().counts().references());
        assertNoContextCookie(response);
        assertThat(refused.contextId().isEmpty()).isTrue();
        assertThat(registry().counts()).isEqualTo(full);
        assertThat(counts()).isEqualTo(durableBefore);
        assertThat(provider.tokenRequests().size()).isEqualTo(tokensBefore);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(hasAuthentication(probe().sessions.get(refused.sessionId()))).isFalse();
        assertThat(hasAuthorizedClient(probe().sessions.get(refused.sessionId()))).isFalse();
        assertThat(registry().capture(probe().sessions.get(refused.sessionId()))).isNull();

        // Check every exact original pair before the final status assertion, including
        // on RED. Real CSRF/intent/entry remains usable; no provider navigation occurs.
        for (int index = 0; index < originalJars.size(); index++) {
            Browser jar = originalJars.get(index);
            HttpSession session = originalSessions.get(index);
            var stamp = originalStamps.get(index);
            HttpResponse<String> echo = jar.get("/auth/csrf");
            assertThat(echo.statusCode()).isEqualTo(200);
            assertNoContextCookie(echo);
            assertThat(jar.contextId().equals(stamp.contextId())).isTrue();
            assertThat(jar.sessionId().equals(session.getId())).isTrue();
            assertThat(probe().sessions.get(jar.sessionId()) == session).isTrue();
            assertThat(registry().capture(session).equals(stamp)).isTrue();
            assertThat(registry().echo(jar.contextId(), stamp)).isTrue();
            HttpResponse<String> intent = loginPost(jar, (String) json(echo).get("token"), Map.of());
            assertRedirect(intent, origin + "/oauth2/authorization/cognito");
            assertNoContextCookie(intent);
            HttpResponse<String> entry = jar.get("/oauth2/authorization/cognito");
            assertThat(entry.statusCode()).isEqualTo(302);
            assertThat(URI.create(location(entry)).getPath()).isEqualTo("/authorize");
            assertNoContextCookie(entry);
            assertThat(registry().capture(session).equals(stamp)).isTrue();
            assertThat(hasAuthentication(session)).isFalse();
            assertThat(hasAuthorizedClient(session)).isFalse();
            assertThat(registry().counts()).isEqualTo(full);
        }
        assertThat(counts()).isEqualTo(durableBefore);
        assertThat(provider.tokenRequests().size()).isEqualTo(tokensBefore);
        assertThat(provider.authorizationRequests()).isEmpty();
        System.out.println("COOKIE_CONTEXT_CAPACITY_HTTP original_pairs_usable=64 no_eviction=true"
                + " no_registry_growth=true no_durable_or_provider_growth=true");
        assertJsonError(response, 503);
        assertThat(response.body().equals("{\"error\":\"request_not_completed\"}")).isTrue();
    }

    @Test
    @Timeout(60)
    void cookieContextRemediationCapacityUnavailableEntryConsumesIntentAndPreservesBoundaries() throws Exception {
        Browser jar = browser();
        String csrf = csrf(jar);
        HttpSession session = probe().sessions.get(jar.sessionId());
        var stamp = registry().capture(session);
        assertThat(stamp).isNotNull();
        assertJsonError(jar.get("/oauth2/authorization/cognito"), 403);
        assertRedirect(loginPost(jar, csrf, Map.of()), origin + "/oauth2/authorization/cognito");
        CLOCK.advance(AuthenticationProperties.FLOW_LIFETIME);
        try {
            assertJsonError(jar.get("/oauth2/authorization/cognito"), 403);
        }
        finally {
            CLOCK.reset();
        }
        assertRedirect(loginPost(jar, csrf(jar), Map.of()), origin + "/oauth2/authorization/cognito");
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT) instanceof Instant).isTrue();
        Set<String> attributeNames = Set.copyOf(Collections.list(session.getAttributeNames()).stream()
                .filter(name -> !name.equals(AuthenticationConfiguration.LOGIN_INTENT)).toList());
        var resourcesBefore = registry().counts();
        Counts durableBefore = counts();
        int tokensBefore = provider.tokenRequests().size();
        var memoryField = BrowserSessionContextRegistry.class.getDeclaredField("memory");
        memoryField.setAccessible(true);
        ReentrantLock memory = (ReentrantLock) memoryField.get(registry());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = null;
        HttpResponse<String> response;
        long elapsed;
        try {
            holder = executor.submit(() -> {
                assertThat(memory.tryLock(5, TimeUnit.SECONDS)).isTrue();
                try {
                    locked.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).as("Bounded real memory-lock holder").isTrue();
                }
                finally {
                    memory.unlock();
                }
                return null;
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(memory.isLocked() && !memory.isHeldByCurrentThread()).isTrue();
            long started = System.nanoTime();
            response = jar.get("/oauth2/authorization/cognito");
            elapsed = System.nanoTime() - started;
            assertThat(release.getCount()).isEqualTo(1);
        }
        finally {
            release.countDown();
            try {
                if (holder != null) holder.get(10, TimeUnit.SECONDS);
            }
            finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
        System.out.println("COOKIE_CONTEXT_CAPACITY_HTTP kind=entry_lock status=" + response.statusCode()
                + " context_cookie_headers=" + response.headers().allValues("Set-Cookie").stream()
                        .filter(header -> header.startsWith(BrowserSessionContextRegistry.COOKIE_NAME + "=")).count()
                + " opaque_body=" + response.body().equals("{\"error\":\"request_not_completed\"}")
                + " elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(elapsed)
                + " intent_consumed=" + (session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT) == null));
        assertThat(elapsed).as("Actual unavailable-memory HTTP outcome has a finite two-second budget")
                .isLessThan(Duration.ofSeconds(2).toNanos());
        assertNoContextCookie(response);
        assertThat(response.headers().allValues("Set-Cookie").isEmpty()).isTrue();
        assertThat(registry().counts()).isEqualTo(resourcesBefore);
        assertThat(registry().capture(session).equals(stamp)).isTrue();
        assertThat(jar.contextId().equals(stamp.contextId())).isTrue();
        assertThat(jar.sessionId().equals(session.getId())).isTrue();
        assertThat(hasAuthentication(session)).isFalse();
        assertThat(hasAuthorizedClient(session)).isFalse();
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
        assertThat(Set.copyOf(Collections.list(session.getAttributeNames()))).isEqualTo(attributeNames);
        assertThat(counts()).isEqualTo(durableBefore);
        assertThat(provider.tokenRequests().size()).isEqualTo(tokensBefore);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertJsonError(jar.get("/api/me"), 401);
        assertJsonError(jar.get("/oauth2/authorization/cognito"), 403);

        Browser other = browser();
        csrf(other);
        assertRedirect(loginPost(jar, csrf(jar), Map.of()), origin + "/oauth2/authorization/cognito");
        HttpResponse<String> contextConflict = jar.exactRequest("GET", "/oauth2/authorization/cognito", "", Map.of(),
                "JSESSIONID=" + jar.sessionId() + "; " + BrowserSessionContextRegistry.COOKIE_NAME + "=" + other.contextId());
        assertJsonError(contextConflict, 409);
        assertNoContextCookie(contextConflict);
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
        assertJsonError(jar.get("/oauth2/authorization/cognito"), 403);
        assertThat(counts()).isEqualTo(durableBefore);
        assertThat(provider.tokenRequests().size()).isEqualTo(tokensBefore);
        complete(jar);
        assertAccount(jar.get("/api/me"), bindingId());
        HttpResponse<String> authenticatedConflict = jar.get("/oauth2/authorization/cognito");
        assertJsonError(authenticatedConflict, 409);
        assertNoContextCookie(authenticatedConflict);
        assertThat(counts()).isEqualTo(durableBefore);
        assertThat(provider.tokenRequests().size()).isEqualTo(tokensBefore + 1);
        System.out.println("COOKIE_CONTEXT_CAPACITY_HTTP intent_not_restored=true unsolicited403=true"
                + " expired403=true context409=true authenticated409=true full_login_after_new_intent=true");
        assertJsonError(response, 503);
        assertThat(response.body().equals("{\"error\":\"request_not_completed\"}")).isTrue();
    }

    @Test
    void fullCodeTokenJwksIdTokenOnlyFlowRotatesSessionAndReturnsOnlyExistingOwnAccount(CapturedOutput logs)
            throws Exception {
        Browser browser = browser();
        String csrf = csrf(browser);
        String anonymousId = browser.sessionId();
        HttpResponse<String> initiation = loginPost(browser, csrf, Map.of(
                "Forwarded", "host=attacker.test;proto=https", "X-Forwarded-Host", "attacker.test"));
        assertRedirect(initiation, origin + "/oauth2/authorization/" + AuthenticationProperties.REGISTRATION_ID);
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
        assertThat(counts()).isEqualTo(before);

        Map<String, String> authorization = provider.authorizationRequests().getFirst();
        Map<String, String> token = provider.tokenRequests().getFirst();
        assertThat(authorization).containsEntry("redirect_uri", origin + "/login/oauth2/code/cognito")
                .containsEntry("client_id", CLIENT_ID).containsEntry("code_challenge_method", "S256")
                .containsEntry("prompt", "login").containsEntry("max_age", "0");
        assertThat(token).containsEntry("redirect_uri", origin + "/login/oauth2/code/cognito");
        assertThat(token.get("code_verifier")).isNotBlank();
        assertThat(authorization).doesNotContainKeys("client_secret", "code_verifier", "access_token");
        assertThat(provider.jwksRequests()).isPositive();
        assertThat(provider.userInfoRequests()).isZero();
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
        assertThat(Stream.of("event=LOGIN_RESULT reason=LOGIN_INTENT_ACCEPTED",
                "event=LOGIN_RESULT reason=LOCAL_SUCCESS_SELECTED", "event=HTTP_COMPLETED route=CALLBACK")
                .allMatch(logs.getAll()::contains)).as("All expected login diagnostic codes are present").isTrue();
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
    void rawIdClaimsAreRejectedAndPoisonedUnrequestedUserInfoHasNoEffect(String surface, String claim,
            Object value, boolean missing) throws Exception {
        Scenario scenario = Scenario.verified(subject);
        if (surface.equals("id")) {
            scenario = missing ? scenario.withoutIdClaim(claim) : scenario.idClaim(claim, value);
        }
        else {
            scenario = missing ? scenario.withoutUserInfoClaim(claim) : scenario.userInfoClaim(claim, value);
        }
        if (surface.equals("userinfo")) {
            // This retired source is never fetched; poison cannot replace the verified ID-token identity.
            provider.scenario(scenario);
            Browser control = browser();
            complete(control);
            assertAccount(control.get("/api/me"), bindingId());
            assertThat(counts()).isEqualTo(before);
        } else {
            assertRejectedWithoutAccount(scenario);
        }
        assertThat(provider.tokenRequests()).hasSize(1);
        assertThat(provider.userInfoRequests()).isZero();
    }

    static Stream<String> protocolFailures() {
        return Stream.of("signature", "key", "algorithm", "issuer", "issuer-missing", "audience", "audience-missing",
                "azp", "azp-number", "multi-audience-no-azp", "expired", "expiry-missing", "issued-in-future", "iat-missing",
                "nonce", "nonce-missing", "state", "token", "userinfo-subject", "subject-blank",
                "subject-number", "subject-missing", "auth-time-stale", "auth-time-future", "admission");
    }

    @ParameterizedTest
    @MethodSource("protocolFailures")
    void protocolAndFreshnessRefusalAndUnrequestedUserInfoControlPreserveExistingBindings(String fault) throws Exception {
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
            case "userinfo-subject" -> valid.userInfoClaim("sub", "synthetic|another-person");
            case "subject-blank" -> valid.idClaim("sub", " ");
            case "subject-number" -> valid.idClaim("sub", 123);
            case "subject-missing" -> valid.withoutIdClaim("sub");
            case "auth-time-stale" -> valid.idClaim("auth_time", now - 120);
            case "auth-time-future" -> valid.idClaim("auth_time", now + 120);
            case "admission" -> Scenario.verified("synthetic|not-admitted");
            default -> throw new AssertionError(fault);
        };
        if (fault.equals("userinfo-subject")) {
            provider.scenario(invalid);
            Browser control = browser();
            complete(control);
            assertAccount(control.get("/api/me"), bindingId());
            assertThat(provider.userInfoRequests()).isZero();
            assertThat(counts()).isEqualTo(before);
        } else {
            assertRejectedWithoutAccount(invalid);
        }
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
        assertThat(counts()).isEqualTo(before);

        provider.scenario(Scenario.verified("synthetic|pilot-499"));
        Browser other = browser();
        complete(other);
        assertThat(json(other.get("/api/me")).get("id")).isNotEqualTo(id.toString());
        assertThat(counts()).isEqualTo(before);
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
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void directEntryAndUnsolicitedCallbackCannotCreateAnAccount() throws Exception {
        Browser browser = browser();
        assertJsonError(browser.get("/oauth2/authorization/cognito"), 403);
        assertRedirect(browser.get("/login/oauth2/code/cognito?code=not-issued&state=not-saved"),
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
                origin + "/oauth2/authorization/cognito");
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
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void logoutDestroysServerSessionAndClientStateAndOldCookieCannotReturn(CapturedOutput logs) throws Exception {
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
        assertThat(logs.getAll().contains("event=LOGOUT_RESULT reason=LOCAL_LOGOUT_COMPLETED"))
                .as("Local logout completion is observed").isTrue();
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
        assertThat(counts()).isEqualTo(before);
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
        assertRejectedWithoutAccount(Scenario.verified(subject), before);
        assertThat(application.getBean(UserService.class).findUser(id).status()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void idleAndAbsoluteExpiryDestroyClientStateAndNeverReturnPrivateData(CapturedOutput logs) throws Exception {
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
        assertThat(logs.getAll().contains("event=SESSION_EXPIRED reason=ABSOLUTE_LIFETIME_EXPIRED"))
                .as("Observed absolute expiry is classified").isTrue();
    }

    @Test
    void databaseAcquisitionFailureFailsClosedAndDoesNotPretendSessionWasDestroyed(CapturedOutput logs) throws Exception {
        Browser browser = browser();
        complete(browser);
        String id = browser.sessionId();
        FAULTS.failAcquisition.set(true);
        try {
            assertJsonError(browser.get("/api/me"), 503);
            assertThat(probe().sessions).containsKey(id);
            assertThat(logs.getAll().contains("event=REQUEST_FAILURE reason=DATABASE_UNAVAILABLE"))
                    .as("Database failure has a bounded diagnostic reason").isTrue();
            assertThat(logs.getAll().contains("Test-only connection acquisition unavailable"))
                    .as("Raw driver diagnostic is not disclosed by observability").isFalse();
            assertThat(logs.getAll().contains("event=LOGOUT_RESULT"))
                    .as("Unknown database state is not logout").isFalse();
        }
        finally {
            FAULTS.failAcquisition.set(false);
        }
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
    }

    @Test
    void failedLogoutIsNotReportedAsSuccessAndDoesNotInvalidateSession(CapturedOutput logs) throws Exception {
        Browser browser = browser();
        complete(browser);
        String oldId = browser.sessionId();
        assertJsonError(browser.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", "invalid")), 403);
        assertThat(probe().sessions).containsKey(oldId);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
        assertThat(logs.getAll().contains("event=LOGOUT_RESULT"))
                .as("Failed CSRF logout does not report success").isFalse();
    }

    @Test
    void actualCommitAcknowledgementLossResolvesThroughNewFullLoginOfSameIdentity() throws Exception {
        Browser browser = browser();
        URI callback = begin(browser);
        FAULTS.loseNextCommitAcknowledgement.set(true);
        assertRedirect(browser.get(callback), origin + "/login?auth=failed");
        assertThat(FAULTS.committedThenFailed.get()).isEqualTo(1);
        assertThat(counts()).isEqualTo(before);
        UUID committedId = bindingId();
        assertJsonError(browser.get("/api/me"), 401);
        int oldTokenCalls = provider.tokenRequests().size();
        complete(browser);
        assertThat(provider.tokenRequests()).hasSize(oldTokenCalls + 1);
        assertThat(bindingId()).isEqualTo(committedId);
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(committedId.toString());
        assertThat(counts()).isEqualTo(before);
        assertThat(provider.tokenRequests().get(0).get("code"))
                .isNotEqualTo(provider.tokenRequests().get(1).get("code"));
    }

    // First-login INSERT contention and commit-unknown creation remain explicit
    // UserAuthenticationIntegrationTest service/DB proofs. The supported HTTP
    // adapter is existing-only; its callback races use real current-identity reads.

    @Test
    void realBackendRestartClearsSessionsAndRestartedAdmissionRejectsOldIdentity() throws Exception {
        Browser browser = browser();
        complete(browser);
        String oldSession = browser.sessionId();
        String oldContext = browser.contextId();
        UUID original = bindingId();
        application.close();
        admitted = Set.of();
        try {
            startBackend();
            verifyOwnedListenerBindings();
            jdbc = application.getBean(JdbcTemplate.class);
            assertThat(probe().sessions).doesNotContainKey(oldSession);
            assertJsonError(browser.get("/api/me"), 401);
            assertRejectedWithoutAccount(Scenario.verified(subject), before);
            assertThat(bindingId()).isEqualTo(original);
        }
        finally {
            application.close();
            admitted = PILOT_SUBJECTS;
            startBackend();
            verifyOwnedListenerBindings();
            jdbc = application.getBean(JdbcTemplate.class);
        }
        assertJsonError(browser.get("/api/me"), 401);
        // Q-aware setup only: preserve this same jar and every original restart,
        // admission, and identity assertion while performing the new explicit recovery.
        HttpResponse<String> cleanupCsrf = browser.get("/auth/csrf");
        assertNoContextCookie(cleanupCsrf);
        HttpResponse<String> cleanup = browser.post(URI.create(origin + "/auth/logout"), "", Map.of(
                "Origin", origin, "X-CSRF-TOKEN", (String) json(cleanupCsrf).get("token")));
        assertJsonError(cleanup, 409);
        assertNoContextCookie(cleanup);
        assertThat(browser.sessionId()).isEmpty();
        assertNewRecoveryBootstrapAndEcho(browser, oldContext, oldSession);
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
        assertRedirect(response, origin + "/oauth2/authorization/cognito");
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
        assertThat(counts()).isEqualTo(before);
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
        assertThat(counts()).isEqualTo(before);
        assertThat(provider.tokenRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%6fauth2/authorization/cognito", "/oauth2/%61uthorization/cognito",
            "/oauth2/authorization/%63ognito"})
    void routeEntryRequiresExplicitIntentBeforeProviderNavigation(String path) throws Exception {
        Browser browser = browser();
        csrf(browser);
        assertJsonError(browser.get(path), 403);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(provider.tokenRequests()).isEmpty();
        assertNoAccountOrAuthentication(browser);
        complete(browser);
        assertThat(counts()).isEqualTo(before);
        logout(browser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%6fauth2/authorization/cognito", "/oauth2/%61uthorization/cognito",
            "/oauth2/authorization/%63ognito"})
    void routeAuthenticatedEntryCannotReplacePrincipalWithoutLogout(String path) throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID original = bindingId();
        String session = browser.sessionId();
        String otherSubject = "synthetic|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS).contains(otherSubject);
        provider.scenario(Scenario.verified(otherSubject));
        assertJsonError(browser.get(path), 409);
        assertThat(browser.sessionId()).isEqualTo(session);
        assertThat(hasAuthentication(probe().sessions.get(session))).isTrue();
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before);
        assertThat(provider.authorizationRequests()).hasSize(1);
        assertThat(provider.tokenRequests()).hasSize(1);
        logout(browser);
        complete(browser);
        assertThat(json(browser.get("/api/me")).get("id")).isNotEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before);
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
        assertJsonError(browser.get("/oauth2/authorization/cognito"), 403);
        assertThat(provider.authorizationRequests()).isEmpty();
        assertThat(provider.tokenRequests()).isEmpty();
        assertNoAccountOrAuthentication(browser);
        // The same token succeeds with the correct Origin: bad CSRF is not the negative's cause.
        assertRedirect(browser.get(authorize(browser, loginPost(browser, token, Map.of()))), origin + "/account");
        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%6cogin/oauth2/code/cognito", "/login/%6fauth2/code/cognito",
            "/login/oauth2/%63ode/cognito"})
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
        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login/oauth2/code/cognito", "/%6cogin/oauth2/code/cognito",
            "/login/oauth2/code/%63ognito"})
    void routeAlreadyPendingSecondCallbackCannotReplaceAuthenticatedPrincipal(String path) throws Exception {
        Browser browser = browser();
        URI firstCallback = begin(browser);
        ResolutionReadPause race = new ResolutionReadPause(true);
        FAULTS.resolutionReadPause = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> first = null;
        URI secondCallback;
        String anonymousSession = browser.sessionId();
        String otherSubject = "synthetic|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS).contains(otherSubject);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity_bindings WHERE issuer = ? AND subject = ?",
                Long.class, provider.issuer().toString(), otherSubject)).isEqualTo(1);
        try {
            first = executor.submit(() -> browser.get(firstCallback));
            assertThat(race.firstReadReturned.await(5, TimeUnit.SECONDS)).isTrue();
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
                FAULTS.resolutionReadPause = null;
            }
        }
        UUID original = bindingId();
        String session = browser.sessionId();
        assertThat(session).isNotEqualTo(anonymousSession);
        assertThat(counts()).isEqualTo(before);
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
                Long.class, provider.issuer().toString(), otherSubject)).isEqualTo(1);
        assertThat(browser.sessionId()).isEqualTo(session);
        assertThat(hasAuthentication(probe().sessions.get(session))).isTrue();
        assertThat(json(browser.get("/api/me")).get("id")).isEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before);
        logout(browser);
        complete(browser);
        assertThat(json(browser.get("/api/me")).get("id")).isNotEqualTo(original.toString());
        assertThat(counts()).isEqualTo(before);
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
                Arguments.of("/%61uth/csrf", "/%61uth/login", "/%6fauth2/authorization/cognito",
                        "/%6cogin/oauth2/code/cognito", "/%61pi/me", "/%61uth/logout"),
                Arguments.of("/a%75th/csrf", "/a%75th/login", "/oauth2/%61uthorization/cognito",
                        "/login/%6fauth2/code/cognito", "/a%70i/me", "/a%75th/logout"),
                Arguments.of("/auth/%63srf", "/auth/%6cogin", "/oauth2/authorization/%63ognito",
                        "/login/oauth2/code/%63ognito", "/api/%6de", "/auth/%6cogout"));
    }

    @ParameterizedTest
    @MethodSource("routeAliases")
    void routeAuthorizedAliasesPreserveOidcQueryAndLogout(String csrfPath, String loginPath, String entryPath,
            String callbackPath, String privatePath, String logoutPath) throws Exception {
        Browser browser = browser();
        String token = (String) json(browser.get(csrfPath)).get("token");
        String anonymousSession = browser.sessionId();
        assertRedirect(browser.post(URI.create(origin + loginPath), "_csrf=" + encode(token),
                Map.of("Origin", origin)), origin + "/oauth2/authorization/cognito");
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
        assertThat(counts()).isEqualTo(before);
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
        String otherSubject = "synthetic|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS).contains(otherSubject);
        String winnerSubject = reverseIdentityOrder ? otherSubject : subject;
        String loserSubject = reverseIdentityOrder ? subject : otherSubject;
        provider.scenario(Scenario.verified(winnerSubject));
        URI winnerCallback = begin(browser);
        ResolutionReadPause race = new ResolutionReadPause(true);
        FAULTS.resolutionReadPause = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> winner = null;
        try {
            winner = executor.submit(() -> browser.get(winnerCallback));
            assertThat(race.firstReadReturned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(winner.isDone()).isFalse();
            assertThat(counts()).isEqualTo(before);
            provider.scenario(Scenario.verified(loserSubject));
            URI loserCallback = begin(browser);
            HttpResponse<String> rejected = browser.get(loserCallback);
            // A real callback has passed anonymous admission and read its existing current binding.
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
            assertThat(bindingCount(loserSubject)).as("Rejected callback preserves its pre-existing binding").isEqualTo(1);
            assertFlowNotSaved(browser, loserCallback);
            assertThat(counts()).isEqualTo(before);
            assertThat(race.firstCommits.get()).isEqualTo(1);
            assertThat(provider.tokenRequests()).hasSize(1);
        }
        finally {
            race.releaseFirst.countDown();
            try { finishWorkers(executor, winner); }
            finally { FAULTS.resolutionReadPause = null; }
        }
    }

    @Test
    void independentBrowserSessionCompletesWhileOtherCallbackIsHeld() throws Exception {
        Browser heldBrowser = browser();
        Browser independent = browser();
        URI heldCallback = begin(heldBrowser);
        ResolutionReadPause race = new ResolutionReadPause(true);
        FAULTS.resolutionReadPause = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> held = null;
        try {
            held = executor.submit(() -> heldBrowser.get(heldCallback));
            assertThat(race.firstReadReturned.await(5, TimeUnit.SECONDS)).isTrue();
            String independentSubject = nextSubject();
            provider.scenario(Scenario.verified(independentSubject));
            complete(independent);
            assertThat(held.isDone()).isFalse();
            assertThat(counts()).isEqualTo(before);
            UUID independentId = bindingId(independentSubject);
            OAuth2AuthorizedClient client = authorizedClient(probe().sessions.get(independent.sessionId()));
            assertSessionIdentity(independent, independentId, client);
            race.releaseFirst.countDown();
            assertRedirect(held.get(10, TimeUnit.SECONDS), origin + "/account");
            assertSessionIdentity(heldBrowser, bindingId(), authorizedClient(probe().sessions.get(heldBrowser.sessionId())));
            assertSessionIdentity(independent, independentId, client);
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(counts()).isEqualTo(before);
        }
        finally {
            race.releaseFirst.countDown();
            try { finishWorkers(executor, held); }
            finally { FAULTS.resolutionReadPause = null; }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackFailurePreservesNewerSavedFlowAndReleasesAdmission(boolean databaseFailure) throws Exception {
        Browser browser = browser();
        provider.scenario(databaseFailure ? Scenario.verified(subject) : Scenario.verified(subject).tokenFailure());
        URI failedCallback = begin(browser);
        var tokenGate = databaseFailure ? null : provider.holdTokenResponse(subject);
        ResolutionReadPause race = databaseFailure ? new ResolutionReadPause(false) : null;
        FAULTS.resolutionReadPause = race;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> failed = null;
        try {
            failed = executor.submit(() -> browser.get(failedCallback));
            assertThat(databaseFailure ? race.firstReadReturned.await(5, TimeUnit.SECONDS)
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
            assertThat(bindingCount(subject)).as("Failed read/token request cannot mutate an existing binding").isEqualTo(1);
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(counts()).isEqualTo(before);
        }
        finally {
            if (tokenGate != null) tokenGate.close();
            if (race != null) race.releaseFirst.countDown();
            try { finishWorkers(executor, failed); }
            finally { FAULTS.resolutionReadPause = null; }
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
            assertThat(counts()).isEqualTo(before);
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
            assertThat(bindingCount(subject)).isEqualTo(1);
            assertThat(counts()).isEqualTo(before);
            assertThat(provider.tokenRequests()).hasSize(2);
            if (committed) {
                assertThat(bindingId()).isEqualTo(committedId);
                assertThat(commitPause.observedCommits.get()).isEqualTo(1);
                logout(browser);
                provider.scenario(Scenario.verified(subject));
                complete(browser);
                assertSessionIdentity(browser, committedId, authorizedClient(probe().sessions.get(browser.sessionId())));
                assertThat(counts()).isEqualTo(before);
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
            assertThat(counts()).isEqualTo(before);
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
            assertThat(counts()).isEqualTo(before);
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

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextO1AfterRealUserLookupRejectsRevokedCapturedStamp() throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID oldUser = bindingId();
        String context = browser.contextId();
        var oldStamp = registry().capture(probe().sessions.get(browser.sessionId()));
        GateProbe gate = new GateProbe("O1-A", false);
        CurrentUserAccessFilter.gateObserver = gate;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> old = null;
        try {
            old = executor.submit(() -> browser.get("/api/me", Map.of("X-Cookie-Control", "O1-A")));
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.stamp.get().equals(oldStamp)).as("A retained its pre-DB immutable stamp").isTrue();
            assertThat(gate.decisions).doesNotContainKey("O1-A");
            assertThat(old.isDone()).isFalse();
            assertThat(logout(browser).statusCode()).isEqualTo(204);
            int revoked = gate.event("logical-revoke-completed");
            String next = nextSubject();
            provider.scenario(Scenario.verified(next));
            complete(browser);
            var newStamp = registry().capture(probe().sessions.get(browser.sessionId()));
            assertThat(browser.contextId().equals(context)).as("B uses the same Q").isTrue();
            assertThat(newStamp.generation()).isGreaterThan(oldStamp.generation());
            assertAccount(browser.get("/api/me", Map.of("X-Cookie-Control", "O1-B")), bindingId(next));
            assertThat(gate.decisions.get("O1-B").allowed()).isTrue();
            assertThat(gate.decisions.get("O1-B").order()).isGreaterThan(revoked);
            assertThat(old.isDone()).isFalse();
            gate.release.countDown();
            assertJsonError(old.get(30, TimeUnit.SECONDS), 401);
            assertThat(gate.decisions.get("O1-A").allowed()).isFalse();
            assertThat(gate.decisions.get("O1-A").order()).isGreaterThan(gate.decisions.get("O1-B").order());
            assertAccount(browser.get("/api/me"), bindingId(next));
            assertThat(bindingId()).isEqualTo(oldUser);
            assertThat(counts()).isEqualTo(before);
        }
        finally {
            gate.release.countDown();
            try { finishWorkers(executor, old); }
            finally { CurrentUserAccessFilter.gateObserver = new GateProbe("unused", false); }
        }
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextO2TwoBootstrapLiveCrossQFirstAdmissionAfterB() throws Exception {
        Browser jar = browser();
        // Both requests are sent Cookie-absent. Applying the actual B headers is delayed
        // by this Java control; this is not a browser delivery/SameSite proof.
        var bootstrapA = jar.cookieFreeGetAsync("/auth/csrf");
        var bootstrapB = jar.cookieFreeGetAsync("/auth/csrf");
        HttpResponse<String> first = bootstrapA.get(30, TimeUnit.SECONDS);
        HttpResponse<String> delayed = bootstrapB.get(30, TimeUnit.SECONDS);
        assertSingleContextCookie(first);
        assertSingleContextCookie(delayed);
        jar.applyHeaders(first);
        String contextA = jar.contextId();
        complete(jar);
        String sessionA = jar.sessionId();
        HttpSession original = probe().sessions.get(sessionA);
        var stampA = registry().capture(original);
        UUID userA = bindingId();
        GateProbe gate = new GateProbe("O2-A", false);
        CurrentUserAccessFilter.gateObserver = gate;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> old = null;
        try {
            old = executor.submit(() -> jar.get("/api/me", Map.of("X-Cookie-Control", "O2-A")));
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.decisions).doesNotContainKey("O2-A");
            assertThat(gate.stamp.get().equals(stampA)).isTrue();
            assertThat(old.isDone()).isFalse();
            jar.applyHeaders(delayed); // The one original B issuance, never replayed.
            String contextB = jar.contextId();
            assertThat(contextB.equals(contextA)).as("The two initial bootstraps have independent Q").isFalse();
            assertThat(registry().echo(contextA, stampA)).as("Q_A remains live; no added logout or request admission").isTrue();
            assertThat(hasAuthentication(original)).isTrue();
            String next = nextSubject();
            provider.scenario(Scenario.verified(next));
            complete(jar);
            UUID userB = bindingId(next);
            assertAccount(jar.get("/api/me", Map.of("X-Cookie-Control", "O2-B")), userB);
            assertThat(gate.decisions).doesNotContainKey("O2-A");
            assertThat(old.isDone()).isFalse();
            gate.release.countDown();
            assertAccount(old.get(30, TimeUnit.SECONDS), userA);
            assertThat(gate.decisions.get("O2-A").allowed()).isTrue();
            assertThat(gate.decisions.get("O2-A").order()).isGreaterThan(gate.decisions.get("O2-B").order());
            assertThat(jar.contextId().equals(contextB)).isTrue();
            assertAccount(jar.get("/api/me"), userB);
            assertThat(probe().sessions.get(sessionA) == original).as("Independent live A is not globally revoked").isTrue();
            assertThat(counts()).isEqualTo(before);
            System.out.println("COOKIE_CONTEXT_ORACLE O2=L1_CHARACTERIZATION NOT_DEFECT_FIXED_GREEN");
        }
        finally {
            gate.release.countDown();
            try { finishWorkers(executor, old); }
            finally { CurrentUserAccessFilter.gateObserver = new GateProbe("unused", false); }
        }
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextAdmittedBeforeRevokeMayFinishAfterB() throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID userA = bindingId();
        GateProbe gate = new GateProbe("admitted-A", true);
        CurrentUserAccessFilter.gateObserver = gate;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> old = null;
        try {
            old = executor.submit(() -> browser.get("/api/me", Map.of("X-Cookie-Control", "admitted-A")));
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.decisions.get("admitted-A").allowed()).isTrue();
            // Exact real invalidation avoids Spring logout's separate shared-context
            // clearing behavior; no principal or final decision is injected by the fixture.
            probe().sessions.get(browser.sessionId()).invalidate();
            assertThat(registry().finalAdmit(browser.contextId(), gate.stamp.get())).isFalse();
            int revoked = gate.event("exact-session-invalidation-and-generation-retirement-observed");
            assertThat(gate.decisions.get("admitted-A").order()).isLessThan(revoked);
            String next = nextSubject();
            provider.scenario(Scenario.verified(next));
            complete(browser);
            assertAccount(browser.get("/api/me", Map.of("X-Cookie-Control", "admitted-B")), bindingId(next));
            assertThat(old.isDone()).isFalse();
            gate.release.countDown();
            assertAccount(old.get(30, TimeUnit.SECONDS), userA);
            assertAccount(browser.get("/api/me"), bindingId(next));
            assertThat(counts()).isEqualTo(before);
        }
        finally {
            gate.release.countDown();
            try { finishWorkers(executor, old); }
            finally { CurrentUserAccessFilter.gateObserver = new GateProbe("unused", false); }
        }
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextLateSuccessJavaCookieControlCannotReadAAfterB() throws Exception {
        lateSuccessJavaCookieControl(false);
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextInvalidatedLateSuccessJavaCookieControlRequiresRecovery() throws Exception {
        lateSuccessJavaCookieControl(true);
    }

    private void lateSuccessJavaCookieControl(boolean invalidateA) throws Exception {
        Browser jar = browser();
        var first = jar.cookieFreeGetAsync("/auth/csrf");
        var second = jar.cookieFreeGetAsync("/auth/csrf");
        HttpResponse<String> initialA = first.get(30, TimeUnit.SECONDS);
        HttpResponse<String> initialB = second.get(30, TimeUnit.SECONDS);
        jar.applyHeaders(initialA);
        String contextA = jar.contextId();
        URI callbackA = begin(jar);
        SuccessPause pause = new SuccessPause();
        FAULTS.successPause = pause;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> held = null;
        try {
            held = executor.submit(() -> jar.request("GET", callbackA, "", Map.of("X-Cookie-Control", "late-success")));
            assertThat(pause.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pause.actualRotationCookie.get()).isTrue();
            assertThat(pause.uncommitted.get()).isTrue();
            assertThat(pause.session.get()).isNotNull();
            String rotatedA = pause.session.get().getId();
            var stampA = registry().capture(pause.session.get());
            assertThat(registry().finalAdmit(contextA, stampA)).isTrue();
            assertThat(hasAuthentication(pause.session.get())).isTrue();
            assertThat(counts()).isEqualTo(before);
            if (invalidateA) {
                pause.session.get().invalidate();
                assertThat(probe().sessions).doesNotContainKey(rotatedA);
                assertThat(registry().finalAdmit(contextA, stampA)).isFalse();
            }
            jar.applyHeaders(initialB);
            String contextB = jar.contextId();
            assertThat(contextB.equals(contextA)).isFalse();
            String next = nextSubject();
            provider.scenario(Scenario.verified(next));
            complete(jar);
            assertAccount(jar.get("/api/me"), bindingId(next));
            assertThat(held.isDone()).isFalse();
            pause.release.countDown();
            HttpResponse<String> late = held.get(30, TimeUnit.SECONDS);
            assertRedirect(late, origin + "/account");
            assertNoContextCookie(late);
            assertThat(jar.contextId().equals(contextB)).isTrue();
            assertThat(jar.sessionId().equals(rotatedA)).isTrue();
            assertThat(registry().finalAdmit(contextA, stampA)).as("A validity matches the declared control").isEqualTo(!invalidateA);
            assertJsonError(jar.get("/api/me"), 401);
            HttpResponse<String> exactA = jar.exactRequest("GET", "/api/me", "", Map.of(),
                    "JSESSIONID=" + rotatedA + "; " + BrowserSessionContextRegistry.COOKIE_NAME + "=" + contextA);
            if (invalidateA) assertJsonError(exactA, 401);
            else assertAccount(exactA, bindingId());
            assertNoContextCookie(logout(jar));
            complete(jar);
            assertAccount(jar.get("/api/me"), bindingId(next));
            assertThat(counts()).isEqualTo(before);
            System.out.println("COOKIE_CONTEXT_ORACLE late_success=JAVA_HTTP_CONTROL invalidated_A=" + invalidateA
                    + " browser_proof=NOT_RUN");
        }
        finally {
            pause.release.countDown();
            try { finishWorkers(executor, held); }
            finally { FAULTS.successPause = null; }
        }
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextSameQLateSuccessLogoutThroughOldSidRevokesA() throws Exception {
        Browser jar = browser();
        URI callbackA = begin(jar);
        String anonymousA = jar.sessionId();
        String context = jar.contextId();
        SuccessPause pause = new SuccessPause();
        FAULTS.successPause = pause;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> held = null;
        try {
            held = executor.submit(() -> jar.request("GET", callbackA, "", Map.of("X-Cookie-Control", "late-success")));
            assertThat(pause.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pause.actualRotationCookie.get()).isTrue();
            assertThat(pause.uncommitted.get()).isTrue();
            String rotatedA = pause.session.get().getId();
            var stampA = registry().capture(pause.session.get());
            assertThat(registry().finalAdmit(context, stampA)).isTrue();
            assertThat(jar.sessionId().equals(anonymousA)).isTrue();
            assertThat(probe().sessions).doesNotContainKey(anonymousA);
            // Real CSRF creates C from the now-stale SID, retaining the presented Q.
            String cleanupToken = csrf(jar);
            String cleanupSession = jar.sessionId();
            assertThat(cleanupSession.equals(anonymousA) || cleanupSession.equals(rotatedA)).isFalse();
            HttpResponse<String> logicalLogout = jar.post(URI.create(origin + "/auth/logout"), "",
                    Map.of("Origin", origin, "X-CSRF-TOKEN", cleanupToken));
            assertThat(logicalLogout.statusCode()).isEqualTo(204);
            assertNoContextCookie(logicalLogout);
            assertThat(registry().finalAdmit(context, stampA)).isFalse();
            assertThat(held.isDone()).isFalse();
            String next = nextSubject();
            provider.scenario(Scenario.verified(next));
            complete(jar);
            var stampB = registry().capture(probe().sessions.get(jar.sessionId()));
            assertThat(stampB.contextId().equals(stampA.contextId())).isTrue();
            assertThat(stampB.generation()).isGreaterThan(stampA.generation());
            assertAccount(jar.get("/api/me"), bindingId(next));
            assertThat(held.isDone()).isFalse();
            pause.release.countDown();
            HttpResponse<String> late = held.get(30, TimeUnit.SECONDS);
            assertRedirect(late, origin + "/account");
            assertNoContextCookie(late);
            assertThat(jar.contextId().equals(context)).isTrue();
            assertThat(jar.sessionId().equals(rotatedA)).isTrue();
            assertJsonError(jar.get("/api/me"), 401);
            assertNoContextCookie(logout(jar));
            complete(jar);
            assertAccount(jar.get("/api/me"), bindingId(next));
            assertThat(bindingCount(subject)).isEqualTo(1);
            assertThat(bindingCount(next)).isEqualTo(1);
            assertThat(counts()).isEqualTo(before);
            System.out.println("COOKIE_CONTEXT_ORACLE same_Q_old_SID_logout=JAVA_HTTP_CONTROL browser_proof=NOT_RUN");
        }
        finally {
            pause.release.countDown();
            try { finishWorkers(executor, held); }
            finally { FAULTS.successPause = null; }
        }
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextLateFinallyCannotReleaseNewGenerationOwner() throws Exception {
        // Retain every existing real token-barrier, principal, cookie and durable-binding assertion.
        staleAttemptFinallyCannotReleaseNewerBusyAdmission();
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextIndependentQProgressWhileOtherRealDatabaseCallbackIsHeld() throws Exception {
        // Preserve the real existing-owner read barrier and every durable/principal assertion.
        independentBrowserSessionCompletesWhileOtherCallbackIsHeld();
    }

    @Test
    @Timeout(60)
    void cookieContextBootstrapEchoRotationAndNoReissueOrAdoption() throws Exception {
        Browser browser = browser();
        HttpResponse<String> first = browser.get("/auth/csrf");
        assertSingleContextCookie(first);
        String context = browser.contextId();
        String anonymous = browser.sessionId();
        var stamp = registry().capture(probe().sessions.get(anonymous));
        assertThat(stamp).isNotNull();
        assertThat(registry().echo(context, stamp)).isTrue();
        String token = (String) json(first).get("token");
        HttpResponse<String> noEcho = browser.exactRequest("POST", "/auth/login", "_csrf=" + encode(token),
                Map.of("Origin", origin), "JSESSIONID=" + anonymous);
        assertJsonError(noEcho, 409);
        assertNoContextCookie(noEcho);
        assertNoContextCookie(browser.get("/auth/csrf"));
        HttpResponse<String> initiation = loginPost(browser, token, Map.of());
        assertNoContextCookie(initiation);
        HttpResponse<String> callback = browser.get(authorize(browser, initiation));
        assertRedirect(callback, origin + "/account");
        assertNoContextCookie(callback);
        assertThat(browser.sessionId().equals(anonymous)).isFalse();
        assertThat(registry().capture(probe().sessions.get(browser.sessionId())).equals(stamp))
                .as("Real fixation rotation preserves the immutable logical stamp").isTrue();
        assertThat(csrf(browser).equals(token)).as("Real Spring CSRF rotates").isFalse();
        assertAccount(browser.get("/api/me"), bindingId());
        String session = browser.sessionId();
        String sidOnly = "JSESSIONID=" + session;
        HttpResponse<String> missing = browser.exactRequest("GET", "/api/me", "", Map.of(), sidOnly);
        assertJsonError(missing, 401);
        assertNoContextCookie(missing);
        HttpResponse<String> cleanupBootstrap = browser.exactRequest("GET", "/auth/csrf", "", Map.of(), sidOnly);
        assertThat(cleanupBootstrap.statusCode()).isEqualTo(200);
        assertNoContextCookie(cleanupBootstrap);
        assertThat(registry().capture(probe().sessions.get(session)).equals(stamp)).isTrue();
        assertAccount(browser.get("/api/me"), bindingId());
        assertNoContextCookie(logout(browser));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    @Timeout(60)
    void cookieContextMissingUnknownMismatchDuplicatesAndCsrfCleanupStatuses() throws Exception {
        Browser a = browser();
        complete(a);
        UUID userA = bindingId();
        Browser b = browser();
        String next = nextSubject();
        provider.scenario(Scenario.verified(next));
        complete(b);
        String sidA = "JSESSIONID=" + a.sessionId();
        String qA = BrowserSessionContextRegistry.COOKIE_NAME + "=" + a.contextId();
        String qB = BrowserSessionContextRegistry.COOKIE_NAME + "=" + b.contextId();
        for (String cookie : List.of(sidA, sidA + "; " + BrowserSessionContextRegistry.COOKIE_NAME + "=unknown",
                sidA + "; " + BrowserSessionContextRegistry.COOKIE_NAME + "=" + "A".repeat(43),
                sidA + "; " + qB, sidA + "; " + qA + "; " + qA,
                sidA + "; " + qA + "; " + qB)) {
            HttpResponse<String> rejected = a.exactRequest("GET", "/api/me", "", Map.of(), cookie);
            assertJsonError(rejected, 401);
            assertNoContextCookie(rejected);
        }
        assertAccount(a.get("/api/me"), userA);
        assertAccount(b.get("/api/me"), bindingId(next));
        // A real CSRF token is session-specific; swapping the presented SID does not revoke B.
        String csrfA = csrf(a);
        HttpResponse<String> csrfRejected = b.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", csrfA));
        assertJsonError(csrfRejected, 403);
        assertNoContextCookie(csrfRejected);
        assertAccount(b.get("/api/me"), bindingId(next));
        // Valid CSRF but no usable Q cleans the presented session only, not context-wide success.
        HttpResponse<String> cleanup = a.exactRequest("POST", "/auth/logout", "", Map.of(
                "Origin", origin, "X-CSRF-TOKEN", csrfA), sidA);
        assertJsonError(cleanup, 409);
        assertNoContextCookie(cleanup);
        assertJsonError(a.get("/api/me"), 401);
        assertAccount(b.get("/api/me"), bindingId(next));
        assertNoContextCookie(logout(b));
        assertJsonError(b.get("/api/me"), 401);
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    @Timeout(60)
    void cookieContextRecoveryExpiredQRetainsSameJarAndDurableIdentity() throws Exception {
        Browser browser = browser();
        CLOCK.advance(Duration.ofHours(-7));
        csrf(browser);
        String context = browser.contextId();
        CLOCK.reset();
        complete(browser);
        UUID durable = bindingId();
        String authenticatedSession = browser.sessionId();
        assertAccount(browser.get("/api/me"), durable);
        CLOCK.advance(Duration.ofHours(1).plusSeconds(1));
        assertJsonError(browser.get("/api/me"), 401);
        HttpResponse<String> bootstrap = browser.get("/auth/csrf");
        assertNoContextCookie(bootstrap);
        assertThat(browser.contextId().equals(context)).isTrue();
        HttpResponse<String> cleanup = browser.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", (String) json(bootstrap).get("token")));
        assertJsonError(cleanup, 409);
        assertNoContextCookie(cleanup);
        assertThat(counts()).isEqualTo(before);
        assertThat(browser.sessionId()).as("Real cleanup removes the incoming SID from this same jar").isEmpty();
        System.out.println("COOKIE_CONTEXT_RECOVERY_SETUP kind=expired private401=true cleanup409=true same_jar=true");
        String newCsrf = assertNewRecoveryBootstrapAndEcho(browser, context, authenticatedSession);
        // OIDC uses the real synthetic-provider clock. Old expiry was already reached
        // and a distinct new context was already reserved before this fixture-clock reset.
        CLOCK.reset();
        try {
            assertRecoveryLoginRetainsBinding(browser, newCsrf, durable);
        }
        finally {
            CLOCK.advance(Duration.ofHours(1).plusSeconds(1));
        }
    }

    @Test
    @Timeout(60)
    void cookieContextRecoveryAfterActualRestartRetainsSameJarAndDurableIdentity() throws Exception {
        Browser browser = browser();
        complete(browser);
        UUID durable = bindingId();
        String context = browser.contextId();
        String authenticatedSession = browser.sessionId();
        application.close();
        startBackend();
        verifyOwnedListenerBindings();
        jdbc = application.getBean(JdbcTemplate.class);
        assertJsonError(browser.get("/api/me"), 401);
        HttpResponse<String> bootstrap = browser.get("/auth/csrf");
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        assertNoContextCookie(bootstrap);
        assertThat(browser.contextId().equals(context)).isTrue();
        HttpResponse<String> cleanup = browser.post(URI.create(origin + "/auth/logout"), "",
                Map.of("Origin", origin, "X-CSRF-TOKEN", (String) json(bootstrap).get("token")));
        assertJsonError(cleanup, 409);
        assertNoContextCookie(cleanup);
        assertThat(counts()).isEqualTo(before);
        assertThat(browser.sessionId()).as("Real cleanup removes the incoming SID from this same jar").isEmpty();
        System.out.println("COOKIE_CONTEXT_RECOVERY_SETUP kind=actual_restart private401=true cleanup409=true same_jar=true");
        String newCsrf = assertNewRecoveryBootstrapAndEcho(browser, context, authenticatedSession);
        assertRecoveryLoginRetainsBinding(browser, newCsrf, durable);
    }

    private static String assertNewRecoveryBootstrapAndEcho(Browser browser, String oldContext, String oldSession)
            throws Exception {
        // The same CookieManager performs all delivery and outgoing Cookie selection.
        // No cookie removal, substitution, imported headers, or new Browser is permitted here.
        HttpResponse<String> recovered = browser.get("/auth/csrf");
        assertSingleContextCookie(recovered);
        String newContext = browser.contextId();
        String newSession = browser.sessionId();
        assertThat(!newContext.isEmpty() && !newContext.equals(oldContext))
                .as("Recovery creates a new random Q, never reissues the old value").isTrue();
        assertThat(!newSession.isEmpty() && !newSession.equals(oldSession)).isTrue();
        HttpSession session = probe().sessions.get(newSession);
        assertThat(session).isNotNull();
        assertThat(hasAuthentication(session)).isFalse();
        var stamp = registry().capture(session);
        assertThat(stamp).isNotNull();
        assertThat(stamp.contextId().equals(newContext)).isTrue();
        HttpResponse<String> echo = browser.get("/auth/csrf");
        assertThat(echo.statusCode()).isEqualTo(200);
        assertNoContextCookie(echo);
        assertThat(browser.contextId().equals(newContext) && browser.sessionId().equals(newSession)).isTrue();
        assertThat(registry().echo(newContext, stamp)).isTrue();
        assertThat(registry().capture(session).equals(stamp)).isTrue();
        return (String) json(echo).get("token");
    }

    private void assertRecoveryLoginRetainsBinding(Browser browser, String token, UUID durable) throws Exception {
        String anonymousSession = browser.sessionId();
        String context = browser.contextId();
        var stamp = registry().capture(probe().sessions.get(anonymousSession));
        HttpResponse<String> initiation = loginPost(browser, token, Map.of());
        assertNoContextCookie(initiation);
        HttpResponse<String> callback = browser.get(authorize(browser, initiation));
        assertRedirect(callback, origin + "/account");
        assertNoContextCookie(callback);
        assertThat(browser.sessionId().equals(anonymousSession)).as("Recovery uses real fixation rotation").isFalse();
        assertThat(browser.contextId().equals(context)).isTrue();
        assertThat(registry().capture(probe().sessions.get(browser.sessionId())).equals(stamp)).isTrue();
        assertAccount(browser.get("/api/me"), durable);
        assertThat(bindingId()).isEqualTo(durable);
        assertThat(bindingCount(subject)).isEqualTo(1);
        assertThat(counts()).isEqualTo(before);
        System.out.println("COOKIE_CONTEXT_RECOVERY_RESULT full_oidc=true same_jar=true same_durable_identity=true");
    }

    @Test
    @Timeout(60)
    void cookieContextRecoveryConcurrentBootstrapLateDeliveryCannotRestoreOldAccount() throws Exception {
        Browser jar = browser();
        RecoveryFixture old = prepareActualRestartRecovery(jar);
        int recordsBefore = registry().counts().contexts();
        RecoveryCookieWriteProbe write = new RecoveryCookieWriteProbe(false);
        FAULTS.recoveryCookieWriteProbe = write;
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> held = null;
        try {
            held = executor.submit(() -> jar.get("/auth/csrf", Map.of("X-Cookie-Control", "recovery-bootstrap")));
            assertThat(write.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(write.writes.get()).isEqualTo(1);
            assertThat(write.beforeCommit.get()).isTrue();
            assertThat(jar.sessionId()).as("The held first recovery response has not delivered its new SID").isEmpty();
            assertThat(jar.contextId().equals(old.context())).isTrue();
            String firstNewContext = write.context.get();
            String firstNewSession = write.session.get().getId();
            assertThat(firstNewContext.equals(old.context())).isFalse();
            assertThat(hasAuthentication(write.session.get())).isFalse();
            assertThat(registry().counts().contexts()).isEqualTo(recordsBefore + 1);

            // A second ordinary request from the very same CookieManager still has no
            // SID. It may create an independent new anonymous context, not reuse the first.
            HttpResponse<String> second = jar.get("/auth/csrf");
            assertSingleContextCookie(second);
            String contextB = jar.contextId();
            assertThat(contextB.equals(old.context()) || contextB.equals(firstNewContext)).isFalse();
            assertThat(jar.sessionId().equals(firstNewSession)).isFalse();
            assertThat(registry().counts().contexts()).isEqualTo(recordsBefore + 2);
            HttpResponse<String> echoB = jar.get("/auth/csrf");
            assertNoContextCookie(echoB);
            String next = nextSubject();
            provider.scenario(Scenario.verified(next));
            HttpResponse<String> initiation = loginPost(jar, (String) json(echoB).get("token"), Map.of());
            assertNoContextCookie(initiation);
            HttpResponse<String> callbackB = jar.get(authorize(jar, initiation));
            assertRedirect(callbackB, origin + "/account");
            assertNoContextCookie(callbackB);
            UUID userB = bindingId(next);
            assertAccount(jar.get("/api/me"), userB);
            assertThat(held.isDone()).isFalse();
            assertThat(hasAuthentication(write.session.get())).isFalse();
            assertThat(registry().finalAdmit(firstNewContext, write.stamp.get())).isFalse();

            write.release.countDown();
            HttpResponse<String> late = held.get(30, TimeUnit.SECONDS);
            assertSingleContextCookie(late);
            assertThat(jar.contextId().equals(firstNewContext)).isTrue();
            assertThat(jar.sessionId().equals(firstNewSession)).isTrue();
            assertJsonError(jar.get("/api/me"), 401);
            assertNoContextCookie(jar.get("/auth/csrf"));
            assertThat(registry().capture(write.session.get()).equals(write.stamp.get())).isTrue();
            assertThat(write.writes.get()).isEqualTo(1);
            assertThat(bindingId()).isEqualTo(old.durable());
            assertThat(counts()).isEqualTo(before);

            assertNoContextCookie(logout(jar));
            HttpResponse<String> recovery = jar.get(begin(jar));
            assertRedirect(recovery, origin + "/account");
            assertNoContextCookie(recovery);
            assertAccount(jar.get("/api/me"), userB);
            assertThat(bindingId(next)).isEqualTo(userB);
            assertThat(bindingCount(subject)).isEqualTo(1);
            assertThat(bindingCount(next)).isEqualTo(1);
            assertThat(counts()).isEqualTo(before);
            System.out.println("COOKIE_CONTEXT_RECOVERY_CONCURRENCY independent_anonymous_contexts=2"
                    + " late_response_after_B=401 same_jar_recovery=true browser_proof=NOT_RUN");
        }
        finally {
            write.release.countDown();
            try { finishWorkers(executor, held); }
            finally { FAULTS.recoveryCookieWriteProbe = null; }
        }
    }

    @Test
    @Timeout(60)
    void cookieContextRecoveryPartialIssuanceFailureDoesNotReissueOrAdoptTaggedSession() throws Exception {
        Browser jar = browser();
        RecoveryFixture old = prepareActualRestartRecovery(jar);
        var resourcesBefore = registry().counts();
        RecoveryCookieWriteProbe write = new RecoveryCookieWriteProbe(true);
        FAULTS.recoveryCookieWriteProbe = write;
        try {
            HttpResponse<String> failed = jar.get("/auth/csrf", Map.of("X-Cookie-Control", "recovery-bootstrap"));
            assertJsonError(failed, 503);
            assertNoContextCookie(failed);
            assertThat(write.writes.get()).isEqualTo(1);
            assertThat(write.injectedFailure.get()).isTrue();
            assertThat(write.beforeCommit.get()).isTrue();
            assertThat(failed.headers().allValues("Set-Cookie").stream().anyMatch(
                    AuthenticationHttpIntegrationTest::isHostOnlyRootSessionCookie))
                    .as("The actual container's pending new SID reached the partial failure response").isTrue();
            assertThat(jar.sessionId().equals(write.session.get().getId())).isTrue();
            assertThat(jar.contextId().equals(old.context())).isTrue();
            assertThat(write.context.get().equals(old.context())).isFalse();
            assertThat(registry().counts().contexts()).isEqualTo(resourcesBefore.contexts() + 1);
            assertThat(registry().counts().references()).isEqualTo(resourcesBefore.references() + 1);
            assertThat(registry().capture(write.session.get()).equals(write.stamp.get())).isTrue();
            assertThat(hasAuthentication(write.session.get())).isFalse();
            assertThat(registry().finalAdmit(write.context.get(), write.stamp.get())).isFalse();

            HttpResponse<String> repeated = jar.get("/auth/csrf");
            assertThat(repeated.statusCode()).isEqualTo(200);
            assertNoContextCookie(repeated);
            assertThat(jar.contextId().equals(old.context())).isTrue();
            assertThat(jar.sessionId().equals(write.session.get().getId())).isTrue();
            assertThat(registry().capture(write.session.get()).equals(write.stamp.get())).isTrue();
            assertThat(registry().counts().contexts()).isEqualTo(resourcesBefore.contexts() + 1);
            assertThat(registry().counts().references()).isEqualTo(resourcesBefore.references() + 1);
            String token = (String) json(repeated).get("token");
            HttpResponse<String> denied = loginPost(jar, token, Map.of());
            assertJsonError(denied, 409);
            assertNoContextCookie(denied);
            assertJsonError(jar.get("/api/me"), 401);
            assertThat(counts()).isEqualTo(before);

            HttpResponse<String> cleanup = jar.post(URI.create(origin + "/auth/logout"), "", Map.of(
                    "Origin", origin, "X-CSRF-TOKEN", token));
            assertJsonError(cleanup, 409);
            assertNoContextCookie(cleanup);
            assertThat(jar.sessionId()).isEmpty();
            String newCsrf = assertNewRecoveryBootstrapAndEcho(jar, old.context(), old.session());
            assertThat(jar.contextId().equals(write.context.get())).as("Failure does not permit reissue of its consumed Q").isFalse();
            assertRecoveryLoginRetainsBinding(jar, newCsrf, old.durable());
            assertThat(write.writes.get()).isEqualTo(1);
            System.out.println("COOKIE_CONTEXT_RECOVERY_PARTIAL_FAILURE injected_cookie_write_failure=true"
                    + " genuine_pending_SID_delivered=true consumed_Q_not_reissued=true browser_proof=NOT_RUN");
        }
        finally {
            write.release.countDown();
            FAULTS.recoveryCookieWriteProbe = null;
        }
    }

    private RecoveryFixture prepareActualRestartRecovery(Browser jar) throws Exception {
        complete(jar);
        RecoveryFixture old = new RecoveryFixture(jar.contextId(), jar.sessionId(), bindingId());
        application.close();
        startBackend();
        verifyOwnedListenerBindings();
        jdbc = application.getBean(JdbcTemplate.class);
        assertJsonError(jar.get("/api/me"), 401);
        HttpResponse<String> csrf = jar.get("/auth/csrf");
        assertThat(csrf.statusCode()).isEqualTo(200);
        assertNoContextCookie(csrf);
        assertThat(jar.contextId().equals(old.context())).isTrue();
        HttpResponse<String> cleanup = jar.post(URI.create(origin + "/auth/logout"), "", Map.of(
                "Origin", origin, "X-CSRF-TOKEN", (String) json(csrf).get("token")));
        assertJsonError(cleanup, 409);
        assertNoContextCookie(cleanup);
        assertThat(jar.sessionId()).isEmpty();
        assertThat(counts()).isEqualTo(before);
        return old;
    }

    private record RecoveryFixture(String context, String session, UUID durable) {
        @Override public String toString() { return "RecoveryFixture[redacted]"; }
    }

    @RepeatedTest(3)
    @Timeout(60)
    void cookieContextAtomicInitialPairingIssuesOnlyOneQ() throws Exception {
        Browser browser = browser();
        assertThat(browser.get("/actuator/health", Map.of("X-Cookie-Control", "create-untagged")).statusCode()).isEqualTo(200);
        String session = browser.sessionId();
        assertThat(session).isNotEmpty();
        assertThat(registry().capture(probe().sessions.get(session))).isNull();
        InitialPairPause pause = new InitialPairPause();
        FAULTS.initialPairPause = pause;
        var executor = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> a = null;
        Future<HttpResponse<String>> b = null;
        try {
            a = executor.submit(() -> browser.exactRequest("GET", "/auth/csrf", "",
                    Map.of("X-Cookie-Control", "pair-race"), "JSESSIONID=" + session));
            b = executor.submit(() -> browser.exactRequest("GET", "/auth/csrf", "",
                    Map.of("X-Cookie-Control", "pair-race"), "JSESSIONID=" + session));
            assertThat(pause.entered.await(10, TimeUnit.SECONDS)).isTrue();
            pause.release.countDown();
            HttpResponse<String> first = a.get(30, TimeUnit.SECONDS);
            HttpResponse<String> second = b.get(30, TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(200);
            List<String> issued = Stream.of(first, second).flatMap(response -> response.headers().allValues("Set-Cookie").stream())
                    .filter(header -> header.startsWith(BrowserSessionContextRegistry.COOKIE_NAME + "=")).toList();
            assertThat(issued.size()).as("One untagged real session has exactly one first-issuance winner").isEqualTo(1);
            String context = HttpCookie.parse(issued.getFirst()).getFirst().getValue();
            var stamp = registry().capture(probe().sessions.get(session));
            assertThat(stamp).isNotNull();
            assertThat(registry().echo(context, stamp)).isTrue();
            assertNoContextCookie(browser.exactRequest("GET", "/auth/csrf", "", Map.of(), "JSESSIONID=" + session));
            assertThat(registry().capture(probe().sessions.get(session)).equals(stamp)).isTrue();
            assertThat(counts()).isEqualTo(before);
        }
        finally {
            pause.release.countDown();
            try { finishWorkers(executor, a, b); }
            finally { FAULTS.initialPairPause = null; }
        }
    }

    private static BrowserSessionContextRegistry registry() {
        return application.getBean(BrowserSessionContextRegistry.class);
    }

    private static void assertAccount(HttpResponse<String> response, UUID user) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).containsExactlyInAnyOrderEntriesOf(Map.of("id", user.toString(), "status", "ACTIVE"));
        assertNoContextCookie(response);
    }

    private static void assertNoContextCookie(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Set-Cookie").stream()
                .noneMatch(header -> header.startsWith(BrowserSessionContextRegistry.COOKIE_NAME + "=")))
                .as("No setting or deleting Q cookie outside the unique initial issuance").isTrue();
    }

    private static void assertSingleContextCookie(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> headers = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(BrowserSessionContextRegistry.COOKIE_NAME + "=")).toList();
        assertThat(headers.size()).isEqualTo(1);
        HttpCookie cookie = HttpCookie.parse(headers.getFirst()).getFirst();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
        assertThat(headers.getFirst().contains("SameSite=Lax")).isTrue();
        assertThat(cookie.getSecure()).isFalse();
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
            assertThat(bindingCount(subject)).isEqualTo(1);
            assertThat(bindingCount(thirdSubject)).isEqualTo(1);
            assertThat(counts()).isEqualTo(before);
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
        String next = "synthetic|pilot-" + NEXT_SUBJECT.getAndIncrement();
        assertThat(PILOT_SUBJECTS.contains(next)).isTrue();
        return next;
    }

    @Test
    void cognitoIdTokenOnlyPreservesOwnerAndRejectsTheUnselectedRegistration() throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            provider.scenario(cognitoScenario().userInfoClaim("email_verified", "true")
                    .userInfoClaim("sub", "must-not-be-read"));
            Browser browser = browser();
            String priorCsrf = csrf(browser);
            String priorSid = browser.sessionId();
            complete(browser);
            assertThat(json(browser.get("/api/me"))).containsEntry("id", owner.toString()).containsEntry("status", "ACTIVE");
            assertThat(browser.sessionId()).isNotEqualTo(priorSid);
            assertThat(csrf(browser)).isNotEqualTo(priorCsrf);
            assertThat(provider.userInfoRequests()).isZero();
            assertThat(provider.tokenRequests()).hasSize(1);
            assertThat(provider.authorizationRequests().getFirst()).containsEntry("prompt", "login")
                    .containsEntry("max_age", "0").containsEntry("code_challenge_method", "S256")
                    .containsEntry("redirect_uri", origin + "/login/oauth2/code/cognito");
            assertThat(provider.tokenRequests().getFirst()).containsEntry("redirect_uri", origin + "/login/oauth2/code/cognito");
            assertJsonError(browser.get("/oauth2/authorization/auth0"), 403);
            assertJsonError(browser.get("/login/oauth2/code/auth0?code=unused&state=unused"), 403);
            assertThat(json(browser.get("/api/me"))).containsEntry("id", owner.toString());
            assertThat(provider.tokenRequests()).hasSize(1);
            assertThat(counts()).isEqualTo(before);
            logout(browser);
            assertJsonError(browser.get("/api/me"), 401);
            complete(browser);
            assertThat(json(browser.get("/api/me"))).containsEntry("id", owner.toString());
            assertThat(counts()).isEqualTo(before);
            assertThat(provider.userInfoRequests()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cognitoUnknownOrUnadmittedPairCannotCreateAnInternalAccount(boolean unadmitted) throws Exception {
        withCognito(() -> {
            Scenario attempt = Scenario.verified(MISSING_OWNER);
            if (unadmitted) attempt = Scenario.verified("opaque-unadmitted-owner").idClaim("token_use", "id");
            assertRejectedWithoutAccount(attempt);
            assertThat(provider.tokenRequests()).hasSize(1);
            assertThat(provider.userInfoRequests()).isZero();
            assertThat(counts()).isEqualTo(before);
        });
    }

    static Stream<String> cognitoFailures() {
        return Stream.of("token-use-missing", "token-use-access", "token-use-number", "verified-string",
                "verified-false", "verified-missing", "email-blank", "email-number", "email-missing",
                "time-string", "time-fraction", "time-missing", "time-stale", "time-future",
                "signature", "key", "issuer", "audience", "nonce", "state", "token");
    }

    @ParameterizedTest
    @MethodSource("cognitoFailures")
    void cognitoSignedClaimAndProtocolRefusalHasNoSideEffectsWithAValidOwnerControl(String fault) throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            long now = Instant.now().getEpochSecond();
            Scenario valid = cognitoScenario();
            Scenario invalid = switch (fault) {
                case "token-use-missing" -> valid.withoutIdClaim("token_use");
                case "token-use-access" -> valid.idClaim("token_use", "access");
                case "token-use-number" -> valid.idClaim("token_use", 1);
                case "verified-string" -> valid.idClaim("email_verified", "true");
                case "verified-false" -> valid.idClaim("email_verified", false);
                case "verified-missing" -> valid.withoutIdClaim("email_verified");
                case "email-blank" -> valid.idClaim("email", " ");
                case "email-number" -> valid.idClaim("email", 1);
                case "email-missing" -> valid.withoutIdClaim("email");
                case "time-string" -> valid.idClaim("auth_time", Long.toString(now));
                case "time-fraction" -> valid.idClaim("auth_time", now + 0.5);
                case "time-missing" -> valid.withoutIdClaim("auth_time");
                case "time-stale" -> valid.idClaim("auth_time", now - 120);
                case "time-future" -> valid.idClaim("auth_time", now + 120);
                case "signature" -> valid.badSignature();
                case "key" -> valid.unknownKey();
                case "issuer" -> valid.idClaim("iss", "https://wrong.example.test/");
                case "audience" -> valid.idClaim("aud", "other-client");
                case "nonce" -> valid.idClaim("nonce", "other-nonce");
                case "state" -> valid.badState();
                case "token" -> valid.tokenFailure();
                default -> throw new AssertionError(fault);
            };
            assertRejectedWithoutAccount(invalid);
            assertThat(provider.userInfoRequests()).isZero();
            provider.scenario(valid);
            Browser control = browser();
            complete(control);
            assertThat(json(control.get("/api/me"))).containsEntry("id", owner.toString());
            assertThat(counts()).isEqualTo(before);
            assertThat(provider.userInfoRequests()).isZero();
        });
    }

    @Test
    void cognitoDecodedRoutesCallbackReplayAndCsrfKeepTheOriginalSessionBoundary() throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            provider.scenario(cognitoScenario());
            Browser browser = browser();
            assertJsonError(browser.post(URI.create(origin + "/auth/login"), "", Map.of("Origin", origin)), 403);
            assertRedirect(loginPost(browser, csrf(browser), Map.of()), origin + "/oauth2/authorization/cognito");
            HttpResponse<String> entry = browser.get("/%6fauth2/authorization/%63ognito");
            assertThat(entry.statusCode()).isIn(302, 303);
            URI callback = URI.create(location(browser.get(URI.create(location(entry)))));
            URI encoded = URI.create(callback.toString().replace("/login/oauth2/code/cognito?", "/login/%6fauth2/code/%63ognito?"));
            assertRedirect(browser.get(encoded), origin + "/account");
            assertThat(json(browser.get("/api/me"))).containsEntry("id", owner.toString());
            assertRedirect(browser.get(callback), origin + "/login?auth=failed");
            assertThat(json(browser.get("/api/me"))).containsEntry("id", owner.toString());
            assertJsonError(browser.get("/oauth2/authorization/cognito"), 409);
            assertThat(provider.tokenRequests()).hasSize(1);
            assertThat(provider.userInfoRequests()).isZero();
        });
    }

    @Test
    void cognitoLogoutDuringTokenWaitRejectsOnlyThePendingAttemptAndPreservesDurableOwner() throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            provider.scenario(cognitoScenario());
            Browser browser = browser();
            URI callback = begin(browser);
            var executor = Executors.newSingleThreadExecutor();
            Future<HttpResponse<String>> pending = null;
            try (var gate = provider.holdTokenResponse(subject)) {
                pending = executor.submit(() -> browser.get(callback));
                assertThat(gate.awaitEntered(5, TimeUnit.SECONDS)).isTrue();
                logout(browser);
                assertThat(pending.isDone()).isFalse();
                gate.close();
                HttpResponse<String> late = pending.get(10, TimeUnit.SECONDS);
                assertRedirect(late, origin + "/login?auth=failed");
                assertThat(late.headers().allValues("Set-Cookie")).isEmpty();
                assertJsonError(browser.get("/api/me"), 401);
                assertThat(bindingId()).isEqualTo(owner);
                assertThat(counts()).isEqualTo(before);
                complete(browser);
                assertThat(json(browser.get("/api/me"))).containsEntry("id", owner.toString());
                assertThat(provider.userInfoRequests()).isZero();
            } finally {
                finishWorkers(executor, pending);
            }
        });
    }

    @Test
    void cognitoCancelledNativeRotationKeepsLateFailureCookieCorrection() throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            provider.scenario(cognitoScenario());
            Browser browser = browser();
            URI callback = begin(browser);
            HttpSession original = probe().sessions.get(browser.sessionId());
            RotationPause pause = new RotationPause(original, true);
            FAULTS.rotationPause = pause;
            var executor = Executors.newSingleThreadExecutor();
            Future<HttpResponse<String>> pending = null;
            try {
                pending = executor.submit(() -> browser.get(callback));
                assertThat(pause.rotated.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pause.fixtureFailure.get()).isNull();
                assertThat(hasAuthorizedClient(original)).isTrue();
                original.invalidate();
                assertThat(pending.isDone()).isFalse();
                pause.release.countDown();
                HttpResponse<String> late = pending.get(10, TimeUnit.SECONDS);
                assertRedirect(late, origin + "/login?auth=failed");
                assertThat(pause.actualPendingRotationCookie.get()).isTrue();
                assertThat(pause.uncommittedAfterRotation.get()).isTrue();
                assertThat(late.headers().allValues("Set-Cookie")).containsExactlyInAnyOrderElementsOf(RotationPause.UNRELATED_COOKIES);
                assertThat(late.headers().allValues("Set-Cookie").stream().noneMatch(AuthenticationHttpIntegrationTest::isHostOnlyRootSessionCookie)).isTrue();
                assertThat(bindingId()).isEqualTo(owner);
                assertThat(counts()).isEqualTo(before);
                assertThat(provider.userInfoRequests()).isZero();
            } finally {
                pause.release.countDown();
                try { finishWorkers(executor, pending); }
                finally { FAULTS.rotationPause = null; }
            }
        });
    }

    private Scenario cognitoScenario() { return Scenario.verified(subject).idClaim("token_use", "id"); }

    @Test
    void cognitoEmailConfirmationIsProviderOnlyAndFreshOidcStillControlsOwnerAdmission(CapturedOutput logs) throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            Browser browser = browser();
            String token = csrf(browser);
            String sid = browser.sessionId();
            HttpSession session = probe().sessions.get(sid);
            var attributes = sessionAttributes(session);
            var stamp = registry().capture(session);
            var durable = confirmationDurableSnapshot();
            String email = "Recovery.Mixed@example.test";
            String code = "private-confirmation-code";
            HttpResponse<String> confirmed = browser.jsonPost("/auth/confirm-email",
                    "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}",
                    Map.of("Origin", origin, "X-CSRF-TOKEN", token));
            assertThat(confirmed.statusCode()).isEqualTo(200);
            assertThat(json(confirmed)).containsExactlyEntriesOf(Map.of("result", "confirmed"));
            assertThat(confirmed.headers().allValues("Set-Cookie")).isEmpty();
            HttpResponse<String> resent = browser.jsonPost("/auth/resend-confirmation-code",
                    "{\"email\":\"" + email + "\"}", Map.of("Origin", origin, "X-CSRF-TOKEN", token));
            assertThat(resent.statusCode()).isEqualTo(202);
            assertThat(json(resent)).containsExactlyEntriesOf(Map.of("result", "code_request_accepted"));
            assertThat(resent.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(browser.sessionId().equals(sid)).isTrue();
            assertThat(sessionAttributes(session).equals(attributes)).isTrue();
            assertThat(registry().capture(session).equals(stamp)).isTrue();
            assertThat(hasAuthentication(session) || hasAuthorizedClient(session)).isFalse();
            assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            assertThat(CONFIRMATION.calls).hasValue(2);
            assertThat(provider.tokenRequests()).isEmpty();
            assertJsonError(browser.get("/api/me"), 401);
            assertJsonError(browser.get("/oauth2/authorization/cognito"), 403);

            // Confirmation is not signed-token verification, login intent, admission, or a principal.
            provider.scenario(cognitoScenario().idClaim("email_verified", false));
            assertRedirect(browser.get(begin(browser)), origin + "/login?auth=failed");
            assertJsonError(browser.get("/api/me"), 401);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            provider.scenario(cognitoScenario());
            complete(browser);
            assertAccount(browser.get("/api/me"), owner);
            assertThat(counts()).isEqualTo(before);
            assertThat(provider.tokenRequests()).hasSize(2);
            assertThat(provider.userInfoRequests()).isZero();
            assertThat(Stream.of(email, code, CLIENT_SECRET, token, sid).noneMatch(logs.getAll()::contains)).isTrue();
            assertThat(logs.getAll().contains("event=EMAIL_CONFIRMATION_RESULT reason=EMAIL_CONFIRMED")).isTrue();
            assertThat(logs.getAll().contains("event=EMAIL_CONFIRMATION_RESULT reason=CONFIRMATION_CODE_REQUESTED")).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cognitoEmailConfirmationUnknownOrExpiredQWorksWithValidCsrfButCannotStartLogin(boolean expired) throws Exception {
        withCognito(() -> {
            Browser browser = browser();
            String token = csrf(browser);
            String sid = browser.sessionId();
            HttpSession session = probe().sessions.get(sid);
            var attributes = sessionAttributes(session);
            var durable = confirmationDurableSnapshot();
            String q = expired ? browser.contextId() : "unknown-context";
            if (expired) CLOCK.advance(Duration.ofHours(9));
            String cookies = "JSESSIONID=" + sid + "; " + BrowserSessionContextRegistry.COOKIE_NAME + "=" + q;
            HttpResponse<String> response = browser.jsonExactPost("/auth/confirm-email",
                    "{\"email\":\"Unknown.Q@example.test\",\"code\":\"123456\"}",
                    Map.of("Origin", origin, "X-CSRF-TOKEN", token), cookies);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response)).containsExactlyEntriesOf(Map.of("result", "confirmed"));
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(sessionAttributes(session).equals(attributes)).isTrue();
            assertThat(hasAuthentication(session) || hasAuthorizedClient(session)).isFalse();
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            assertJsonError(browser.exactRequest("GET", "/api/me", "", Map.of(), cookies), 401);
            assertJsonError(browser.exactRequest("POST", "/auth/login", "_csrf=" + encode(token),
                    Map.of("Origin", origin), cookies), 409);
            assertJsonError(browser.exactRequest("GET", "/oauth2/authorization/cognito", "", Map.of(), cookies), 403);
            assertThat(CONFIRMATION.calls).hasValue(1);
            assertThat(provider.tokenRequests()).isEmpty();
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/confirm-email", "/auth/resend-confirmation-code", "/%61uth/%63onfirm-email", "/auth/%72esend-confirmation-code"})
    void cognitoEmailConfirmationWrongHostOriginCsrfAndMethodNeverReachProvider(String path) throws Exception {
        withCognito(() -> {
            Browser browser = browser();
            String token = csrf(browser);
            var durable = confirmationDurableSnapshot();
            String body = path.contains("confirm-email") || path.contains("%63onfirm-email")
                    ? "{\"email\":\"a@b\",\"code\":\"123456\"}" : "{\"email\":\"a@b\"}";
            for (Map<String, String> headers : List.of(Map.of("X-CSRF-TOKEN", token),
                    Map.of("Origin", "https://attacker.test", "X-CSRF-TOKEN", token),
                    Map.of("Origin", "null", "X-CSRF-TOKEN", token), Map.of("Origin", origin),
                    Map.of("Origin", origin, "X-CSRF-TOKEN", "wrong-token"))) {
                assertJsonError(browser.jsonPost(path, body, headers), 403);
            }
            assertJsonError(browser.get(path), 403);
            String raw = rawRequest("POST " + path + " HTTP/1.1\r\nHost: attacker.test\r\nOrigin: " + origin
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body);
            assertThat(raw).startsWith("HTTP/1.1 403");
            assertThat(CONFIRMATION.calls).hasValue(0);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            // Correct Host/Origin/CSRF on the same exact or decoded path is the positive control.
            HttpResponse<String> allowed = browser.jsonPost(path, body, Map.of("Origin", origin, "X-CSRF-TOKEN", token));
            assertThat(allowed.statusCode()).isEqualTo(body.contains("code") ? 200 : 202);
            assertThat(CONFIRMATION.calls).hasValue(1);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
        });
    }

    @Test
    void cognitoEmailConfirmationAuthenticatedRefusalPrecedesCsrfAndRetainsExactPrincipalClientAndSession() throws Exception {
        withCognito(() -> {
            UUID owner = prepareCognitoOwner();
            Browser browser = browser();
            complete(browser);
            String token = csrf(browser);
            String sid = browser.sessionId();
            HttpSession session = probe().sessions.get(sid);
            OAuth2AuthorizedClient client = authorizedClient(session);
            var attributes = sessionAttributes(session);
            var durable = confirmationDurableSnapshot();
            for (String path : List.of("/auth/confirm-email", "/auth/resend-confirmation-code")) {
                for (Map<String, String> headers : List.of(Map.of("Origin", origin),
                        Map.of("Origin", origin, "X-CSRF-TOKEN", "wrong"),
                        Map.of("Origin", origin, "X-CSRF-TOKEN", token))) {
                    HttpResponse<String> denied = browser.jsonPost(path, "{}", headers);
                    assertJsonError(denied, 403);
                    assertThat(denied.headers().allValues("Set-Cookie")).isEmpty();
                    assertThat(browser.sessionId().equals(sid)).isTrue();
                    assertThat(sessionAttributes(session).equals(attributes)).isTrue();
                    assertSessionIdentity(browser, owner, client);
                }
            }
            assertThat(CONFIRMATION.calls).hasValue(0);
            assertThat(provider.tokenRequests()).hasSize(1);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            logout(browser);
            assertJsonError(browser.get("/api/me"), 401);
        });
    }

    @Test
    void legacyRoutesNeverStartAuthenticationOrConfirmationAndCognitoStillWorks() throws Exception {
        Browser browser = browser();
        String token = csrf(browser);
        var durable = confirmationDurableSnapshot();
        for (String path : List.of("/oauth2/authorization/auth0", "/login/oauth2/code/auth0",
                "/oauth2/authorization/%61uth0", "/login/oauth2/code/%61uth0")) {
            assertJsonError(browser.post(URI.create(origin + path), "", Map.of("Origin", origin, "X-CSRF-TOKEN", token)), 403);
            assertJsonError(browser.get(path), 403);
        }
        assertThat(CONFIRMATION.calls).hasValue(0);
        assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
        complete(browser);
        assertAccount(browser.get("/api/me"), bindingId());
        assertThat(provider.userInfoRequests()).isZero();
        assertThat(counts()).isEqualTo(before);
        logout(browser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"email\":\"a@b\",\"code\":1}", "{\"email\":\"a@b\",\"code\":\"1\",\"clientId\":\"caller\"}",
            "{\"email\":\"a@b\",\"code\":\"1\",\"email\":\"other@b\"}", "{\"email\":\"a@b\",\"code\":\"1\"} {}"})
    void cognitoEmailConfirmationMalformedJsonIsOpaqueAndNeverSubmits(String body) throws Exception {
        withCognito(() -> {
            Browser browser = browser();
            String token = csrf(browser);
            var durable = confirmationDurableSnapshot();
            HttpResponse<String> denied = browser.jsonPost("/auth/confirm-email", body, Map.of("Origin", origin, "X-CSRF-TOKEN", token));
            assertJsonError(denied, 400);
            assertThat(denied.body()).isEqualTo("{\"error\":\"request_not_completed\"}");
            assertThat(CONFIRMATION.calls).hasValue(0);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
        });
    }

    @Test
    void cognitoEmailConfirmationActualBodyBytesAreCappedForDeclaredAndChunkedRequests() throws Exception {
        withCognito(() -> {
            Browser browser = browser();
            String token = csrf(browser);
            var durable = confirmationDurableSnapshot();
            String oversized = "{\"email\":\"a@b\",\"code\":\"1\"}" + " ".repeat(CognitoEmailConfirmationService.MAX_BYTES);
            Map<String, String> headers = Map.of("Origin", origin, "X-CSRF-TOKEN", token);
            assertJsonError(browser.jsonPost("/auth/confirm-email", oversized, headers), 400);
            assertJsonError(browser.jsonChunkedPost("/auth/confirm-email", oversized, headers), 400);
            assertJsonError(browser.post(URI.create(origin + "/auth/confirm-email"), "email=a%40b&code=1", headers), 400);
            assertThat(CONFIRMATION.calls).hasValue(0);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
        });
    }

    @Test
    void cognitoEmailConfirmationResendHidesRecipientStateAndTechnicalOutcomeRemainsUnknown(CapturedOutput logs) throws Exception {
        withCognito(() -> {
            Browser browser = browser();
            String token = csrf(browser);
            var durable = confirmationDurableSnapshot();
            int index = 0;
            for (String error : List.of("UserNotFoundException", "NotAuthorizedException", "CodeMismatchException",
                    "ExpiredCodeException", "AliasExistsException", "InvalidParameterException", "TooManyFailedAttemptsException",
                    "TooManyRequestsException", "LimitExceededException")) {
                String email = "state" + index++ + "@example.test";
                CONFIRMATION.next = new CognitoEmailConfirmationService.ProviderResponse(400,
                        ("{\"__type\":\"" + error + "\",\"message\":\"private-provider-recipient-detail\"}").getBytes(StandardCharsets.UTF_8));
                HttpResponse<String> confirm = browser.jsonPost("/auth/confirm-email", "{\"email\":\"" + email + "\",\"code\":\"123456\"}",
                        Map.of("Origin", origin, "X-CSRF-TOKEN", token));
                assertJsonError(confirm, 400);
                assertThat(confirm.body()).isEqualTo("{\"error\":\"request_not_completed\"}");
                HttpResponse<String> resend = browser.jsonPost("/auth/resend-confirmation-code", "{\"email\":\"" + email + "\"}",
                        Map.of("Origin", origin, "X-CSRF-TOKEN", token));
                assertThat(resend.statusCode()).isEqualTo(202);
                assertThat(json(resend)).containsExactlyEntriesOf(Map.of("result", "code_request_accepted"));
            }
            CONFIRMATION.fail = true;
            HttpResponse<String> unknown = browser.jsonPost("/auth/resend-confirmation-code", "{\"email\":\"technical@example.test\"}",
                    Map.of("Origin", origin, "X-CSRF-TOKEN", token));
            assertJsonError(unknown, 503);
            assertThat(unknown.body()).isEqualTo("{\"error\":\"request_not_completed\"}");
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            assertThat(logs.getAll().contains("private-provider-recipient-detail") || logs.getAll().contains("private-provider-transport-detail")).isFalse();
            assertThat(logs.getAll().contains("event=EMAIL_CONFIRMATION_RESULT reason=PROVIDER_REQUEST_UNAVAILABLE")).isTrue();
        });
    }

    @Test
    void cognitoEmailConfirmationCookieResetCannotResetTargetThrottle(CapturedOutput logs) throws Exception {
        withCognito(() -> {
            Browser first = browser();
            String token = csrf(first);
            String body = "{\"email\":\"throttled@example.test\",\"code\":\"123456\"}";
            var durable = confirmationDurableSnapshot();
            for (int index = 0; index < CognitoEmailConfirmationService.TARGET_LIMIT; index++) {
                assertThat(first.jsonPost("/auth/confirm-email", body, Map.of("Origin", origin, "X-CSRF-TOKEN", token)).statusCode()).isEqualTo(200);
            }
            assertJsonError(first.jsonPost("/auth/confirm-email", body, Map.of("Origin", origin, "X-CSRF-TOKEN", token)), 429);
            Browser replacement = browser();
            assertJsonError(replacement.jsonPost("/auth/confirm-email", body,
                    Map.of("Origin", origin, "X-CSRF-TOKEN", csrf(replacement))), 429);
            assertJsonError(replacement.jsonPost("/auth/confirm-email", body.replace("throttled@", "THROTTLED@"),
                    Map.of("Origin", origin, "X-CSRF-TOKEN", csrf(replacement))), 429);
            assertThat(CONFIRMATION.calls).hasValue(3);
            assertThat(confirmationDurableSnapshot().equals(durable)).isTrue();
            assertThat(logs.getAll().contains("event=EMAIL_CONFIRMATION_RESULT reason=CONFIRMATION_RATE_LIMITED")).isTrue();
        });
    }

    private static Map<String, Object> sessionAttributes(HttpSession session) {
        var attributes = new LinkedHashMap<String, Object>();
        Collections.list(session.getAttributeNames()).forEach(name -> attributes.put(name, session.getAttribute(name)));
        return attributes;
    }

    private Map<String, String> confirmationDurableSnapshot() {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : List.of("users", "user_profiles", "user_identity_bindings", "user_identity_current",
                "user_identity_migration_receipts", "workspaces", "workspace_memberships", "platform_access_grants",
                "platform_access_grant_targets", "platform_access_operation_intents", "platform_access_audit_events",
                "platform_access_operation_outcomes")) {
            String hash = jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(t)::text, ',' ORDER BY to_jsonb(t)::text)) FROM " + table + " t", String.class);
            snapshot.put(table, hash == null ? "EMPTY" : hash);
        }
        return snapshot;
    }

    private UUID prepareCognitoOwner() {
        UUID owner = FIXTURE_OWNERS.get(subject);
        assertThat(owner).isNotNull().isEqualTo(bindingId());
        assertThat(counts()).isEqualTo(before);
        return owner;
    }

    @FunctionalInterface
    private interface CognitoWork { void run() throws Exception; }

    private void withCognito(CognitoWork work) throws Exception {
        assertThat(providerMode).isEqualTo(AuthenticationProperties.Provider.COGNITO);
        provider.scenario(cognitoScenario());
        work.run();
        assertThat(provider.failure()).isNull();
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
        assertRedirect(initiation, origin + "/oauth2/authorization/" + AuthenticationProperties.REGISTRATION_ID);
        HttpResponse<String> authorization = browser.get(URI.create(location(initiation)));
        assertThat(authorization.statusCode()).isIn(302, 303);
        URI providerLocation = URI.create(location(authorization));
        assertThat(providerLocation.getScheme() + "://" + providerLocation.getRawAuthority())
                .isEqualTo(provider.issuer().getScheme() + "://" + provider.issuer().getRawAuthority());
        assertThat(providerLocation.getPath()).isEqualTo("/authorize");
        HttpResponse<String> issuedCode = browser.get(providerLocation);
        assertThat(issuedCode.statusCode()).isEqualTo(302);
        URI callback = URI.create(location(issuedCode));
        assertThat(callback.toString()).startsWith(origin + "/login/oauth2/code/" + AuthenticationProperties.REGISTRATION_ID + "?");
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
                "JSESSIONID", "synthetic|", "SQLSTATE", "SQLException", "org.springframework", "<html", "<form");
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
        private final HttpClient exactClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> get(String path) throws Exception { return get(path, Map.of()); }
        HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
            return request("GET", URI.create(origin + path), "", headers);
        }
        HttpResponse<String> get(URI uri) throws Exception { return request("GET", uri, "", Map.of()); }
        HttpResponse<String> post(URI uri, String body, Map<String, String> headers) throws Exception {
            return request("POST", uri, body, headers);
        }
        HttpResponse<String> jsonPost(String path, String body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json");
            headers.forEach(request::header);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> jsonExactPost(String path, String body, Map<String, String> headers, String cookie) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json").header("Cookie", cookie);
            headers.forEach(request::header);
            return exactClient.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> jsonChunkedPost(String path, String body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json");
            headers.forEach(request::header);
            return client.send(request.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
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
        String contextId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(BrowserSessionContextRegistry.COOKIE_NAME))
                    .map(HttpCookie::getValue).findFirst().orElse("");
        }
        java.util.concurrent.CompletableFuture<HttpResponse<String>> cookieFreeGetAsync(String path) {
            return exactClient.sendAsync(HttpRequest.newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        void applyHeaders(HttpResponse<String> response) throws IOException {
            // Only the actual, original response headers are applied, once per held response.
            // Explicit delivery ordering is a Java HTTP control, never a browser claim.
            cookies.put(response.uri(), response.headers().map());
        }
        HttpResponse<String> exactRequest(String method, String path, String body,
                Map<String, String> headers, String cookieHeader) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25));
            headers.forEach(request::header);
            if (cookieHeader != null && !cookieHeader.isEmpty()) request.header("Cookie", cookieHeader);
            if (method.equals("POST")) request.header("Content-Type", "application/x-www-form-urlencoded");
            return exactClient.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() {
            try { client.close(); }
            finally { exactClient.close(); }
        }
    }

    private record GateDecision(boolean allowed, int order, long atNanos) {}

    private static final class GateProbe implements CurrentUserAccessFilter.GateObserver {
        final String heldLabel;
        final boolean holdAfter;
        final AtomicInteger sequence = new AtomicInteger();
        final AtomicReference<BrowserSessionContextRegistry.Stamp> stamp = new AtomicReference<>();
        final Map<String, GateDecision> decisions = new ConcurrentHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        GateProbe(String heldLabel, boolean holdAfter) { this.heldLabel = heldLabel; this.holdAfter = holdAfter; }
        @Override public void beforeFinal(HttpServletRequest request, BrowserSessionContextRegistry.Stamp captured) {
            String label = request.getHeader("X-Cookie-Control");
            if (!heldLabel.equals(label)) return;
            stamp.set(captured);
            event(label + "-after-real-user-lookup-before-final");
            if (!holdAfter) pause();
        }
        @Override public void afterFinal(HttpServletRequest request, BrowserSessionContextRegistry.Stamp captured,
                boolean allowed) {
            String label = request.getHeader("X-Cookie-Control");
            if (label == null) return;
            int order = event(label + "-final-admission-" + allowed);
            assertThat(decisions.putIfAbsent(label, new GateDecision(allowed, order, System.nanoTime()))).isNull();
            if (heldLabel.equals(label) && holdAfter) pause();
        }
        int event(String event) {
            int order = sequence.incrementAndGet();
            System.out.println("COOKIE_CONTEXT_EVENT order=" + order + " monotonic_ns=" + System.nanoTime()
                    + " event=" + event);
            return order;
        }
        void pause() {
            entered.countDown();
            awaitControl(release, "Final gate observer");
        }
    }

    private static void awaitControl(CountDownLatch release, String label) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError(label + " timed out");
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " interrupted", interrupted);
        }
    }

    private static final class SuccessPause {
        final AtomicBoolean actualRotationCookie = new AtomicBoolean();
        final AtomicBoolean uncommitted = new AtomicBoolean();
        final AtomicReference<HttpSession> session = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        void afterSuccessfulChain(HttpServletRequest request, HttpServletResponse response) {
            assertThat(response.getStatus()).isEqualTo(303);
            assertThat(response.getHeader("Location")).isEqualTo(origin + "/account");
            HttpSession captured = request.getSession(false);
            assertThat(captured).isNotNull();
            session.set(captured);
            uncommitted.set(!response.isCommitted());
            actualRotationCookie.set(response.getHeaders("Set-Cookie").stream().anyMatch(header ->
                    isHostOnlyRootSessionCookie(header)
                    && HttpCookie.parse(header).getFirst().getValue().equals(captured.getId())));
            System.out.println("COOKIE_CONTEXT_EVENT event=actual-success-selected-before-commit monotonic_ns="
                    + System.nanoTime());
            entered.countDown();
            awaitControl(release, "Actual success response");
        }
    }

    private static final class InitialPairPause {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class RecoveryCookieWriteProbe {
        final boolean failWrite;
        final AtomicInteger writes = new AtomicInteger();
        final AtomicBoolean beforeCommit = new AtomicBoolean();
        final AtomicBoolean injectedFailure = new AtomicBoolean();
        final AtomicReference<String> context = new AtomicReference<>();
        final AtomicReference<HttpSession> session = new AtomicReference<>();
        final AtomicReference<BrowserSessionContextRegistry.Stamp> stamp = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        RecoveryCookieWriteProbe(boolean failWrite) { this.failWrite = failWrite; }

        void beforeActualCookieWrite(HttpServletRequest request, HttpServletResponse response,
                jakarta.servlet.http.Cookie cookie) {
            assertThat(writes.incrementAndGet()).as("The exact recovery response has one initial issuance").isEqualTo(1);
            HttpSession captured = request.getSession(false);
            assertThat(captured).isNotNull();
            session.set(captured);
            stamp.set(registry().capture(captured));
            assertThat(stamp.get()).isNotNull();
            assertThat(cookie.getValue().equals(stamp.get().contextId())).isTrue();
            context.set(cookie.getValue());
            beforeCommit.set(!response.isCommitted());
            entered.countDown();
            if (failWrite) {
                injectedFailure.set(true);
                throw new RecoveryCookieWriteFailure();
            }
            awaitControl(release, "Actual first recovery-cookie write");
        }
    }

    private static final class RecoveryCookieWriteFailure extends RuntimeException {
        RecoveryCookieWriteFailure() { super("Test-only failure before actual recovery cookie write"); }
    }

    private static boolean isRecoveryCookieWriteFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof RecoveryCookieWriteFailure) return true;
        }
        return false;
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
        volatile ResolutionReadPause resolutionReadPause;
        volatile CommitPause commitPause;
        volatile RotationPause rotationPause;
        volatile SuccessPause successPause;
        volatile InitialPairPause initialPairPause;
        volatile RecoveryCookieWriteProbe recoveryCookieWriteProbe;
        void reset() {
            failAcquisition.set(false);
            loseNextCommitAcknowledgement.set(false);
            committedThenFailed.set(0);
        }
    }

    static final class CommitPause {
        final AtomicReference<Connection> readConnection = new AtomicReference<>();
        final AtomicInteger observedCommits = new AtomicInteger();
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void afterCommit(Connection connection) throws SQLException {
            if (connection != readConnection.get()) return;
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

    static final class ResolutionReadPause {
        final boolean commitFirst;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<Integer> firstPid = new AtomicReference<>();
        final AtomicReference<Integer> secondPid = new AtomicReference<>();
        final AtomicInteger firstCommits = new AtomicInteger();
        final AtomicInteger firstRollbacks = new AtomicInteger();
        final CountDownLatch firstReadReturned = new CountDownLatch(1);
        final CountDownLatch secondAttempted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        volatile Connection firstConnection;

        ResolutionReadPause(boolean commitFirst) { this.commitFirst = commitFirst; }

        synchronized int beforeRead(Connection connection) throws SQLException {
            // Observe only two in-flight identity resolutions; private follow-up reads
            // and already-released operations do not become artificial competitors.
            if (releaseFirst.getCount() == 0 || attempts.get() >= 2) return 0;
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
            }
            else if (attempt == 2) {
                secondPid.set(pid);
                secondAttempted.countDown();
            }
            return attempt;
        }

        void afterRead(int attempt) throws SQLException {
            if (attempt != 1) {
                return;
            }
            // The real SELECT returned its ResultSet; the owned read transaction has not committed.
            firstReadReturned.countDown();
            try {
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("Test identity-read barrier expired");
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Test identity-read barrier interrupted", interrupted);
            }
            if (!commitFirst) {
                throw new SQLException("Test-only failure after real identity SELECT", "XX000");
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
                            ResolutionReadPause race = FAULTS.resolutionReadPause;
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
                                    && sql.startsWith("SELECT u.id, u.status FROM user_identity_bindings b")) {
                                return observeIdentityRead(connection, (PreparedStatement) result, race, commitPause);
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

        private PreparedStatement observeIdentityRead(Connection connection, PreparedStatement statement,
                ResolutionReadPause race, CommitPause commitPause) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                        try {
                            boolean executing = method.getName().equals("executeQuery");
                            if (executing && commitPause != null) {
                                commitPause.readConnection.compareAndSet(null, connection);
                            }
                            int attempt = executing && race != null
                                    ? race.beforeRead(connection) : 0;
                            Object result = method.invoke(statement, args);
                            if (attempt != 0) {
                                try {
                                    race.afterRead(attempt);
                                }
                                catch (SQLException failure) {
                                    ((java.sql.ResultSet) result).close();
                                    throw failure;
                                }
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
        @Bean @Primary CognitoEmailConfirmationService testConfirmationService(AuthenticationProperties properties) {
            // Always replace the production API transport, including every selected-Cognito context.
            return new CognitoEmailConfirmationService(properties, CONFIRMATION, CONFIRMATION_TIME::get,
                    CognitoEmailConfirmationService.Limits.standard());
        }
        @Bean @Primary AuthenticationProperties testAuthenticationProperties() {
            return new AuthenticationProperties(provider.issuer().toString(), CLIENT_ID, CLIENT_SECRET,
                    origin, admitted, false, providerMode, true);
        }
        @Bean @Primary Clock testClock() { return CLOCK; }
        @Bean @Primary ClientRegistrationRepository testClientRegistrations() {
            ClientRegistration registration = ClientRegistration.withRegistrationId(AuthenticationProperties.REGISTRATION_ID)
                    .clientId(CLIENT_ID).clientSecret(CLIENT_SECRET)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(origin + "/login/oauth2/code/" + AuthenticationProperties.REGISTRATION_ID)
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
                        || !("/login/oauth2/code/" + AuthenticationProperties.REGISTRATION_ID).equals(http.getRequestURI())
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
        @Bean FilterRegistrationBean<Filter> testCookieContextObservers() {
            Filter observer = (request, response, chain) -> {
                if (!(request instanceof HttpServletRequest http)
                        || !(response instanceof HttpServletResponse output)) {
                    chain.doFilter(request, response);
                    return;
                }
                String control = http.getHeader("X-Cookie-Control");
                if ("create-untagged".equals(control) && "/actuator/health".equals(http.getRequestURI())) {
                    http.getSession(true); // Real anonymous fixture session, no Q or principal injected.
                }
                InitialPairPause pair = FAULTS.initialPairPause;
                if (pair != null && "pair-race".equals(control)) {
                    pair.entered.countDown();
                    awaitControl(pair.release, "Initial pairing entry");
                }
                SuccessPause success = FAULTS.successPause;
                RecoveryCookieWriteProbe recovery = FAULTS.recoveryCookieWriteProbe;
                if (recovery != null && "recovery-bootstrap".equals(control)
                        && "/auth/csrf".equals(http.getRequestURI())) {
                    var observed = new jakarta.servlet.http.HttpServletResponseWrapper(output) {
                        @Override public void addCookie(jakarta.servlet.http.Cookie cookie) {
                            if (BrowserSessionContextRegistry.COOKIE_NAME.equals(cookie.getName())) {
                                // Observe/delay the real issuance after registry pairing, never invent
                                // a cookie or change a registry/authentication decision.
                                recovery.beforeActualCookieWrite(http, output, cookie);
                            }
                            super.addCookie(cookie);
                        }
                    };
                    try {
                        chain.doFilter(request, observed);
                    }
                    catch (IOException | jakarta.servlet.ServletException | RuntimeException failure) {
                        if (!recovery.injectedFailure.get() || !isRecoveryCookieWriteFailure(failure)) throw failure;
                        // Controlled partial write failure only. Preserve the genuine pending
                        // container SID and all other headers; do not reset or manufacture cookies.
                        AuthenticationConfiguration.jsonError(output, 503);
                    }
                }
                else {
                    chain.doFilter(request, response);
                }
                if (success != null && "late-success".equals(control)) success.afterSuccessfulChain(http, output);
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

    private static final class ConfirmationStub implements CognitoEmailConfirmationService.Transport {
        final AtomicInteger calls = new AtomicInteger();
        volatile CognitoEmailConfirmationService.ProviderResponse next;
        volatile boolean fail;
        void reset() {
            calls.set(0);
            fail = false;
            next = new CognitoEmailConfirmationService.ProviderResponse(200,
                    "{\"Session\":\"discarded-test-session\",\"CodeDeliveryDetails\":{\"Destination\":\"private-test-destination\"}}".getBytes(StandardCharsets.UTF_8));
        }
        @Override public CognitoEmailConfirmationService.ProviderResponse submit(CognitoEmailConfirmationService.Operation operation,
                byte[] body, long deadlineNanos) throws IOException {
            calls.incrementAndGet();
            if (fail) throw new IOException("private-provider-transport-detail");
            return next;
        }
    }
}
