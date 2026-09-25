package com.creastrix.platform.authentication;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Single-instance, logical-session ownership; never held across provider or database work. */
final class AuthenticationAttemptCoordinator implements HttpSessionBindingListener {
    private static final String SESSION_KEY = AuthenticationAttemptCoordinator.class.getName();
    private static final String REQUEST_KEY = SESSION_KEY + ".attempt";
    private final ReentrantLock publication = new ReentrantLock();
    private final AtomicReference<Attempt> owner = new AtomicReference<>();
    private volatile boolean revoked;

    static AuthenticationAttemptCoordinator forSession(HttpSession session) {
        // Initialization does not acquire the publication lock while holding the session mutex.
        synchronized (session) {
            Object existing = session.getAttribute(SESSION_KEY);
            if (existing instanceof AuthenticationAttemptCoordinator coordinator) {
                return coordinator;
            }
            var coordinator = new AuthenticationAttemptCoordinator();
            session.setAttribute(SESSION_KEY, coordinator);
            return coordinator;
        }
    }

    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        // Container invalidation can hold its internal monitor: never lock/call the session here.
        revoked = true;
    }

    Attempt admit(HttpSession session, String state) {
        if (!publication.tryLock()) {
            return null;
        }
        try {
            if (!live(session) || authenticated(session)) {
                return null;
            }
            Attempt attempt = new Attempt(this, session, state);
            return owner.compareAndSet(null, attempt) ? attempt : null;
        }
        catch (IllegalStateException invalidated) {
            return null;
        }
        finally {
            publication.unlock();
        }
    }

    boolean ownsFlow(String state) {
        Attempt current = owner.get();
        return current != null && state != null && state.equals(current.state);
    }

    boolean authenticated(HttpSession session) {
        Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(value instanceof SecurityContext context)) {
            return false;
        }
        var authentication = context.getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    boolean live(HttpSession session) {
        if (revoked) {
            return false;
        }
        try {
            return session.getAttribute(SESSION_KEY) == this && !revoked;
        }
        catch (IllegalStateException invalidated) {
            return false;
        }
    }

    void lock() {
        try {
            if (publication.tryLock(1, TimeUnit.SECONDS)) {
                return;
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        throw new Unavailable();
    }

    void unlock() { publication.unlock(); }

    static void revoke(HttpSession session) {
        if (session == null) {
            return;
        }
        AuthenticationAttemptCoordinator coordinator;
        try {
            coordinator = forSession(session);
        }
        catch (IllegalStateException alreadyInvalid) {
            return;
        }
        coordinator.lock();
        try {
            coordinator.revoked = true;
        }
        finally {
            coordinator.unlock();
        }
    }

    static Attempt attempt(HttpServletRequest request) {
        return (Attempt) request.getAttribute(REQUEST_KEY);
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() { super("Authentication session operation not completed"); }
    }

    static final class Attempt {
        private final AuthenticationAttemptCoordinator coordinator;
        private final HttpSession session;
        private final String state;
        private boolean publishing;
        private String rotatedSessionId;
        private boolean rejected;

        private Attempt(AuthenticationAttemptCoordinator coordinator, HttpSession session, String state) {
            this.coordinator = coordinator;
            this.session = session;
            this.state = state;
        }

        HttpServletRequest pin(HttpServletRequest request) {
            request.setAttribute(REQUEST_KEY, this);
            return new HttpServletRequestWrapper(request) {
                @Override public HttpSession getSession() { return requireSession(); }
                @Override public HttpSession getSession(boolean create) {
                    // A stale callback must never fall back to creating a replacement session.
                    return live() ? session : create ? requireSession() : null;
                }
                @Override public String changeSessionId() {
                    requireSession();
                    try {
                        String changed = super.changeSessionId();
                        // Tomcat writes the rotation cookie directly, before returning through this wrapper.
                        // Record ownership before the liveness check can reject an invalidated session.
                        rotatedSessionId = changed;
                        requireSession();
                        return changed;
                    }
                    catch (IllegalStateException invalidated) {
                        throw CreastrixOidcUserService.rejected();
                    }
                }
            };
        }

        private boolean live() {
            return coordinator.owner.get() == this && coordinator.live(session);
        }

        HttpSession requireSession() {
            if (!live()) {
                throw CreastrixOidcUserService.rejected();
            }
            return session;
        }

        void beginPublication() {
            coordinator.lock();
            boolean accepted = false;
            try {
                requireSession();
                if (coordinator.authenticated(session)) {
                    throw CreastrixOidcUserService.rejected();
                }
                publishing = true;
                accepted = true;
            }
            catch (IllegalStateException invalidated) {
                throw CreastrixOidcUserService.rejected();
            }
            finally {
                if (!accepted) {
                    coordinator.unlock();
                }
            }
            // Keep only the local client/rotation/context/success segment serialized with logout.
            // RequestBoundary finally releases this exact owner's lock even on framework failure.
        }

        boolean ownsPublication() { return publishing && live(); }

        void reject() { rejected = true; }

        void discardRejectedSessionCookie(HttpServletResponse response) {
            if (!rejected || rotatedSessionId == null || response.isCommitted()) {
                return;
            }
            var cookies = response.getHeaders("Set-Cookie");
            var retained = cookies.stream().filter(header -> !ownsSessionCookie(header)).toList();
            if (retained.size() != cookies.size()) {
                // Only this header is rebuilt; unrelated values and all other response headers survive.
                response.setHeader("Set-Cookie", null);
                retained.forEach(header -> response.addHeader("Set-Cookie", header));
            }
        }

        private boolean ownsSessionCookie(String header) {
            // Exact current pilot scope: JSESSIONID, host-only, Path=/; not a general cookie parser.
            // A name alone, an empty deletion value or an ambiguous scope does not establish ownership.
            String[] parts = header.split(";", -1);
            if (!parts[0].trim().equals("JSESSIONID=" + rotatedSessionId)) {
                return false;
            }
            String cookiePath = null;
            for (int i = 1; i < parts.length; i++) {
                String attribute = parts[i].trim();
                int separator = attribute.indexOf('=');
                String name = (separator < 0 ? attribute : attribute.substring(0, separator)).trim();
                if (name.equalsIgnoreCase("Domain")) {
                    return false;
                }
                if (name.equalsIgnoreCase("Path")) {
                    if (cookiePath != null) {
                        return false;
                    }
                    cookiePath = separator < 0 ? "" : attribute.substring(separator + 1).trim();
                }
            }
            return "/".equals(cookiePath);
        }

        void finish() {
            coordinator.owner.compareAndSet(this, null);
            if (publishing) {
                publishing = false;
                coordinator.unlock();
            }
        }
    }
}
