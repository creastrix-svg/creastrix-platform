package com.creastrix.platform.platformaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.ReadReason;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.State;
import com.creastrix.platform.platformaccess.domain.PlatformPermission;
import com.creastrix.platform.platformaccess.domain.PlatformRole;
import com.creastrix.platform.user.domain.UserStatus;

/**
 * Closed values for the unwired I2A workflow. IDs and retained records are not
 * authority. Public failures contain neither stored binding fields nor causes;
 * the service retains policy and technical classifications internally.
 */
public final class PlatformAccessOperations {
    private PlatformAccessOperations() { }

    public enum Kind { USER_SECURITY_READ, SUPPORT_REVOKE }
    public enum OutcomeKind { READ_ADMITTED, REVOKED }

    /** Complete version-one binding; equality must never be replaced by a digest. */
    public record Intent(UUID initiatorUserId, UUID operationId, Kind kind,
                         UUID targetUserId, UUID targetGrantId, ReadReason readReason,
                         Long expectedRevision) {
        public boolean valid() {
            if (initiatorUserId == null || operationId == null || kind == null || targetUserId == null) {
                return false;
            }
            return switch (kind) {
                case USER_SECURITY_READ -> targetGrantId == null && expectedRevision == null
                        && (readReason == ReadReason.USER_REQUESTED_SUPPORT || readReason == ReadReason.SECURITY_REVIEW);
                case SUPPORT_REVOKE -> targetGrantId != null && readReason == null
                        && expectedRevision != null && expectedRevision > 0;
            };
        }
    }

    public record RegisteredIntent(Intent intent, Instant registeredAt, UUID correlationId,
                                   UUID actorGrantId, long actorGrantRevision) { }
    public record StoredOutcome(OutcomeKind kind, Instant admittedAt, UUID actorGrantId,
                                long actorGrantRevision, UUID auditEventId, State beforeState,
                                Long beforeRevision, State afterState, Long afterRevision) { }

    public record RegistrationAck(Intent intent, Instant registeredAt) { }
    public record UserSecurity(UUID userId, UserStatus accountStatus) { }
    /** Historical admission only: deliberately contains no old or current account status. */
    public record Receipt(Intent intent, Instant registeredAt, Instant admittedAt, OutcomeKind kind,
                          State beforeState, Long beforeRevision, State afterState, Long afterRevision) { }

    public sealed interface Result<T> permits Success, Failure, Denied { }
    public record Success<T>(T payload) implements Result<T> {
        public Success { Objects.requireNonNull(payload, "A success requires its closed payload."); }
    }
    public record Failure<T>(Code code) implements Result<T> {
        public Failure { Objects.requireNonNull(code, "A failure requires its closed code."); }
        public String message() { return code.message(); }
    }
    public record Denied<T>() implements Result<T> {
        public String message() { return "The operation is not authorized."; }
    }

    public enum Code {
        REGISTRATION_ACKNOWLEDGED("Registration is acknowledged."),
        ID_UNAVAILABLE("ID is unavailable for this registration."),
        NO_EXECUTABLE_INTENT("No executable intent is available."),
        NO_DISCLOSABLE_RECEIPT("No disclosable receipt is available."),
        INVALID_INPUT("The operation input is invalid."),
        AMBIENT_TRANSACTION("An ambient transaction is not permitted."),
        TERMINAL_RECEIPT_REQUIRED("A separate authorized receipt request is required."),
        AUDIT_UNAVAILABLE("Required audit is unavailable."),
        DATABASE_UNAVAILABLE("The database is unavailable."),
        DEADLINE_EXCEEDED("The operation deadline was exceeded."),
        INTERRUPTED("The operation was interrupted."),
        COMMIT_UNKNOWN("The transaction outcome is unknown."),
        UNEXPECTED_FAILURE("The operation could not be completed.");

        private final String message;
        Code(String message) { this.message = message; }
        public String message() { return message; }
    }

    public enum AuditKind { INTENT_RECORDED, READ_ADMITTED, GRANT_REVOKED, ACCESS_DENIED, OPERATION_FAILED }
    public enum AuditOutcome { REGISTERED, ADMITTED, COMMITTED, DENIED, CONFIRMED_FAILED }
    public enum AuditProjection { USER_SECURITY, OPERATION_RECEIPT }
    public enum TargetVerification { SUPPLIED, VERIFIED }
    /** Internal finite reasons; none is an arbitrary message or public mismatch payload. */
    public enum AuditReason {
        USER_REQUESTED_SUPPORT, SECURITY_REVIEW, ACCESS_REVIEW,
        MISSING_FACTS, INCONSISTENT_FACTS, UNKNOWN_VERSION, INACTIVE_ACTOR,
        INVALID_GRANT, SLOT_MISMATCH, INVALID_SCOPE, INVALID_VALIDITY,
        NOT_YET_VALID, EXPIRED, PERMISSION_DENIED, ACTION_MISMATCH,
        RESOURCE_MISMATCH, REASON_DENIED, ASSURANCE_REQUIRED, STAMP_MISMATCH,
        TIME_INVALID, ASSURANCE_EXPIRED, STEP_UP_REQUIRED, STEP_UP_EXPIRED,
        SELF_TARGET, OWNER_TARGET, INACTIVE_TARGET, OCCUPIED_SLOT, STATE_DENIED,
        STALE_REVISION, NO_CHANGE, REVISION_OVERFLOW, INVITATION_MISMATCH,
        INTENT_ABSENT, INTENT_MISMATCH, INTENT_UNRESOLVED, HISTORICAL_INITIATOR_UNAVAILABLE,
        ID_UNAVAILABLE, INVALID_INPUT, TERMINAL_RECEIPT_REQUIRED,
        AUDIT_UNAVAILABLE, DATABASE_UNAVAILABLE, DEADLINE_EXCEEDED, INTERRUPTED, UNEXPECTED_FAILURE
    }

    /**
     * Exact V12 audit shape. Supplied UUIDs stay separate from verified foreign
     * references, so a denial need not look up or invent another target's data.
     * Catalog, bundle and event versions are fixed at one by this boundary.
     */
    public record AuditEvent(UUID id, AuditKind kind, Instant at, UUID correlationId, UUID attemptId,
                             UUID actorUserId, UUID actorGrantId, Long actorGrantRevision, PlatformRole actorRole,
                             UUID suppliedTargetUserId, UUID suppliedTargetGrantId, TargetVerification verification,
                             UUID targetUserId, UUID targetGrantId, UUID initiatorUserId, UUID operationId,
                             Kind action, PlatformPermission permission, AuditProjection projection, AuditReason reason,
                             AuditOutcome outcome, State beforeState, Long beforeRevision,
                             State afterState, Long afterRevision, UUID linkedEventId) { }
}
