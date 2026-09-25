package com.creastrix.platform.authentication;

import java.io.IOException;
import java.time.Clock;

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

    public CurrentUserAccessFilter(AuthenticatedUserService users, AuthenticationProperties properties, Clock clock) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
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
            AuthenticationConfiguration.jsonError(response, 401);
            return;
        }
        if (!clock.instant().isBefore(principal.authenticatedAt().plus(AuthenticationProperties.ABSOLUTE_LIFETIME))) {
            AuthenticationConfiguration.invalidate(request, response, properties);
            AuthenticationConfiguration.jsonError(response, 401);
            return;
        }
        if (!properties.admits(principal.identity())) {
            AuthenticationConfiguration.invalidate(request, response, properties);
            AuthenticationConfiguration.jsonError(response, 403);
            return;
        }
        try {
            var current = users.currentUser(principal.identity(), properties::admits);
            if (current.isEmpty() || !principal.userId().equals(current.get().id())
                    || current.get().status() != UserStatus.ACTIVE) {
                AuthenticationConfiguration.invalidate(request, response, properties);
                AuthenticationConfiguration.jsonError(response, 403);
                return;
            }
        } catch (AuthenticatedUserService.AdmissionDeniedException denied) {
            AuthenticationConfiguration.invalidate(request, response, properties);
            AuthenticationConfiguration.jsonError(response, 403);
            return;
        } catch (RuntimeException unavailable) {
            // Unknown database state is not evidence of inactivity or successful logout.
            AuthenticationConfiguration.jsonError(response, 503);
            return;
        }
        chain.doFilter(request, response);
    }
}
