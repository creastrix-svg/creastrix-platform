package com.creastrix.platform.platformaccess.domain;

/**
 * Bounded result of a pure evaluation of supplied snapshot facts.
 *
 * <p>ALLOW is not live authorization, an executed mutation, delivered data, or
 * durable audit evidence. A projected revision describes the proposed next
 * value only; persistence must still enforce the future transaction contract.
 * Reasons contain no raw identity, request, provider, or exception payload.
 * Diagnostics belong at a future responsible application/HTTP boundary, not in
 * this side-effect-free result model.
 */
public record PlatformAccessDecision(Code code, Projection projection, Long projectedNextRevision) {

    public boolean allowed() {
        return code == Code.ALLOW;
    }

    public static PlatformAccessDecision deny(Code code) {
        if (code == null || code == Code.ALLOW) {
            throw new IllegalArgumentException("A denial requires a denial code.");
        }
        return new PlatformAccessDecision(code, Projection.NONE, null);
    }

    public static PlatformAccessDecision allow(Projection projection, Long projectedNextRevision) {
        return new PlatformAccessDecision(Code.ALLOW, projection, projectedNextRevision);
    }

    public enum Projection {
        NONE,
        USER_SECURITY,
        GRANT_SECURITY_METADATA,
        SECURITY_AUDIT
    }

    public enum Code {
        ALLOW,
        MISSING_FACTS,
        INCONSISTENT_FACTS,
        UNKNOWN_VERSION,
        INACTIVE_ACTOR,
        INVALID_GRANT,
        SLOT_MISMATCH,
        INVALID_SCOPE,
        INVALID_VALIDITY,
        NOT_YET_VALID,
        EXPIRED,
        PERMISSION_DENIED,
        ACTION_MISMATCH,
        RESOURCE_MISMATCH,
        REASON_DENIED,
        ASSURANCE_REQUIRED,
        STAMP_MISMATCH,
        TIME_INVALID,
        ASSURANCE_EXPIRED,
        STEP_UP_REQUIRED,
        STEP_UP_EXPIRED,
        SELF_TARGET,
        OWNER_TARGET,
        INACTIVE_TARGET,
        OCCUPIED_SLOT,
        STATE_DENIED,
        STALE_REVISION,
        NO_CHANGE,
        REVISION_OVERFLOW,
        INVITATION_MISMATCH
    }
}
