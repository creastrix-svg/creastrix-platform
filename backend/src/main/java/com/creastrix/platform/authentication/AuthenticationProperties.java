package com.creastrix.platform.authentication;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;

/** Immutable, restart-controlled admission for one explicitly configured issuer. */
public record AuthenticationProperties(
        String issuer, String clientId, String clientSecret, String browserOrigin,
        Set<String> allowedSubjects, boolean secureCookie) {

    public static final Duration FLOW_LIFETIME = Duration.ofMinutes(5);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    public static final Duration IDLE_LIFETIME = Duration.ofMinutes(30);
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);

    public AuthenticationProperties {
        Objects.requireNonNull(issuer, "creastrix.auth.issuer");
        Objects.requireNonNull(browserOrigin, "creastrix.auth.browser-origin");
        requireNonblank(issuer, "creastrix.auth.issuer");
        requireNonblank(clientId, "creastrix.auth.client-id");
        requireNonblank(clientSecret, "creastrix.auth.client-secret");
        requireNonblank(browserOrigin, "creastrix.auth.browser-origin");
        URI origin = configurationUri(browserOrigin, "creastrix.auth.browser-origin");
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !origin.getRawPath().isEmpty()
                || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))) {
            throw new IllegalArgumentException("creastrix.auth.browser-origin must be an HTTP(S) origin without user info, path, query or fragment");
        }
        allowedSubjects = Set.copyOf(Objects.requireNonNull(allowedSubjects));
        if (allowedSubjects.stream().anyMatch(subject -> subject.isBlank() || !subject.equals(subject.strip()))) {
            throw new IllegalArgumentException("creastrix.auth.allowed-subjects must contain nonblank, unpadded values");
        }
    }

    /** Called only by runtime wiring. HTTP issuers are possible only in test-owned bean wiring. */
    public void validateRuntime(boolean localProfile) {
        URI provider = configurationUri(issuer, "creastrix.auth.issuer");
        if (!"https".equals(provider.getScheme()) || provider.getHost() == null
                || !provider.getHost().endsWith(".eu.auth0.com") || provider.getPort() != -1
                || provider.getUserInfo() != null || provider.getRawQuery() != null
                || provider.getRawFragment() != null || !"/".equals(provider.getRawPath())) {
            throw new IllegalArgumentException("creastrix.auth.issuer must be an exact standard EU HTTPS issuer");
        }
        if (localProfile) {
            if (!"http://localhost:3000".equals(browserOrigin) || secureCookie) {
                throw new IllegalArgumentException("Invalid explicit local authentication configuration");
            }
        } else if (!secureCookie || !"https".equals(URI.create(browserOrigin).getScheme())) {
            throw new IllegalArgumentException("Non-local authentication requires HTTPS and secure cookies");
        }
    }

    public boolean admits(Identity identity) {
        return issuer.equals(identity.issuer()) && allowedSubjects.contains(identity.subject());
    }

    private static void requireNonblank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank");
        }
    }

    private static URI configurationUri(String value, String field) {
        try {
            return URI.create(value);
        }
        catch (IllegalArgumentException malformed) {
            // URI exceptions retain the complete input. Startup may print causes, so do not attach it.
            throw new IllegalArgumentException(field + " must be a valid URI");
        }
    }

    public String browserAuthority() { return URI.create(browserOrigin).getRawAuthority(); }
    public String callbackUri() { return browserOrigin + "/login/oauth2/code/auth0"; }
    public String entryUri() { return browserOrigin + "/oauth2/authorization/auth0"; }
    public String successUri() { return browserOrigin + "/account"; }
    public String failureUri() { return browserOrigin + "/login?auth=failed"; }

    @Override
    public String toString() { return "AuthenticationProperties[redacted]"; }
}
