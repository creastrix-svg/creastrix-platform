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

    public AccountController(AuthenticationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "parameterName", token.getParameterName(),
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
        request.getSession().setAttribute(AuthenticationConfiguration.LOGIN_INTENT, clock.instant());
        AuthenticationConfiguration.redirect(response, properties.entryUri());
    }

    @GetMapping("/api/me")
    public Map<String, String> me(Authentication authentication) {
        CreastrixPrincipal principal = (CreastrixPrincipal) authentication.getPrincipal();
        return Map.of("id", principal.userId().toString(), "status", "ACTIVE");
    }
}
