package com.creastrix.platform.authentication;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** Local slot/HTTP-decision contract. The terminal chain is a probe, not an OIDC client. */
@Timeout(12)
class LoginIntentCoordinationTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final FilterChain ACCEPT = (request, response) -> ((HttpServletResponse) response).setStatus(204);
    private final AuthenticationProperties properties = new AuthenticationProperties(
            "https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_SyntheticPool", "client", "test",
            "http://localhost:3000", Set.of(), false, AuthenticationProperties.Provider.COGNITO, true);
    private final ObservedSession session = new ObservedSession();
    private final AuthenticationAttemptCoordinator coordinator = AuthenticationAttemptCoordinator.forSession(session);
    private final BrowserSessionContextRegistry contexts = new BrowserSessionContextRegistry(clock(START));
    private final String q = contexts.bootstrap(session, coordinator);

    @AfterEach
    void closeRegistry() {
        contexts.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anonymousRequestMayNotChangeSlotAfterSessionHasBecomeAuthenticated(boolean post) throws Exception {
        assertPublished(START);
        var publishedContext = SecurityContextHolder.createEmptyContext();
        publishedContext.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("synthetic-user", null, List.of()));
        // This request's authentication snapshot predates publication of the stored session context.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, publishedContext);
        Instant later = START.plusSeconds(1);
        assertRefusal(post ? publish(later) : entry(later, q, ACCEPT), 409);
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isEqualTo(START);
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isSameAs(publishedContext);
    }

    @Test
    void storeAndTakeTouchTheSlotUnderTheSameCoordinatorButTheChainRunsOutside() throws Exception {
        var field = AuthenticationAttemptCoordinator.class.getDeclaredField("publication");
        field.setAccessible(true);
        session.observed = (ReentrantLock) field.get(coordinator);
        assertThat(publish(START).getStatus()).isEqualTo(303);
        var response = entry(START, q, (request, out) -> {
            assertThat(session.observed.isHeldByCurrentThread()).isFalse();
            ACCEPT.doFilter(request, out);
        });
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(session.accesses).containsExactly("write:locked", "read:locked", "remove:locked");
    }

    /** Observe ownership only; do not replace the lock or interpose on its scheduling. */
    private static final class ObservedSession extends MockHttpSession {
        private ReentrantLock observed;
        private final List<String> accesses = new ArrayList<>();
        private Runnable afterIntentRemoval;

        private void record(String name, String operation) {
            if (observed != null && AuthenticationConfiguration.LOGIN_INTENT.equals(name)) {
                accesses.add(operation + (observed.isHeldByCurrentThread() ? ":locked" : ":unlocked"));
            }
        }
        @Override public Object getAttribute(String name) {
            record(name, "read");
            return super.getAttribute(name);
        }
        @Override public void setAttribute(String name, Object value) {
            record(name, "write");
            super.setAttribute(name, value);
        }
        @Override public void removeAttribute(String name) {
            record(name, "remove");
            super.removeAttribute(name);
            if (afterIntentRemoval != null && AuthenticationConfiguration.LOGIN_INTENT.equals(name)) {
                afterIntentRemoval.run();
            }
        }
    }

    @Test
    void aPublicationCannotFinishInsideAnotherRequestsSlotOperation() throws Exception {
        assertPublished(START);
        var removed = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        // Pause inside the real consumer's slot operation, after deletion rather than in the chain.
        // No second login or provider operation is needed to observe the exclusion boundary.
        session.afterIntentRemoval = () -> {
            removed.countDown();
            try {
                assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        Instant next = START.plusSeconds(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var taking = worker.submit(() -> entry(START, q, ACCEPT));
            try {
                assertThat(removed.await(3, TimeUnit.SECONDS)).isTrue();
                assertRefusal(publish(next), 503);
                assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
            } finally {
                resume.countDown();
            }
            assertThat(taking.get(3, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        }
        session.afterIntentRemoval = null;
        assertPublished(next);
        assertThat(entry(next, q, ACCEPT).getStatus()).isEqualTo(204);
        assertRefusal(entry(next, q, ACCEPT), 403);
    }

    @Test
    void duplicateConsumeCannotEnterEvenWhileFirstEntryIsStillInTheChain() throws Exception {
        assertPublished(START);
        var inChain = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> entry(START, q, paused(inChain, release)));
            try {
                assertThat(inChain.await(3, TimeUnit.SECONDS)).isTrue();
                assertRefusal(entry(START, q, ACCEPT), 403);
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void publicationAfterClaimSurvivesCompletionOfTheOlderEntry(int secondsLater) throws Exception {
        assertPublished(START);
        var inChain = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Instant next = START.plusSeconds(secondsLater);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> entry(START, q, paused(inChain, release)));
            try {
                assertThat(inChain.await(3, TimeUnit.SECONDS)).isTrue();
                assertPublished(next);
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        }
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isEqualTo(next);
        assertThat(entry(next, q, ACCEPT).getStatus()).isEqualTo(204);
        assertRefusal(entry(next, q, ACCEPT), 403);
    }

    @Test
    void publicationBeforeClaimRenewsOneSlotRatherThanQueuingTwoPermissions() throws Exception {
        assertPublished(START);
        assertPublished(START.plusSeconds(120));
        Instant oldDeadline = START.plusSeconds(300);
        assertThat(entry(oldDeadline, q, ACCEPT).getStatus()).isEqualTo(204);
        assertRefusal(entry(oldDeadline, q, ACCEPT), 403);
    }

    @ParameterizedTest
    @CsvSource({"-1,403", "0,204", "299999,204", "300000,403"})
    void oneShotLifetimeIncludesStartButExcludesDeadline(int elapsedMillis, int status) throws Exception {
        assertPublished(START);
        assertThat(entry(START.plusMillis(elapsedMillis), q, ACCEPT).getStatus()).isEqualTo(status);
        assertRefusal(entry(START, q, ACCEPT), 403);
    }

    @Test
    void refusalAtPairingGateDoesNotRestoreTheSlot() throws Exception {
        assertPublished(START);
        assertRefusal(entry(START, null, ACCEPT), 409);
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
        assertRefusal(entry(START, q, ACCEPT), 403);
    }

    @Test
    void registryTimeoutAfterClaimDoesNotRestoreTheSlot() throws Exception {
        assertPublished(START);
        var field = BrowserSessionContextRegistry.class.getDeclaredField("memory");
        field.setAccessible(true);
        var memory = (ReentrantLock) field.get(contexts);
        try (var worker = Executors.newSingleThreadExecutor()) {
            memory.lock();
            try {
                var contender = worker.submit(() -> entry(START, q, ACCEPT));
                assertRefusal(contender.get(4, TimeUnit.SECONDS), 503);
            } finally {
                memory.unlock();
            }
        }
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
        assertRefusal(entry(START, q, ACCEPT), 403);
        assertPublished(START);
        assertThat(entry(START, q, ACCEPT).getStatus()).isEqualTo(204);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void occupiedCoordinatorRefusesWithoutOverwritingOrConsumingExistingSlot(boolean post) throws Exception {
        assertPublished(START);
        Instant later = START.plusSeconds(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            // The main thread owns the actual session lock until the request finishes.
            coordinator.lock();
            try {
                var contender = worker.submit(() -> post ? publish(later) : entry(later, q, ACCEPT));
                assertRefusal(contender.get(4, TimeUnit.SECONDS), 503);
                assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isEqualTo(START);
            } finally {
                coordinator.unlock();
            }
        }
        assertThat(entry(later, q, ACCEPT).getStatus()).isEqualTo(204);
        assertRefusal(entry(later, q, ACCEPT), 403);
    }

    private static FilterChain paused(CountDownLatch entered, CountDownLatch release) {
        return (request, response) -> {
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ServletException(interrupted);
            }
            ACCEPT.doFilter(request, response);
        };
    }

    private void assertPublished(Instant now) throws Exception {
        var response = publish(now);
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location")).isEqualTo(properties.entryUri());
        assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isEqualTo(now);
    }

    private MockHttpServletResponse publish(Instant now) throws ServletException, IOException {
        var request = request("POST", "/auth/login", q);
        var response = new MockHttpServletResponse();
        var controller = new AccountController(properties, clock(now), contexts);
        boundary(now).doFilter(request, response, (input, output) -> {
            try {
                controller.login(null, request, response);
            } catch (RuntimeException failure) {
                // DispatcherServlet wraps controller failures; the outer boundary must still select 503.
                throw new ServletException(failure);
            }
        });
        return response;
    }

    private MockHttpServletResponse entry(Instant now, String cookie, FilterChain chain)
            throws ServletException, IOException {
        var response = new MockHttpServletResponse();
        boundary(now).doFilter(request("GET", properties.entryPath(), cookie), response, chain);
        return response;
    }

    private MockHttpServletRequest request(String method, String path, String cookie) {
        var request = new MockHttpServletRequest(method, path);
        request.setSession(session);
        request.addHeader("Host", properties.browserAuthority());
        if ("POST".equals(method)) request.addHeader("Origin", properties.browserOrigin());
        if (cookie != null) request.addHeader("Cookie", BrowserSessionContextRegistry.COOKIE_NAME + "=" + cookie);
        return request;
    }

    private AuthenticationConfiguration.RequestBoundary boundary(Instant now) {
        var clock = clock(now);
        return new AuthenticationConfiguration.RequestBoundary(properties, clock,
                new AuthenticationConfiguration.TimedAuthorizationRequests(clock), contexts);
    }

    private static Clock clock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private static void assertRefusal(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"request_not_completed\"}");
    }
}
