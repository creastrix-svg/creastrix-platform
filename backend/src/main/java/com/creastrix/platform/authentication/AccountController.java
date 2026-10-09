package com.creastrix.platform.authentication;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON/redirect contract only. No frontend pages and no caller-selected User authority. */
@RestController
@ConditionalOnProperty(name = "creastrix.auth.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AccountController {
    private final AuthenticationProperties properties;
    private final Clock clock;
    private final BrowserSessionContextRegistry contexts;

    public AccountController(AuthenticationProperties properties, Clock clock, BrowserSessionContextRegistry contexts) {
        this.properties = properties;
        this.clock = clock;
        this.contexts = contexts;
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Capture before deferred CSRF materializes a session. An unresolved stale SID is
        // cleanup-only too; isNew() on an existing session is not proof of fresh creation.
        boolean freshWithoutRequestedSession = request.getSession(false) == null
                && request.getRequestedSessionId() == null;
        String csrf = token.getToken(); // Standard token materialization creates the anonymous session if needed.
        var session = request.getSession(false);
        var coordinator = AuthenticationAttemptCoordinator.forSession(session);
        String q = BrowserSessionContextRegistry.cookie(request);
        if (!coordinator.authenticated(session)) {
            String issued = null;
            if (q != null) {
                if (freshWithoutRequestedSession) issued = contexts.recoveryBootstrap(q, session, coordinator);
                // A restrictive recovery result never authorizes another bootstrap.
                // Ordinary live-Q attach remains separate; its failure is not recovery eligibility.
                if (issued == null) contexts.attachAnonymous(q, session, coordinator);
            }
            else if (!BrowserSessionContextRegistry.cookieSupplied(request)) {
                issued = contexts.bootstrap(session, coordinator);
            }
            if (issued != null) {
                var cookie = new jakarta.servlet.http.Cookie(BrowserSessionContextRegistry.COOKIE_NAME, issued);
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setSecure(properties.secureCookie());
                cookie.setAttribute("SameSite", "Lax");
                response.addCookie(cookie);
            }
        }
        return Map.of("token", csrf, "parameterName", token.getParameterName(),
                "headerName", token.getHeaderName());
    }

    @PostMapping("/auth/login")
    public void login(Authentication authentication, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            AuthenticationConfiguration.jsonError(response, 409);
            return;
        }
        if (!contexts.canStart(BrowserSessionContextRegistry.cookie(request), contexts.capture(request.getSession(false)))) {
            AuthenticationConfiguration.jsonError(response, 409);
            return;
        }
        request.getSession().setAttribute(AuthenticationConfiguration.LOGIN_INTENT, clock.instant());
        AuthenticationConfiguration.redirect(response, properties.entryUri());
    }

    @GetMapping("/api/me")
    public Map<String, String> me(Authentication authentication) {
        CreastrixPrincipal principal = (CreastrixPrincipal) authentication.getPrincipal();
        return Map.of("id", principal.userId().toString(), "status", "ACTIVE");
    }
}
