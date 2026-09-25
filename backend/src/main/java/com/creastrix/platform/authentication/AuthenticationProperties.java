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
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(browserOrigin);
        if (issuer.isBlank() || clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("Authentication configuration is incomplete");
        }
        URI origin = URI.create(browserOrigin);
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !origin.getRawPath().isEmpty()
                || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))) {
            throw new IllegalArgumentException("Invalid browser origin");
        }
        allowedSubjects = Set.copyOf(Objects.requireNonNull(allowedSubjects));
        if (allowedSubjects.stream().anyMatch(subject -> subject.isBlank() || !subject.equals(subject.strip()))) {
            throw new IllegalArgumentException("Invalid admission configuration");
        }
    }

    /** Called only by runtime wiring. HTTP issuers are possible only in test-owned bean wiring. */
    public void validateRuntime(boolean localProfile) {
        URI provider = URI.create(issuer);
        if (!"https".equals(provider.getScheme()) || provider.getHost() == null
                || !provider.getHost().endsWith(".eu.auth0.com") || provider.getPort() != -1
                || provider.getUserInfo() != null || provider.getRawQuery() != null
                || provider.getRawFragment() != null || !"/".equals(provider.getRawPath())) {
            throw new IllegalArgumentException("An exact standard EU HTTPS issuer is required");
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

    public String browserAuthority() { return URI.create(browserOrigin).getRawAuthority(); }
    public String callbackUri() { return browserOrigin + "/login/oauth2/code/auth0"; }
    public String entryUri() { return browserOrigin + "/oauth2/authorization/auth0"; }
    public String successUri() { return browserOrigin + "/account"; }
    public String failureUri() { return browserOrigin + "/login?auth=failed"; }

    @Override
    public String toString() { return "AuthenticationProperties[redacted]"; }
}
