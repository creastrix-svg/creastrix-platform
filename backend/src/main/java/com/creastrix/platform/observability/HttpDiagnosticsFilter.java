package com.creastrix.platform.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Wraps security (including firewall rejection), without changing routing or HTTP output. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class HttpDiagnosticsFilter extends OncePerRequestFilter {
    private static final String[] PATHS = {"/actuator/health", "/auth/csrf", "/auth/login",
            "/oauth2/authorization/auth0", "/login/oauth2/code/auth0", "/auth/logout", "/api/**"};
    private static final Diagnostics.Route[] ROUTES = {Diagnostics.Route.HEALTH, Diagnostics.Route.CSRF,
            Diagnostics.Route.LOGIN, Diagnostics.Route.OAUTH_ENTRY, Diagnostics.Route.CALLBACK,
            Diagnostics.Route.LOGOUT, Diagnostics.Route.PRIVATE_API};
    private static final PathPatternRequestMatcher[] MATCHERS = java.util.Arrays.stream(PATHS)
            .map(path -> PathPatternRequestMatcher.withDefaults().matcher(path)).toArray(PathPatternRequestMatcher[]::new);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Diagnostics.beginRequest();
        long started = System.nanoTime();
        Throwable failed = null;
        try {
            chain.doFilter(request, response);
        }
        catch (IOException | ServletException | RuntimeException | Error failure) {
            failed = failure;
            throw failure;
        }
        finally {
            try {
                var route = route(request);
                if (failed == null) {
                    if (route == Diagnostics.Route.LOGIN && response.getStatus() == 303) {
                        Diagnostics.mark(Diagnostics.Event.LOGIN_RESULT, Diagnostics.Reason.LOGIN_INTENT_ACCEPTED);
                    }
                    else if (response.getStatus() == 401 || response.getStatus() == 403 || response.getStatus() == 409) {
                        Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.REQUEST_BOUNDARY_REJECTED);
                    }
                }
                Diagnostics.finishRequest(route, method(request), response.getStatus(), System.nanoTime() - started, failed);
            }
            catch (RuntimeException diagnosticFailure) {
                // A malformed wrapper or diagnostic sink must not replace the application result.
            }
            finally {
                Diagnostics.clearRequest();
            }
        }
    }

    private static Diagnostics.Route route(HttpServletRequest request) {
        try {
            for (int i = 0; i < MATCHERS.length; i++) {
                if (MATCHERS[i].matches(request)) {
                    return ROUTES[i];
                }
            }
        }
        catch (RuntimeException malformedPath) {
            // Firewall may already have rejected this input. Never echo or decode it for logging.
        }
        return Diagnostics.Route.OTHER;
    }

    private static Diagnostics.Method method(HttpServletRequest request) {
        String method = request.getMethod();
        for (var candidate : Diagnostics.Method.values()) {
            if (candidate.name().equals(method)) {
                return candidate;
            }
        }
        return Diagnostics.Method.OTHER;
    }
}
