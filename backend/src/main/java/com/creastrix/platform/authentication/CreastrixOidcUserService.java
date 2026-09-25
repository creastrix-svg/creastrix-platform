package com.creastrix.platform.authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.creastrix.platform.user.application.AuthenticatedUserService;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Standard OIDC protocol processing first; only then the internal identity transaction. */
public final class CreastrixOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OidcUserService delegate;
    private final AuthenticatedUserService users;
    private final AuthenticationProperties properties;
    private final Clock clock;

    public CreastrixOidcUserService(OidcUserService delegate, AuthenticatedUserService users,
                                   AuthenticationProperties properties, Clock clock) {
        this.delegate = delegate;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        // The Spring provider validates the signed ID token, state and nonce before this call.
        OidcUser provider = delegate.loadUser(request);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        Object start = attributes == null ? null
                : attributes.getRequest().getAttribute(AuthenticationConfiguration.FLOW_STARTED);
        if (!(start instanceof Instant started)) {
            throw rejected();
        }
        Identity identity = verifiedIdentity(provider, properties, started, clock.instant());
        try {
            var user = users.resolve(identity, properties::admits);
            return new CreastrixPrincipal(user.id(), identity, clock.instant(), provider);
        } catch (RuntimeException failure) {
            // No transaction outcome, provider claims, SQL diagnostic or token leaves this boundary.
            throw rejected();
        }
    }

    static Identity verifiedIdentity(OidcUser provider, AuthenticationProperties properties,
                                     Instant started, Instant now) {
        Map<String, Object> claims = provider.getIdToken().getClaims();
        Object subject = claims.get("sub");
        Object issuer = claims.get("iss");
        if (!(subject instanceof String value) || value.isBlank()
                || issuer == null || !properties.issuer().equals(issuer.toString())) {
            throw rejected();
        }
        requireVerifiedEmail(claims);
        if (provider.getUserInfo() != null) {
            requireVerifiedEmail(provider.getUserInfo().getClaims());
            if (!value.equals(provider.getUserInfo().getSubject())) {
                throw rejected();
            }
        }
        Object authenticated = claims.get("auth_time");
        if (!(authenticated instanceof Instant authenticationTime)
                || started.isAfter(now) || !now.isBefore(started.plus(AuthenticationProperties.FLOW_LIFETIME))
                || authenticationTime.isBefore(started.minus(AuthenticationProperties.CLOCK_SKEW))
                || authenticationTime.isAfter(now.plus(AuthenticationProperties.CLOCK_SKEW))) {
            throw rejected();
        }
        Identity identity = new Identity(properties.issuer(), value);
        if (!properties.admits(identity)) {
            throw rejected();
        }
        return identity;
    }

    private static void requireVerifiedEmail(Map<String, Object> claims) {
        if (!Boolean.TRUE.equals(claims.get("email_verified"))
                || !(claims.get("email") instanceof String email) || email.isBlank()) {
            throw rejected();
        }
    }

    /** Must wrap the framework converter, never inspect only its already-coerced output. */
    static Converter<Map<String, Object>, Map<String, Object>> strictClaims(
            Converter<Map<String, Object>, Map<String, Object>> converter, boolean idToken) {
        return raw -> {
            if (raw.containsKey("email_verified") && !(raw.get("email_verified") instanceof Boolean)) {
                throw rejected();
            }
            if (idToken || raw.containsKey("auth_time")) {
                Object time = raw.get("auth_time");
                if (!(time instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() < 0 || number.doubleValue() >= Long.MAX_VALUE
                        || number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw rejected();
                }
            }
            if (raw.containsKey("sub") && !(raw.get("sub") instanceof String)) {
                throw rejected();
            }
            if (raw.containsKey("email") && !(raw.get("email") instanceof String)) {
                throw rejected();
            }
            return converter.convert(raw);
        };
    }

    static OAuth2AuthenticationException rejected() {
        return new OAuth2AuthenticationException(new OAuth2Error("authentication_failed"));
    }
}
