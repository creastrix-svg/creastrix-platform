package com.creastrix.platform.authentication;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.creastrix.platform.user.application.AuthenticatedUserService;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuthenticationPolicyTest {

    private static final String ISSUER = "https://pilot.eu.auth0.com/";
    private static final String SUBJECT = "auth0|pilot-subject";
    private static final Instant STARTED = Instant.parse("2026-09-10T12:00:00Z");
    private static final Instant NOW = STARTED.plusSeconds(120);

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void nonWebContextDoesNotCreateAuthenticationWiringEvenWhenEnabled(boolean enabled) {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthenticationConfiguration.class, AccountController.class)
                .withPropertyValues("creastrix.auth.enabled=" + enabled, "creastrix.auth.issuer=malformed")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(AuthenticationProperties.class)
                            .doesNotHaveBean(SecurityFilterChain.class)
                            .doesNotHaveBean(ClientRegistrationRepository.class)
                            .doesNotHaveBean(CreastrixOidcUserService.class)
                            .doesNotHaveBean(AccountController.class)
                            .doesNotHaveBean(OidcIdTokenDecoderFactory.class)
                            .doesNotHaveBean(Clock.class);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void defaultOrExplicitlyDisabledWebContextHasOnlyFailClosedHealthAccess(boolean explicit) {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                        ServletWebSecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                .withUserConfiguration(AuthenticationConfiguration.class, AccountController.class)
                .withPropertyValues("creastrix.auth.issuer=malformed");
        if (explicit) {
            runner = runner.withPropertyValues("creastrix.auth.enabled=false");
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SecurityFilterChain.class)
                    .doesNotHaveBean(AuthenticationProperties.class)
                    .doesNotHaveBean(CreastrixOidcUserService.class)
                    .doesNotHaveBean(ClientRegistrationRepository.class)
                    .doesNotHaveBean(AccountController.class)
                    .doesNotHaveBean(UserDetailsService.class)
                    .doesNotHaveBean(OAuth2AuthorizedClientService.class);
            assertThat(context.getBean(SecurityFilterChain.class).getFilters())
                    .noneMatch(filter -> filter instanceof BasicAuthenticationFilter
                            || filter instanceof UsernamePasswordAuthenticationFilter
                            || filter instanceof DefaultLoginPageGeneratingFilter);

            Filter security = context.getBean("springSecurityFilterChain", Filter.class);
            for (String path : List.of("/actuator/health", "/actuator/info", "/api/me", "/auth/csrf",
                    "/login", "/oauth2/authorization/auth0")) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                AtomicBoolean endpointReached = new AtomicBoolean();
                security.doFilter(new MockHttpServletRequest("GET", path), response,
                        (request, result) -> endpointReached.set(true));
                if ("/actuator/health".equals(path)) {
                    assertThat(endpointReached).isTrue();
                } else {
                    assertThat(endpointReached).isFalse();
                    assertThat(response.getStatus()).isEqualTo(401);
                    assertThat(response.getContentType()).startsWith("application/json");
                    assertThat(response.getHeader("Location")).isNull();
                    assertThat(response.getHeader("WWW-Authenticate")).isNull();
                    assertThat(response.getContentAsString()).doesNotContain("<html");
                }
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "garbage", "1", "yes" })
    void malformedAuthenticationModeStopsStartupInsteadOfFallingBackToBootLogin(String mode) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                        ServletWebSecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                .withUserConfiguration(AuthenticationConfiguration.class, AccountController.class)
                .withPropertyValues("creastrix.auth.enabled=" + mode)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Authentication mode must be explicitly true or false");
                });
    }

    @Test
    void enabledServletContextUsesExplicitLocalSettingsAndTestOwnedRegistrationWithoutDiscovery() {
        new WebApplicationContextRunner()
                .withUserConfiguration(AuthenticationConfiguration.class, AccountController.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("auth-local"))
                .withPropertyValues("creastrix.auth.enabled=true", "creastrix.auth.issuer=" + ISSUER,
                        "creastrix.auth.client-id=client", "creastrix.auth.client-secret=secret",
                        "creastrix.auth.browser-origin=http://localhost:3000",
                        "creastrix.auth.allowed-subjects[0]=" + SUBJECT,
                        "server.servlet.session.cookie.secure=false")
                .withBean(AuthenticatedUserService.class, () -> mock(AuthenticatedUserService.class))
                .withBean(ClientRegistrationRepository.class, () -> new InMemoryClientRegistrationRepository(
                        ClientRegistration.withRegistrationId("auth0").clientId("client").clientSecret("secret")
                                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                                .redirectUri("http://localhost:3000/login/oauth2/code/auth0")
                                .scope("openid", "profile", "email").issuerUri(ISSUER)
                                .authorizationUri(ISSUER + "authorize").tokenUri(ISSUER + "oauth/token")
                                .jwkSetUri(ISSUER + ".well-known/jwks.json").userInfoUri(ISSUER + "userinfo")
                                .userNameAttributeName("sub").build()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SecurityFilterChain.class)
                            .hasSingleBean(AuthenticationProperties.class).hasSingleBean(AccountController.class)
                            .hasSingleBean(CreastrixOidcUserService.class).hasSingleBean(OidcIdTokenDecoderFactory.class)
                            .doesNotHaveBean(UserDetailsService.class).doesNotHaveBean(OAuth2AuthorizedClientService.class)
                            .doesNotHaveBean("authenticationDisabled");
                    assertThat(context.getBean(AuthenticationProperties.class).admits(new Identity(ISSUER, SUBJECT)))
                            .isTrue();
                    assertThat(context.getBean(SecurityFilterChain.class).getFilters())
                            .noneMatch(filter -> filter instanceof BasicAuthenticationFilter
                                    || filter instanceof UsernamePasswordAuthenticationFilter
                                    || filter instanceof DefaultLoginPageGeneratingFilter);
                });
    }

    @ParameterizedTest
    @ValueSource(longs = { 28800, 28801 })
    void absoluteSessionExpiryInvalidatesTheSessionBeforeAnyDatabaseLookup(long elapsedSeconds) throws Exception {
        UUID userId = UUID.fromString("b4465361-93da-4fae-9f3d-8d189a0c198e");
        CreastrixPrincipal principal = new CreastrixPrincipal(userId, new Identity(ISSUER, SUBJECT), STARTED,
                provider(verifiedClaims(), null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean endpointReached = new AtomicBoolean();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        try {
            // A null database service makes an accidental lookup fail this test instead of hiding it.
            new CurrentUserAccessFilter(null, properties(), Clock.fixed(STARTED.plusSeconds(elapsedSeconds), ZoneOffset.UTC))
                    .doFilter(request, response, (ignoredRequest, ignoredResponse) -> endpointReached.set(true));

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(endpointReached).isFalse();
            assertThat(session.isInvalid()).isTrue();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @MethodSource("malformedRawClaims")
    void malformedRawClaimsAreRejectedBeforeEitherFrameworkConverter(String claim, Object value) {
        for (boolean idToken : List.of(true, false)) {
            Map<String, Object> raw = rawClaims();
            raw.put(claim, value);
            AtomicBoolean converted = new AtomicBoolean();

            assertRejected(() -> CreastrixOidcUserService.strictClaims(input -> {
                converted.set(true);
                return input;
            }, idToken).convert(raw));

            assertThat(converted).isFalse();
        }
    }

    static Stream<Arguments> malformedRawClaims() {
        return Stream.of(
                Arguments.of("email_verified", "true"),
                Arguments.of("email_verified", "false"),
                Arguments.of("email_verified", 1),
                Arguments.of("email_verified", 0),
                Arguments.of("email_verified", null),
                Arguments.of("email_verified", List.of(true)),
                Arguments.of("auth_time", Long.toString(STARTED.getEpochSecond())),
                Arguments.of("auth_time", true),
                Arguments.of("auth_time", null),
                Arguments.of("auth_time", STARTED),
                Arguments.of("auth_time", Double.NaN),
                Arguments.of("auth_time", Double.POSITIVE_INFINITY),
                Arguments.of("auth_time", Double.NEGATIVE_INFINITY),
                Arguments.of("auth_time", -1),
                Arguments.of("auth_time", 1.5),
                Arguments.of("auth_time", Long.MAX_VALUE),
                Arguments.of("sub", 123),
                Arguments.of("sub", null),
                Arguments.of("email", true),
                Arguments.of("email", null));
    }

    @ParameterizedTest
    @MethodSource("validNumericAuthenticationTimes")
    void numericAuthenticationTimeReachesTheRealIdTokenConverter(Number authenticationTime) {
        Map<String, Object> raw = rawClaims();
        raw.put("auth_time", authenticationTime);

        Map<String, Object> converted = CreastrixOidcUserService.strictClaims(
                OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter(), true).convert(raw);

        assertThat(converted.get("auth_time")).isEqualTo(STARTED);
        assertThat(converted.get("email_verified")).isEqualTo(true);
        assertThat(raw.get("auth_time")).isSameAs(authenticationTime);
    }

    static Stream<Number> validNumericAuthenticationTimes() {
        return Stream.of(STARTED.getEpochSecond(), Math.toIntExact(STARTED.getEpochSecond()),
                (double) STARTED.getEpochSecond(), BigDecimal.valueOf(STARTED.getEpochSecond()));
    }

    @Test
    void missingAuthenticationTimeIsRejectedInIdTokenButIsOptionalInUserInfo() {
        Map<String, Object> raw = rawClaims();
        raw.remove("auth_time");

        assertRejected(() -> CreastrixOidcUserService.strictClaims(input -> input, true).convert(raw));
        assertThat(CreastrixOidcUserService.strictClaims(new ClaimTypeConverter(
                OidcUserService.createDefaultClaimTypeConverters()), false).convert(raw))
                .containsEntry("email_verified", true)
                .doesNotContainKey("auth_time");
    }

    @Test
    void booleanFalseIsAValidRawTypeButNeverVerifiedIdentity() {
        Map<String, Object> raw = rawClaims();
        raw.put("email_verified", false);
        assertThat(CreastrixOidcUserService.strictClaims(input -> input, true).convert(raw))
                .containsEntry("email_verified", false);

        Map<String, Object> claims = verifiedClaims();
        claims.put("email_verified", false);
        assertRejected(() -> verify(claims, null, STARTED, NOW));
    }

    @Test
    void exactAdmittedIdentityWithFreshAuthenticationIsAccepted() {
        assertThat(verify(verifiedClaims(), null, STARTED, NOW)).isEqualTo(new Identity(ISSUER, SUBJECT));
        assertThat(verify(verifiedClaims(), userInfoClaims(), STARTED, NOW))
                .isEqualTo(new Identity(ISSUER, SUBJECT));
    }

    @ParameterizedTest
    @MethodSource("invalidVerifiedEmailClaims")
    void eitherClaimSourceMustHaveVerifiedNonblankEmail(String claim, Object value) {
        Map<String, Object> invalid = verifiedClaims();
        invalid.put(claim, value);
        assertRejected(() -> verify(invalid, userInfoClaims(), STARTED, NOW));

        Map<String, Object> invalidUserInfo = userInfoClaims();
        invalidUserInfo.put(claim, value);
        assertRejected(() -> verify(verifiedClaims(), invalidUserInfo, STARTED, NOW));
    }

    static Stream<Arguments> invalidVerifiedEmailClaims() {
        return Stream.of(Arguments.of("email_verified", false), Arguments.of("email_verified", "true"),
                Arguments.of("email_verified", 1), Arguments.of("email_verified", null),
                Arguments.of("email", ""), Arguments.of("email", "  "),
                Arguments.of("email", 123), Arguments.of("email", null));
    }

    @Test
    void missingVerificationOrEmailIsRejectedRatherThanTakenFromTheOtherSource() {
        for (String claim : List.of("email_verified", "email")) {
            Map<String, Object> idToken = verifiedClaims();
            idToken.remove(claim);
            assertRejected(() -> verify(idToken, userInfoClaims(), STARTED, NOW));

            Map<String, Object> userInfo = userInfoClaims();
            userInfo.remove(claim);
            assertRejected(() -> verify(verifiedClaims(), userInfo, STARTED, NOW));
        }
    }

    @Test
    void userInfoSubjectMustMatchIdTokenEvenWhenEmailMatches() {
        Map<String, Object> userInfo = userInfoClaims();
        userInfo.put("sub", "auth0|another-subject");

        assertRejected(() -> verify(verifiedClaims(), userInfo, STARTED, NOW));
    }

    @Test
    void subjectAdmissionNeverFallsBackToAnExistingVerifiedEmail() {
        Map<String, Object> claims = verifiedClaims();
        claims.put("sub", "auth0|unadmitted-subject");

        assertRejected(() -> verify(claims, null, STARTED, NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://other.eu.auth0.com/", "https://pilot.eu.auth0.com",
            "https://PILOT.eu.auth0.com/", "http://pilot.eu.auth0.com/" })
    void identityIssuerMustMatchExactly(String issuer) {
        Map<String, Object> claims = verifiedClaims();
        claims.put("iss", issuer);

        assertRejected(() -> verify(claims, null, STARTED, NOW));
    }

    @ParameterizedTest
    @MethodSource("invalidFreshness")
    void staleExpiredFutureOrUnconvertedAuthenticationIsRejected(Object authenticationTime,
                                                                 Instant started, Instant now) {
        Map<String, Object> claims = verifiedClaims();
        claims.put("auth_time", authenticationTime);

        assertRejected(() -> verify(claims, null, started, now));
    }

    static Stream<Arguments> invalidFreshness() {
        return Stream.of(
                Arguments.of(STARTED.minusSeconds(60).minusNanos(1), STARTED, NOW),
                Arguments.of(NOW.plusSeconds(60).plusNanos(1), STARTED, NOW),
                Arguments.of(STARTED, NOW.plusNanos(1), NOW),
                Arguments.of(STARTED, STARTED, STARTED.plusSeconds(300)),
                Arguments.of(STARTED, STARTED, STARTED.plusSeconds(301)),
                Arguments.of(STARTED.getEpochSecond(), STARTED, NOW),
                Arguments.of(STARTED.toString(), STARTED, NOW),
                Arguments.of(null, STARTED, NOW));
    }

    @Test
    void clockSkewBoundariesAreInclusiveButFlowExpiryIsExclusive() {
        for (Instant authenticationTime : List.of(STARTED.minusSeconds(60), NOW.plusSeconds(60))) {
            Map<String, Object> claims = verifiedClaims();
            claims.put("auth_time", authenticationTime);
            assertThat(verify(claims, null, STARTED, NOW)).isEqualTo(new Identity(ISSUER, SUBJECT));
        }
        assertThat(verify(verifiedClaims(), null, STARTED, STARTED.plusSeconds(300).minusNanos(1)))
                .isEqualTo(new Identity(ISSUER, SUBJECT));
    }

    @Test
    void admissionIsAnImmutableExactIssuerSubjectSet() {
        Set<String> configured = new HashSet<>(Set.of(SUBJECT));
        AuthenticationProperties properties = properties(ISSUER, "http://localhost:3000", configured, false);
        configured.add("auth0|added-after-start");

        assertThat(properties.admits(new Identity(ISSUER, SUBJECT))).isTrue();
        assertThat(properties.admits(new Identity(ISSUER, SUBJECT.toUpperCase()))).isFalse();
        assertThat(properties.admits(new Identity(ISSUER, " " + SUBJECT))).isFalse();
        assertThat(properties.admits(new Identity(ISSUER, "auth0|added-after-start"))).isFalse();
        assertThat(properties.admits(new Identity(ISSUER + "/", SUBJECT))).isFalse();
        assertThat(properties(ISSUER, "http://localhost:3000", Set.of(), false)
                .admits(new Identity(ISSUER, SUBJECT))).isFalse();
        assertThatThrownBy(() -> properties.allowedSubjects().add("auth0|mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "http://localhost:3000/", "http://localhost:3000/path",
            "http://localhost:3000?next=elsewhere", "http://localhost:3000#fragment",
            "http://user@localhost:3000", "ftp://localhost:3000", "http:///missing-host" })
    void browserOriginMustBeOnlyAnHttpOrHttpsOrigin(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(ISSUER, origin, Set.of(SUBJECT), false));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", " auth0|subject", "auth0|subject " })
    void invalidAdmissionEntriesAreRejectedRatherThanNormalized(String subject) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(ISSUER, "http://localhost:3000", Set.of(subject), false));
    }

    @Test
    void missingRequiredConfigurationIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthenticationProperties(
                " ", "client", "secret", "http://localhost:3000", Set.of(), false));
        for (String missing : new String[] { null, "", " " }) {
            assertThatIllegalArgumentException().isThrownBy(() -> new AuthenticationProperties(
                    ISSUER, missing, "secret", "http://localhost:3000", Set.of(), false));
            assertThatIllegalArgumentException().isThrownBy(() -> new AuthenticationProperties(
                    ISSUER, "client", missing, "http://localhost:3000", Set.of(), false));
        }
        assertThatNullPointerException().isThrownBy(() -> properties(null, "http://localhost:3000", Set.of(), false));
        assertThatNullPointerException().isThrownBy(() -> properties(ISSUER, null, Set.of(), false));
        assertThatNullPointerException().isThrownBy(() -> properties(ISSUER, "http://localhost:3000", null, false));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://pilot.eu.auth0.com/", "https://pilot.us.auth0.com/",
            "https://pilot.eu.auth0.com", "https://pilot.eu.auth0.com/tenant/",
            "https://pilot.eu.auth0.com:443/", "https://user@pilot.eu.auth0.com/",
            "https://pilot.eu.auth0.com/?query=1", "https://pilot.eu.auth0.com/#fragment",
            "https://pilot.eu.auth0.com.example/" })
    void runtimeRequiresTheExactStandardEuHttpsIssuerShape(String issuer) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties(issuer, "http://localhost:3000", Set.of(SUBJECT), false).validateRuntime(true));
    }

    @Test
    void localCookieExceptionRequiresTheExplicitLocalProfileAndExactBrowserOrigin() {
        properties(ISSUER, "http://localhost:3000", Set.of(SUBJECT), false).validateRuntime(true);
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties(ISSUER, "http://localhost:3000", Set.of(SUBJECT), false).validateRuntime(false));
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties(ISSUER, "http://127.0.0.1:3000", Set.of(SUBJECT), false).validateRuntime(true));
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties(ISSUER, "http://localhost:3000", Set.of(SUBJECT), true).validateRuntime(true));
    }

    @Test
    void nonLocalRuntimeRequiresBothHttpsAndSecureCookies() {
        properties(ISSUER, "https://app.example.test", Set.of(SUBJECT), true).validateRuntime(false);
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties(ISSUER, "https://app.example.test", Set.of(SUBJECT), false).validateRuntime(false));
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties(ISSUER, "http://app.example.test", Set.of(SUBJECT), true).validateRuntime(false));
    }

    @Test
    void redirectsAreFixedAndSensitiveRecordsAreRedacted() {
        AuthenticationProperties properties = properties();
        assertThat(properties.browserAuthority()).isEqualTo("localhost:3000");
        assertThat(properties.callbackUri()).isEqualTo("http://localhost:3000/login/oauth2/code/auth0");
        assertThat(properties.entryUri()).isEqualTo("http://localhost:3000/oauth2/authorization/auth0");
        assertThat(properties.successUri()).isEqualTo("http://localhost:3000/account");
        assertThat(properties.failureUri()).isEqualTo("http://localhost:3000/login?auth=failed");
        assertThat(properties.toString()).isEqualTo("AuthenticationProperties[redacted]");

        UUID userId = UUID.fromString("b4465361-93da-4fae-9f3d-8d189a0c198e");
        OidcUser provider = provider(verifiedClaims(), null);
        CreastrixPrincipal principal = new CreastrixPrincipal(userId, new Identity(ISSUER, SUBJECT), NOW, provider);
        assertThat(principal.getName()).isEqualTo(userId.toString()).isNotEqualTo(SUBJECT);
        assertThat(principal.authenticatedAt()).isEqualTo(NOW);
        assertThat(principal.toString()).isEqualTo("CreastrixPrincipal[redacted]");
    }

    @Test
    void lifetimePolicyIsExplicit() {
        assertThat(AuthenticationProperties.FLOW_LIFETIME).isEqualTo(Duration.ofMinutes(5));
        assertThat(AuthenticationProperties.CLOCK_SKEW).isEqualTo(Duration.ofSeconds(60));
        assertThat(AuthenticationProperties.IDLE_LIFETIME).isEqualTo(Duration.ofMinutes(30));
        assertThat(AuthenticationProperties.ABSOLUTE_LIFETIME).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void admittedFlowCannotBeErasedByDuplicateCleanupOrNewerEntryBeforeFrameworkConsumption() {
        var requests = new AuthenticationConfiguration.TimedAuthorizationRequests(Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/auth0");
        request.setSession(new MockHttpSession());
        request.setParameter("state", "owned-state");
        request.setParameter("code", "test-code");
        var response = new MockHttpServletResponse();
        OAuth2AuthorizationRequest owned = savedFlow("owned-state");
        OAuth2AuthorizationRequest newer = savedFlow("newer-state");
        requests.saveAuthorizationRequest(owned, request, response);
        var attempt = requests.admit(request, response);
        assertThat(attempt).isNotNull();
        try {
            assertThat(requests.admit(request, response)).isNull();
            requests.discardConflicting(request, response);
            requests.saveAuthorizationRequest(newer, request, response);
            var pinned = attempt.pin(request);
            assertThat(requests.removeAuthorizationRequest(pinned, response) == owned).isTrue();
            assertThat(requests.removeAuthorizationRequest(pinned, response)).isNull();
            var nextRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/auth0");
            nextRequest.setSession(request.getSession(false));
            nextRequest.setParameter("state", "newer-state");
            assertThat(requests.loadAuthorizationRequest(nextRequest) == newer).isTrue();
            // Incomplete protocol input must not discard a valid newer flow.
            requests.discardConflicting(nextRequest, response);
            assertThat(requests.loadAuthorizationRequest(nextRequest) == newer).isTrue();
        }
        finally {
            attempt.finish();
        }
    }

    @Test
    void revokedAttemptCannotCreateReplacementSessionOrReleaseNewOwner() {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var coordinator = AuthenticationAttemptCoordinator.forSession(session);
        var old = coordinator.admit(session, "old");
        assertThat(old).isNotNull();
        var pinned = old.pin(request);
        // Actual servlet binding-listener invalidation, not a substituted authenticated principal.
        session.invalidate();
        assertThat(pinned.getSession(false)).isNull();
        assertRejected(pinned::getSession);
        assertRejected(old::beginPublication);
        var replacement = new MockHttpSession();
        var nextCoordinator = AuthenticationAttemptCoordinator.forSession(replacement);
        var current = nextCoordinator.admit(replacement, "current");
        assertThat(current).isNotNull();
        try {
            old.finish();
            assertThat(nextCoordinator.admit(replacement, "competitor")).isNull();
        }
        finally {
            current.finish();
        }
        var subsequent = nextCoordinator.admit(replacement, "subsequent");
        assertThat(subsequent).isNotNull();
        subsequent.finish();
    }

    @ParameterizedTest
    @MethodSource("rejectedCookieStates")
    void pendingCookieCleanupRequiresRejectionOwnershipExactScopeAndUncommittedResponse(
            boolean rejected, boolean committed) {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var attempt = AuthenticationAttemptCoordinator.forSession(session).admit(session, "unit-cookie-state");
        assertThat(attempt).isNotNull();
        var response = new PendingCookieResponse();
        try {
            attempt.beginPublication();
            String rotated = attempt.pin(request).changeSessionId();
            // Classification-only unit inputs; the HTTP regression separately observes native Tomcat headers.
            var owned = List.of("JSESSIONID=" + rotated + "; Path=/; HttpOnly; SameSite=Lax",
                    "JSESSIONID=" + rotated + "; Path=/; Max-Age=0; HttpOnly");
            var unrelated = List.of("control=keep; Path=/", "JSESSIONID=another-attempt; Path=/",
                    "JSESSIONID=; Path=/; Max-Age=0", "JSESSIONID=" + rotated + "; Path=/elsewhere",
                    "JSESSIONID=" + rotated + "; Path=/; Domain=example.test",
                    "JSESSIONID=" + rotated + "; Path=/; Domain",
                    "JSESSIONID=" + rotated + "; Path=/; Path=/elsewhere",
                    "JSESSIONID=" + rotated + "; Path=/; path=/",
                    "JSESSIONID=" + rotated, "JSESSIONID=" + rotated + "; Path",
                    "JSESSIONID_EXTRA=" + rotated + "; Path=/", "not-a-cookie");
            owned.forEach(header -> response.addHeader("Set-Cookie", header));
            unrelated.forEach(header -> response.addHeader("Set-Cookie", header));
            response.setStatus(303);
            response.setHeader("Location", properties().failureUri());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Frame-Options", "DENY");
            var originalHeaders = List.copyOf(response.getHeaders("Set-Cookie"));
            if (rejected) attempt.reject();
            response.setCommitted(committed);

            attempt.discardRejectedSessionCookie(response);

            assertThat(response.getHeaders("Set-Cookie"))
                    .containsExactlyElementsOf(rejected && !committed ? unrelated : originalHeaders);
            assertThat(response.getStatus()).isEqualTo(303);
            assertThat(response.getHeader("Location")).isEqualTo(properties().failureUri());
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.isCommitted()).isEqualTo(committed);
            assertThat(session.isInvalid()).isFalse();
        }
        finally {
            attempt.finish();
        }
    }

    static Stream<Arguments> rejectedCookieStates() {
        return Stream.of(Arguments.of(false, false), Arguments.of(false, true),
                Arguments.of(true, false), Arguments.of(true, true));
    }

    /** Exposes raw pending headers without MockHttpServletResponse's eager cookie parsing. */
    private static final class PendingCookieResponse extends MockHttpServletResponse {
        private final List<String> pendingCookies = new ArrayList<>();
        @Override public void addHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name)) pendingCookies.add(value);
            else super.addHeader(name, value);
        }
        @Override public void setHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name)) {
                pendingCookies.clear();
                if (value != null) pendingCookies.add(value);
            }
            else super.setHeader(name, value);
        }
        @Override public List<String> getHeaders(String name) {
            return "Set-Cookie".equalsIgnoreCase(name) ? List.copyOf(pendingCookies) : super.getHeaders(name);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestBoundaryDoesNotDisguiseCommittedRejectionAndAlwaysReleasesItsOwner(boolean committed)
            throws Exception {
        var requests = new AuthenticationConfiguration.TimedAuthorizationRequests(Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/auth0");
        var session = new MockHttpSession();
        request.setSession(session);
        request.addHeader("Host", properties().browserAuthority());
        request.setParameter("state", "owned-state");
        request.setParameter("code", "unit-code");
        var response = new MockHttpServletResponse();
        requests.saveAuthorizationRequest(savedFlow("owned-state"), request, response);
        var boundary = new AuthenticationConfiguration.RequestBoundary(
                properties(), Clock.fixed(NOW, ZoneOffset.UTC), requests);
        var rejection = CreastrixOidcUserService.rejected();
        jakarta.servlet.FilterChain failing = (input, output) -> {
            var pinned = (jakarta.servlet.http.HttpServletRequest) input;
            AuthenticationAttemptCoordinator.attempt(pinned).beginPublication();
            String rotated = pinned.changeSessionId();
            response.addHeader("Set-Cookie", "JSESSIONID=" + rotated + "; Path=/; HttpOnly");
            response.setStatus(202);
            response.setCommitted(committed);
            throw rejection;
        };

        if (committed) {
            assertThatThrownBy(() -> boundary.doFilter(request, response, failing)).isSameAs(rejection);
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.getHeader("Location")).isNull();
            assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
        }
        else {
            boundary.doFilter(request, response, failing);
            assertThat(response.getStatus()).isEqualTo(303);
            assertThat(response.getHeader("Location")).isEqualTo(properties().failureUri());
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        }
        assertThat(response.isCommitted()).isEqualTo(committed);
        assertThat(session.isInvalid()).isFalse();
        var next = AuthenticationAttemptCoordinator.forSession(session).admit(session, "next");
        assertThat(next).isNotNull();
        next.finish();
    }

    private static OAuth2AuthorizationRequest savedFlow(String state) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(ISSUER + "authorize")
                .clientId("test-client").redirectUri("http://localhost:3000/login/oauth2/code/auth0")
                .state(state).attributes(attributes -> attributes.put(AuthenticationConfiguration.FLOW_STARTED, STARTED))
                .build();
    }

    private static AuthenticationProperties properties() {
        return properties(ISSUER, "http://localhost:3000", Set.of(SUBJECT), false);
    }

    private static AuthenticationProperties properties(String issuer, String browserOrigin,
                                                        Set<String> subjects, boolean secureCookie) {
        return new AuthenticationProperties(issuer, "client", "secret", browserOrigin, subjects, secureCookie);
    }

    private static Map<String, Object> rawClaims() {
        Map<String, Object> claims = verifiedClaims();
        claims.put("auth_time", STARTED.getEpochSecond());
        return claims;
    }

    private static Map<String, Object> verifiedClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", SUBJECT);
        claims.put("email", "pilot@example.test");
        claims.put("email_verified", true);
        claims.put("auth_time", STARTED);
        return claims;
    }

    private static Map<String, Object> userInfoClaims() {
        return new HashMap<>(Map.of("sub", SUBJECT, "email", "pilot@example.test", "email_verified", true));
    }

    private static OidcUser provider(Map<String, Object> claims, Map<String, Object> userInfoClaims) {
        OidcIdToken idToken = new OidcIdToken("test-id-token", STARTED, NOW.plusSeconds(600), claims);
        OidcUserInfo userInfo = userInfoClaims == null ? null : new OidcUserInfo(userInfoClaims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, userInfo);
    }

    private static Identity verify(Map<String, Object> claims, Map<String, Object> userInfoClaims,
                                   Instant started, Instant now) {
        return CreastrixOidcUserService.verifiedIdentity(provider(claims, userInfoClaims), properties(), started, now);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(action)
                .satisfies(exception -> {
                    assertThat(exception.getError().getErrorCode()).isEqualTo("authentication_failed");
                    assertThat(exception.getError().getDescription()).isNull();
                });
    }
}
