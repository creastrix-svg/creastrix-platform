package com.creastrix.platform.platformaccess.domain;

import java.util.Optional;
import java.util.Set;

/**
 * Explicit, immutable v1 bundles used only by the exploratory policy model.
 *
 * <p>The owner bundle enumerates its permissions instead of inheriting all enum
 * values, so adding vocabulary cannot silently expand the existing bundle.
 */
public enum PlatformRole {

    PLATFORM_OWNER(Set.of(
            PlatformPermission.STAFF_GRANTS_READ,
            PlatformPermission.STAFF_INVITE,
            PlatformPermission.STAFF_GRANT_CHANGE,
            PlatformPermission.STAFF_GRANT_SUSPEND_REVOKE,
            PlatformPermission.USER_SECURITY_READ,
            PlatformPermission.SECURITY_AUDIT_READ)),
    SUPPORT_READ(Set.of(PlatformPermission.USER_SECURITY_READ));

    private final Set<PlatformPermission> permissions;

    PlatformRole(Set<PlatformPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<PlatformPermission> permissions() {
        return permissions;
    }

    /**
     * Does not interpret provider roles, aliases, casing, or surrounding spaces.
     */
    public static Optional<PlatformRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case "PLATFORM_OWNER" -> Optional.of(PLATFORM_OWNER);
            case "SUPPORT_READ" -> Optional.of(SUPPORT_READ);
            default -> Optional.empty();
        };
    }
}
