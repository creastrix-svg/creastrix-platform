package com.creastrix.platform.authentication;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;

import com.creastrix.platform.user.application.AuthenticatedUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Servlet-only authentication. Disabled mode does not validate settings or contact an issuer. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthenticationConfiguration {
    static final String LOGIN_INTENT = AuthenticationConfiguration.class.getName() + ".intent";
    static final String FLOW_STARTED = AuthenticationConfiguration.class.getName() + ".started";
    private static final String ENTRY_BASE = "/oauth2/authorization";
    private static final String ENTRY_PATH = ENTRY_BASE + "/auth0";
    private static final String CALLBACK_PATH = "/login/oauth2/code/auth0";
    private static final String LOGOUT_PATH = "/auth/logout";

    // Match decoded path segments exactly as Spring's OAuth filters and MVC do;
    // never decode/rewrite the URI or its code/state query parameters ourselves.
    static final PathPatternRequestMatcher PRIVATE_API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
    private static final PathPatternRequestMatcher AUTH_ROUTES = PathPatternRequestMatcher.withDefaults().matcher("/auth/**");
    private static final PathPatternRequestMatcher ENTRY_ROUTES = PathPatternRequestMatcher.withDefaults().matcher("/oauth2/**");
    private static final PathPatternRequestMatcher CALLBACK_ROUTES = PathPatternRequestMatcher.withDefaults().matcher("/login/oauth2/**");
    private static final PathPatternRequestMatcher ENTRY = PathPatternRequestMatcher.withDefaults().matcher(ENTRY_PATH);
    private static final PathPatternRequestMatcher CALLBACK = PathPatternRequestMatcher.withDefaults().matcher(CALLBACK_PATH);

    @Bean
    static BeanFactoryPostProcessor authenticationModeValidation(Environment environment) {
        return factory -> {
            String mode = environment.getProperty("creastrix.auth.enabled", "false");
            if (!("true".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode))) {
                throw new IllegalArgumentException("Authentication mode must be explicitly true or false");
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "creastrix.auth.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain authenticationDisabled(HttpSecurity http) throws Exception {
        defaults(http);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    static void defaults(HttpSecurity http) throws Exception {
        http.formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> jsonError(response, 401))
                        .accessDeniedHandler((request, response, exception) -> jsonError(response, 403)));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "creastrix.auth.enabled", havingValue = "true")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class EnabledAuthentication {
        @Bean
        @ConditionalOnMissingBean
        Clock authenticationClock() { return Clock.systemUTC(); }

        @Bean
        @ConditionalOnMissingBean
        AuthenticationProperties authenticationProperties(Environment environment) {
            var binder = Binder.get(environment);
            var subjects = binder.bind("creastrix.auth.allowed-subjects", Bindable.setOf(String.class)).orElse(Set.of());
            boolean local = environment.acceptsProfiles(Profiles.of("auth-local"));
            var properties = new AuthenticationProperties(
                    environment.getRequiredProperty("creastrix.auth.issuer"),
                    environment.getRequiredProperty("creastrix.auth.client-id"),
                    environment.getRequiredProperty("creastrix.auth.client-secret"),
                    environment.getRequiredProperty("creastrix.auth.browser-origin"), subjects,
                    environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true));
            properties.validateRuntime(local);
            return properties;
        }

        @Bean
        @ConditionalOnMissingBean(ClientRegistrationRepository.class)
        ClientRegistrationRepository authenticationClients(AuthenticationProperties properties) {
            return new InMemoryClientRegistrationRepository(ClientRegistrations.fromIssuerLocation(properties.issuer())
                    .registrationId("auth0").clientId(properties.clientId()).clientSecret(properties.clientSecret())
                    .scope("openid", "profile", "email").redirectUri(properties.callbackUri()).build());
        }

        @Bean
        OidcIdTokenDecoderFactory strictIdTokens(AuthenticationProperties properties) {
            var factory = new OidcIdTokenDecoderFactory();
            factory.setClaimTypeConverterFactory(registration -> raw -> {
                if (!properties.issuer().equals(raw.get("iss"))) {
                    throw CreastrixOidcUserService.rejected();
                }
                return CreastrixOidcUserService.strictClaims(
                        OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter(), true).convert(raw);
            });
            return factory;
        }

        @Bean
        CreastrixOidcUserService authenticationUsers(AuthenticatedUserService users,
                                                    AuthenticationProperties properties, Clock clock) {
            var delegate = new OidcUserService();
            delegate.setClaimTypeConverterFactory(registration -> CreastrixOidcUserService.strictClaims(
                    new ClaimTypeConverter(OidcUserService.createDefaultClaimTypeConverters()), false));
            return new CreastrixOidcUserService(delegate, users, properties, clock);
        }

        @Bean
        SecurityFilterChain authenticationEnabled(HttpSecurity http, AuthenticationProperties properties,
                ClientRegistrationRepository clients, CreastrixOidcUserService users,
                AuthenticatedUserService internalUsers, Clock clock) throws Exception {
            defaults(http);
            var requests = new TimedAuthorizationRequests(clock);
            var authorizedClients = new OwnedAuthorizedClients();
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, ENTRY_BASE);
            resolver.setAuthorizationRequestCustomizer(builder -> {
                OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
                builder.redirectUri(properties.callbackUri())
                        .additionalParameters(parameters -> {
                            parameters.put("prompt", "login");
                            parameters.put("max_age", "0");
                        }).attributes(attributes -> attributes.put(FLOW_STARTED, clock.instant()));
            });
            var csrf = new HttpSessionCsrfTokenRepository();
            http.csrf(configurer -> configurer.csrfTokenRepository(csrf))
                    .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET, "/actuator/health", "/auth/csrf",
                                    ENTRY_PATH, CALLBACK_PATH).permitAll()
                            .requestMatchers(HttpMethod.POST, "/auth/login", LOGOUT_PATH).permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                            .anyRequest().denyAll())
                    .oauth2Login(oauth -> oauth
                            .loginPage("/login")
                            .clientRegistrationRepository(clients)
                            .authorizedClientRepository(authorizedClients)
                            .authorizationEndpoint(endpoint -> endpoint
                                    .authorizationRequestResolver(resolver).authorizationRequestRepository(requests))
                            .userInfoEndpoint(endpoint -> endpoint.oidcUserService(users))
                            .successHandler((request, response, authentication) -> {
                                request.getSession().setMaxInactiveInterval((int) AuthenticationProperties.IDLE_LIFETIME.toSeconds());
                                redirect(response, properties.successUri());
                            })
                            .failureHandler((request, response, failure) -> {
                                var attempt = AuthenticationAttemptCoordinator.attempt(request);
                                if (attempt != null) {
                                    attempt.reject();
                                }
                                if (response.isCommitted()) {
                                    throw failure;
                                }
                                // The consumed flow belongs to this attempt; a newer flow/session does not.
                                authorizedClients.failedPublication(request, response);
                                redirect(response, properties.failureUri());
                            }))
                    .logout(logout -> logout
                            .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOGOUT_PATH))
                            .addLogoutHandler((request, response, authentication) -> {
                                AuthenticationAttemptCoordinator.revoke(request.getSession(false));
                                expireCookie(response, properties);
                            })
                            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                    // Load the current principal first, but gate before CSRF, logout and OAuth processing.
                    .addFilterAfter(new RequestBoundary(properties, clock, requests), SecurityContextHolderFilter.class)
                    .addFilterBefore(new CurrentUserAccessFilter(internalUsers, properties, clock), AuthorizationFilter.class);
            return http.build();
        }
    }

    static final class OwnedAuthorizedClients implements OAuth2AuthorizedClientRepository {
        private final HttpSessionOAuth2AuthorizedClientRepository delegate = new HttpSessionOAuth2AuthorizedClientRepository();

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registration, Authentication principal,
                                                                       HttpServletRequest request) {
            return delegate.loadAuthorizedClient(registration, principal, request);
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                                         HttpServletRequest request, HttpServletResponse response) {
            var attempt = AuthenticationAttemptCoordinator.attempt(request);
            if (attempt == null) {
                throw CreastrixOidcUserService.rejected();
            }
            // Spring 7.1 saves the client before rotation/context and before the success handler.
            attempt.beginPublication();
            try {
                delegate.saveAuthorizedClient(client, principal, request, response);
            }
            catch (IllegalStateException invalidated) {
                throw CreastrixOidcUserService.rejected();
            }
        }

        @Override
        public void removeAuthorizedClient(String registration, Authentication principal,
                                           HttpServletRequest request, HttpServletResponse response) {
            delegate.removeAuthorizedClient(registration, principal, request, response);
        }

        void failedPublication(HttpServletRequest request, HttpServletResponse response) {
            var attempt = AuthenticationAttemptCoordinator.attempt(request);
            if (attempt != null && attempt.ownsPublication()) {
                // A framework session-strategy failure may follow client save, while our lock is held.
                try {
                    delegate.removeAuthorizedClient("auth0", null, request, response);
                    attempt.requireSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                }
                catch (IllegalStateException invalidated) {
                    // An invalidated original session already discarded these attributes; never touch another.
                }
            }
        }
    }

    static final class TimedAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
        private static final String ADMITTED_FLOW = TimedAuthorizationRequests.class.getName() + ".admitted";
        private final HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();
        private final Clock clock;
        TimedAuthorizationRequests(Clock clock) { this.clock = clock; }
        @Override
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
            return locked(request, false, delegate::loadAuthorizationRequest);
        }
        @Override
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request,
                                             HttpServletResponse response) {
            locked(request, authorization != null, pinned -> {
                delegate.saveAuthorizationRequest(authorization, pinned, response);
                return null;
            });
        }
        @Override
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
            OAuth2AuthorizationRequest saved;
            var attempt = AuthenticationAttemptCoordinator.attempt(request);
            if (attempt == null) {
                saved = locked(request, false, pinned -> delegate.removeAuthorizationRequest(pinned, response));
            }
            else {
                attempt.requireSession();
                saved = (OAuth2AuthorizationRequest) request.getAttribute(ADMITTED_FLOW);
                request.removeAttribute(ADMITTED_FLOW);
            }
            if (saved != null) {
                Object started = saved.getAttributes().get(FLOW_STARTED);
                if (!(started instanceof Instant start) || start.isAfter(clock.instant())
                        || !clock.instant().isBefore(start.plus(AuthenticationProperties.FLOW_LIFETIME))) {
                    throw CreastrixOidcUserService.rejected();
                }
                request.setAttribute(FLOW_STARTED, started);
            }
            return saved;
        }

        AuthenticationAttemptCoordinator.Attempt admit(HttpServletRequest request, HttpServletResponse response) {
            return locked(request, false, pinned -> {
                var session = pinned.getSession();
                var attempt = AuthenticationAttemptCoordinator.forSession(session)
                        .admit(session, request.getParameter("state"));
                if (attempt != null) {
                    try {
                        // Capture/consume the exact saved flow in the same atomic section as admission.
                        // A later OAuth entry can save its own flow without replacing this owner's flow.
                        if (authorizationResponse(request)) {
                            request.setAttribute(ADMITTED_FLOW, delegate.removeAuthorizationRequest(pinned, response));
                        }
                    }
                    catch (RuntimeException | Error failure) {
                        attempt.finish();
                        throw failure;
                    }
                }
                return attempt;
            });
        }

        private static boolean authorizationResponse(HttpServletRequest request) {
            // Only gate early consumption; Spring still performs the actual OIDC response validation.
            return StringUtils.hasText(request.getParameter("state"))
                    && (StringUtils.hasText(request.getParameter("code"))
                    || StringUtils.hasText(request.getParameter("error")));
        }

        void discardConflicting(HttpServletRequest request, HttpServletResponse response) {
            if (!authorizationResponse(request)) {
                return;
            }
            // The framework compares state; compare/remove and a newer flow's save are atomic together.
            locked(request, false, pinned -> {
                var session = pinned.getSession();
                var coordinator = AuthenticationAttemptCoordinator.forSession(session);
                // A duplicate can arrive after admission but BEFORE the owner consumes its request.
                if (!coordinator.ownsFlow(request.getParameter("state"))) {
                    delegate.removeAuthorizationRequest(pinned, response);
                }
                return null;
            });
        }

        private <T> T locked(HttpServletRequest request, boolean create, Function<HttpServletRequest, T> action) {
            var session = request.getSession(create);
            if (session == null) {
                return null;
            }
            AuthenticationAttemptCoordinator coordinator;
            try {
                coordinator = AuthenticationAttemptCoordinator.forSession(session);
            }
            catch (IllegalStateException invalidated) {
                throw CreastrixOidcUserService.rejected();
            }
            coordinator.lock();
            try {
                if (!coordinator.live(session)) {
                    throw CreastrixOidcUserService.rejected();
                }
                // Even rejected callbacks must not let delegate cleanup create a session after invalidation.
                var pinned = new HttpServletRequestWrapper(request) {
                    @Override public HttpSession getSession() {
                        if (!coordinator.live(session)) {
                            throw CreastrixOidcUserService.rejected();
                        }
                        return session;
                    }
                    @Override public HttpSession getSession(boolean createSession) {
                        return coordinator.live(session) ? session : createSession ? getSession() : null;
                    }
                };
                return action.apply(pinned);
            }
            catch (IllegalStateException invalidated) {
                throw CreastrixOidcUserService.rejected();
            }
            finally {
                coordinator.unlock();
            }
        }
    }

    static final class RequestBoundary extends OncePerRequestFilter {
        private final AuthenticationProperties properties;
        private final Clock clock;
        private final TimedAuthorizationRequests requests;
        RequestBoundary(AuthenticationProperties properties, Clock clock, TimedAuthorizationRequests requests) {
            this.properties = properties;
            this.clock = clock;
            this.requests = requests;
        }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Referrer-Policy", "no-referrer");
            boolean entry = ENTRY.matches(request);
            boolean callback = CALLBACK.matches(request);
            boolean boundary = AUTH_ROUTES.matches(request) || PRIVATE_API.matches(request)
                    || ENTRY_ROUTES.matches(request) || CALLBACK_ROUTES.matches(request);
            if (boundary && !properties.browserAuthority().equals(request.getHeader("Host"))) {
                jsonError(response, 403);
                return;
            }
            if (CALLBACK_ROUTES.matches(request) && (!callback || !"GET".equals(request.getMethod()))) {
                jsonError(response, 403);
                return;
            }
            if (ENTRY_ROUTES.matches(request) && (!entry || !"GET".equals(request.getMethod()))) {
                jsonError(response, 403);
                return;
            }
            if (boundary && !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                    && !properties.browserOrigin().equals(request.getHeader("Origin"))) {
                jsonError(response, 403);
                return;
            }
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (entry && authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                // Entry is not callback navigation; keep the fixed JSON conflict for this endpoint.
                jsonError(response, 409);
                return;
            }
            if (entry) {
                var session = request.getSession(false);
                Object intent = session == null ? null : session.getAttribute(LOGIN_INTENT);
                if (session != null) {
                    session.removeAttribute(LOGIN_INTENT);
                }
                if (!"GET".equals(request.getMethod()) || !(intent instanceof Instant start)
                        || start.isAfter(clock.instant())
                        || !clock.instant().isBefore(start.plus(AuthenticationProperties.FLOW_LIFETIME))) {
                    jsonError(response, 403);
                    return;
                }
            }
            AuthenticationAttemptCoordinator.Attempt attempt = null;
            try {
                if (callback) {
                    attempt = requests.admit(request, response);
                    if (attempt == null) {
                        requests.discardConflicting(request, response);
                        redirect(response, properties.failureUri());
                        return;
                    }
                    request = attempt.pin(request);
                }
                chain.doFilter(request, response);
            }
            catch (AuthenticationAttemptCoordinator.Unavailable contention) {
                if (attempt != null) {
                    attempt.reject();
                }
                if (response.isCommitted()) {
                    throw contention;
                }
                // No bypass if a local publication section cannot be entered within its fixed budget.
                if (callback) {
                    redirect(response, properties.failureUri());
                }
                else {
                    jsonError(response, 503);
                }
            }
            catch (OAuth2AuthenticationException rejected) {
                if (attempt != null) {
                    attempt.reject();
                }
                if (!callback || response.isCommitted()) {
                    throw rejected;
                }
                redirect(response, properties.failureUri());
            }
            finally {
                if (attempt != null) {
                    try {
                        attempt.discardRejectedSessionCookie(response);
                    }
                    finally {
                        attempt.finish();
                    }
                }
            }
        }
    }

    static void redirect(HttpServletResponse response, String location) {
        response.setStatus(303);
        response.setHeader("Location", location);
        response.setHeader("Cache-Control", "no-store");
    }

    static void jsonError(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"request_not_completed\"}");
    }

    static void invalidate(HttpServletRequest request, HttpServletResponse response, AuthenticationProperties properties) {
        var session = request.getSession(false);
        if (session != null) {
            AuthenticationAttemptCoordinator.revoke(session);
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        expireCookie(response, properties);
    }

    static void expireCookie(HttpServletResponse response, AuthenticationProperties properties) {
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.secureCookie());
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
