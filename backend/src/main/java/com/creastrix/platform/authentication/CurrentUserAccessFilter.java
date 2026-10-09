package com.creastrix.platform.authentication;

import java.io.IOException;
import java.time.Clock;

import com.creastrix.platform.observability.Diagnostics;
import com.creastrix.platform.user.application.AuthenticatedUserService;
import com.creastrix.platform.user.domain.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only private API access; CSRF bootstrap and local logout remain usable for cleanup. */
public final class CurrentUserAccessFilter extends OncePerRequestFilter {
    private final AuthenticatedUserService users;
    private final AuthenticationProperties properties;
    private final Clock clock;
    private final BrowserSessionContextRegistry contexts;

    // Disposable-prototype observation only: hooks never supply/replace an admission decision.
    interface GateObserver {
        default void beforeFinal(HttpServletRequest request, BrowserSessionContextRegistry.Stamp stamp) { }
        default void afterFinal(HttpServletRequest request, BrowserSessionContextRegistry.Stamp stamp, boolean admitted) { }
    }
    static volatile GateObserver gateObserver = new GateObserver() { };

    public CurrentUserAccessFilter(AuthenticatedUserService users, AuthenticationProperties properties, Clock clock,
                                  BrowserSessionContextRegistry contexts) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
        this.contexts = contexts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuthenticationConfiguration.PRIVATE_API.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CreastrixPrincipal principal)) {
            // Missing session is not proof of idle expiry (logout/restart are also possible).
            Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.NO_AUTHENTICATED_SESSION);
            AuthenticationConfiguration.jsonError(response, 401);
            return;
        }
        if (!clock.instant().isBefore(principal.authenticatedAt().plus(AuthenticationProperties.ABSOLUTE_LIFETIME))) {
            Diagnostics.mark(Diagnostics.Event.SESSION_EXPIRED, Diagnostics.Reason.ABSOLUTE_LIFETIME_EXPIRED);
            AuthenticationConfiguration.invalidate(request, response, properties);
            AuthenticationConfiguration.jsonError(response, 401);
            return;
        }
        // Capture once before DB. Never borrow a later generation from the registry.
        String q = BrowserSessionContextRegistry.cookie(request);
        var captured = contexts.capture(request.getSession(false));
        if (!contexts.echo(q, captured)) {
            AuthenticationConfiguration.jsonError(response, 401);
            return;
        }
        if (!properties.admits(principal.identity())) {
            Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.ADMISSION_DENIED);
            AuthenticationConfiguration.invalidate(request, response, properties);
            AuthenticationConfiguration.jsonError(response, 403);
            return;
        }
        try {
            var current = users.currentUser(principal.identity(), properties::admits);
            if (current.isEmpty() || !principal.userId().equals(current.get().id())
                    || current.get().status() != UserStatus.ACTIVE) {
                Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.CURRENT_USER_DENIED);
                AuthenticationConfiguration.invalidate(request, response, properties);
                AuthenticationConfiguration.jsonError(response, 403);
                return;
            }
        } catch (AuthenticatedUserService.AdmissionDeniedException denied) {
            Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.ADMISSION_DENIED);
            AuthenticationConfiguration.invalidate(request, response, properties);
            AuthenticationConfiguration.jsonError(response, 403);
            return;
        } catch (RuntimeException unavailable) {
            // Unknown database state is not evidence of inactivity or successful logout.
            Diagnostics.mark(Diagnostics.Event.REQUEST_FAILURE,
                    unavailable instanceof AuthenticatedUserService.ResolutionUnavailableException resolution
                            ? resolution.diagnosticReason() : Diagnostics.failureReason(unavailable));
            AuthenticationConfiguration.jsonError(response, 503);
            return;
        }
        GateObserver observer = gateObserver;
        observer.beforeFinal(request, captured);
        boolean admitted = contexts.finalAdmit(q, captured);
        observer.afterFinal(request, captured, admitted);
        if (!admitted) {
            AuthenticationConfiguration.jsonError(response, 401);
            return;
        }
        chain.doFilter(request, response);
    }
}
