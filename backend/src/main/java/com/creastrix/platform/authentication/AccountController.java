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
        login(authentication,request,response,null);
    }

    void login(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
               LocalCartSessionBinding cart) throws IOException {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            AuthenticationConfiguration.jsonError(response, 409);
            return;
        }
        var session = request.getSession(false);
        var coordinator = AuthenticationAttemptCoordinator.existing(session);
        var stamp=contexts.capture(session);
        var q=BrowserSessionContextRegistry.cookie(request);
        LocalCartSessionBinding.GuestLogin guestLogin=null;
        if(cart!=null) {
            if(coordinator==null || !contexts.canStart(q,stamp)) {
                AuthenticationConfiguration.jsonError(response,409);
                return;
            }
            // Commit the one-use nonce outside the servlet mutex. Publication still
            // rechecks the captured stamp after this I/O; a lost reservation stays spent.
            try { guestLogin=cart.reserveGuestLogin(request); }
            catch(LocalCartSessionBinding.Refusal | com.creastrix.platform.accountcart.LocalCartCommands.Conflict refused) {
                AuthenticationConfiguration.jsonError(response,409);
                return;
            } catch(com.creastrix.platform.accountcart.LocalCartCommands.Unavailable unavailable) {
                AuthenticationConfiguration.jsonError(response,503);
                return;
            }
        }
        if (coordinator == null || !coordinator.publishLoginIntent(session, contexts,
                q, stamp, clock, guestLogin)) {
            AuthenticationConfiguration.jsonError(response, 409);
            return;
        }
        AuthenticationConfiguration.redirect(response, properties.entryUri()+(guestLogin==null ? "" : "?"+guestLogin.query()));
    }

    @GetMapping("/api/me")
    public Map<String, String> me(Authentication authentication) {
        CreastrixPrincipal principal = (CreastrixPrincipal) authentication.getPrincipal();
        return Map.of("id", principal.userId().toString(), "status", "ACTIVE");
    }
}
