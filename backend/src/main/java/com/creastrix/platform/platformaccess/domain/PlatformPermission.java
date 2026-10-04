package com.creastrix.platform.platformaccess.domain;

import java.util.Optional;

/**
 * Closed vocabulary for the exploratory DRAFT 0.2 policy model.
 *
 * <p>A recognized identifier is not a live capability or permission grant.
 * Maintenance and future business operations are deliberately absent.
 */
public enum PlatformPermission {

    STAFF_GRANTS_READ,
    STAFF_INVITE,
    STAFF_GRANT_CHANGE,
    STAFF_GRANT_SUSPEND_REVOKE,
    USER_SECURITY_READ,
    SECURITY_AUDIT_READ;

    /**
     * Accepts only an exact catalog identifier, without normalization or fallback.
     */
    public static Optional<PlatformPermission> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case "STAFF_GRANTS_READ" -> Optional.of(STAFF_GRANTS_READ);
            case "STAFF_INVITE" -> Optional.of(STAFF_INVITE);
            case "STAFF_GRANT_CHANGE" -> Optional.of(STAFF_GRANT_CHANGE);
            case "STAFF_GRANT_SUSPEND_REVOKE" -> Optional.of(STAFF_GRANT_SUSPEND_REVOKE);
            case "USER_SECURITY_READ" -> Optional.of(USER_SECURITY_READ);
            case "SECURITY_AUDIT_READ" -> Optional.of(SECURITY_AUDIT_READ);
            default -> Optional.empty();
        };
    }
}
