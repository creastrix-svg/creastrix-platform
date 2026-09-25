package com.creastrix.platform.authentication;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Server-session principal; never a response DTO or browser authority. */
public record CreastrixPrincipal(UUID userId, Identity identity, Instant authenticatedAt,
                                OidcUser providerUser) implements OidcUser {
    @Override
    public String getName() { return userId.toString(); }
    @Override
    public Map<String, Object> getAttributes() { return providerUser.getAttributes(); }
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return providerUser.getAuthorities(); }
    @Override
    public Map<String, Object> getClaims() { return providerUser.getClaims(); }
    @Override
    public OidcUserInfo getUserInfo() { return providerUser.getUserInfo(); }
    @Override
    public OidcIdToken getIdToken() { return providerUser.getIdToken(); }
    @Override
    public String toString() { return "CreastrixPrincipal[redacted]"; }
}
