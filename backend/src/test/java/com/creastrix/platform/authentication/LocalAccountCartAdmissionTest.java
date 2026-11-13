package com.creastrix.platform.authentication;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.creastrix.platform.user.application.AuthenticatedUserService;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LocalAccountCartAdmissionTest {
    private static MockEnvironment isolated() {
        var env = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1").withProperty("server.port", "8191")
                .withProperty("server.forward-headers-strategy", "none").withProperty("creastrix.auth.enabled", "true")
                .withProperty("creastrix.auth.existing-only", "true")
                .withProperty("creastrix.auth.issuer", LocalAccountCartConfiguration.ISSUER)
                .withProperty("creastrix.auth.browser-origin", LocalAccountCartConfiguration.ORIGIN)
                .withProperty("spring.datasource.url", LocalAccountCartConfiguration.DATABASE)
                .withProperty("spring.datasource.username", LocalAccountCartConfiguration.ROLE)
                .withProperty("spring.flyway.locations", LocalAccountCartConfiguration.MIGRATIONS);
        env.setActiveProfiles(LocalAccountCartConfiguration.PROFILE);
        return env;
    }

    @Test void acceptsOnlyTheDedicatedProfile() {
        var env = isolated();
        LocalAccountCartConfiguration.verify(env);
        for (String other : List.of("auth-local", "local-catalog")) {
            env.setActiveProfiles(LocalAccountCartConfiguration.PROFILE, other);
            assertThatThrownBy(() -> LocalAccountCartConfiguration.verify(env)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({"creastrix.auth.issuer,https://provider.invalid/", "server.address,0.0.0.0",
            "spring.flyway.url,jdbc:postgresql://localhost/other", "spring.datasource.username,other",
            "creastrix.auth.existing-only,false", "spring.datasource.url,jdbc:postgresql://localhost/other"})
    void refusesOtherAuthorityOrMigrationTarget(String key, String value) {
        assertThatThrownBy(() -> LocalAccountCartConfiguration.verify(isolated().withProperty(key, value)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @CsvSource({"GET,false", "HEAD,false", "POST,false", "GET,true"})
    void privateArtworkOnlyAcceptsCanonicalGet(String method, boolean query) {
        String uuid = "abcdef01-0000-4000-8000-000000000001";
        String path = "/api/account-cart/lines/" + uuid + "/artwork";
        var request = new MockHttpServletRequest(method, path);
        if (query) request.setQueryString("version=1");
        assertThat(LocalAccountCartController.artworkLine(request).isPresent()).isEqualTo(method.equals("GET") && !query);
        for (String substitute : List.of("not-a-uuid", uuid.toUpperCase(java.util.Locale.ROOT), uuid + "/more")) {
            assertThat(LocalAccountCartController.artworkLine(new MockHttpServletRequest("GET", path.replace(uuid, substitute)))).isEmpty();
        }
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void privateResultCanReachMvcOnlyIfOriginalSessionIsAdmittedAfterLoading(
            boolean sourceArtwork, boolean revokeDuringRead) throws Exception {
        var clock = Clock.systemUTC();
        var users = mock(AuthenticatedUserService.class);
        var controller = mock(LocalAccountCartController.class);
        var identity = new Identity(LocalAccountCartConfiguration.ISSUER, LocalAccountCartConfiguration.SUBJECT);
        var owner = UUID.randomUUID();
        var properties = new AuthenticationProperties(identity.issuer(), "local-client", "synthetic",
                LocalAccountCartConfiguration.ORIGIN, Set.of(identity.subject()), false, AuthenticationProperties.Provider.COGNITO, true);
        var line = UUID.randomUUID();
        var request = new MockHttpServletRequest("GET", LocalAccountCartController.CART_PATH
                + (sourceArtwork ? "/lines/" + line + "/artwork" : ""));
        var response = new MockHttpServletResponse();
        var session = new MockHttpSession();
        request.setSession(session);
        var contexts = new BrowserSessionContextRegistry(clock);
        try {
            var principal = new CreastrixPrincipal(owner, identity, clock.instant(), null);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
            String q = contexts.bootstrap(session, AuthenticationAttemptCoordinator.forSession(session));
            request.addHeader("Cookie", BrowserSessionContextRegistry.COOKIE_NAME + "=" + q);
            var original = contexts.beginOwner(contexts.capture(session));
            assertThat(contexts.reservePublication(original)).isTrue();
            assertThat(contexts.publish(original)).isTrue();
            when(users.currentUser(eq(identity), any())).thenReturn(Optional.of(new User(owner, UserStatus.ACTIVE)));
            if (revokeDuringRead) {
                var reading = doAnswer(invocation -> { contexts.revoke(q); return null; }).when(controller);
                if (sourceArtwork) reading.stageArtwork(request, owner, line);
                else reading.stage(request, owner);
            }
            var reached = new AtomicBoolean();
            new CurrentUserAccessFilter(users, properties, clock, contexts, controller).doFilter(request, response,
                    (req, res) -> reached.set(true));
            if (sourceArtwork) verify(controller).stageArtwork(request, owner, line);
            else verify(controller).stage(request, owner);
            assertThat(reached.get()).isEqualTo(!revokeDuringRead);
            assertThat(response.getStatus()).isEqualTo(revokeDuringRead ? 401 : 200);
        } finally {
            SecurityContextHolder.clearContext();
            contexts.close();
        }
    }
}
