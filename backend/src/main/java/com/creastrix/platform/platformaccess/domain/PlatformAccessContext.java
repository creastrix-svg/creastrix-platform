package com.creastrix.platform.platformaccess.domain;

import com.creastrix.platform.user.domain.UserStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit immutable inputs to an unwired platform-access policy evaluation.
 *
 * <p>These reported facts are not a browser DTO or proof of authentication,
 * authoritative account generation, fresh database lookup, or verified MFA.
 * Missing or inconsistent facts are representable for typed policy denial.
 * Collection snapshots preserve malformed entries rather than silently fixing
 * them. Time is supplied explicitly; construction performs no I/O or logging.
 * The existingUsers set contains reported existing scope and resource targets;
 * the separate Actor snapshot reports the current authenticated account. It is
 * not a complete database directory, nor an alternative actor-authentication gate.
 *
 * <p>A future trusted application boundary must establish every relevant fact,
 * recheck final admission and perform any required atomic audit. No
 * {@code auditSucceeded} flag can substitute for physical transaction evidence.
 */
public record PlatformAccessContext(
        Instant now,
        Actor actor,
        PlatformAccessGrant grant,
        Slot actorSlot,
        Assurance assurance,
        Request request,
        Resource resource,
        Change change,
        Set<UUID> existingUsers
) {

    public PlatformAccessContext {
        existingUsers = existingUsers == null
                ? null
                : Collections.unmodifiableSet(new HashSet<>(existingUsers));
    }

    /**
     * Account eligibility generation is a trusted model input, not a new field
     * implemented on User or an identity issued by this policy.
     */
    public record Actor(
            UUID userId,
            UserStatus status,
            UUID accountEligibilityGeneration,
            UUID sessionGeneration
    ) {
    }

    /**
     * Reported non-revoked assignments, including suspended or expired grants.
     * This list can expose a cardinality inconsistency but cannot prove database
     * uniqueness or reserve the slot for an invitation.
     */
    public record Slot(UUID userId, List<UUID> nonRevokedGrantIds) {

        public Slot {
            nonRevokedGrantIds = nonRevokedGrantIds == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(nonRevokedGrantIds));
        }
    }

    public record Stamp(
            UUID userId,
            UUID accountEligibilityGeneration,
            UUID sessionGeneration,
            UUID grantId,
            long grantRevision
    ) {
    }

    public enum Verification {
        VERIFIED_MFA,
        VERIFIED_STEP_UP,
        UNVERIFIED
    }

    /**
     * Snapshot of a trusted adapter's claimed assurance and prior admitted
     * activity. Evaluation must not refresh that activity before checking it.
     */
    public record Assurance(
            Verification verification,
            Stamp stamp,
            Instant mfaAt,
            Instant boundAt,
            Instant lastActivityAt,
            StepUp stepUp
    ) {
    }

    /**
     * Reported evidence for the closed critical-staff purpose, not generic MFA
     * that a browser may label as step-up.
     */
    public record StepUp(Verification verification, Stamp stamp, Instant occurredAt) {
    }

    public record Request(PlatformPermission permission, Action action, ReadReason reason) {
    }

    public enum Action {
        READ,
        INVITE,
        CHANGE,
        RESUME,
        SUSPEND,
        REVOKE
    }

    public enum ReadReason {
        USER_REQUESTED_SUPPORT,
        SECURITY_REVIEW,
        ACCESS_REVIEW
    }

    /**
     * One reported exact resource; no arbitrary tenant payload or bulk query.
     */
    public sealed interface Resource permits UserResource, GrantResource, AuditResource {
    }

    public record UserResource(UUID userId, UserStatus status, UUID bindingIdentity)
            implements Resource {
    }

    public record GrantResource(PlatformAccessGrant grant, UserStatus recipientStatus)
            implements Resource {
    }

    /**
     * Only event identity and a closed catalog kind are modeled. This neither
     * persists an audit record nor proves that a historical event occurred.
     */
    public record AuditResource(UUID eventId, AuditEventKind eventKind) implements Resource {
    }

    public enum AuditEventKind {
        ACCESS_CHANGE,
        MAINTENANCE,
        USER_SECURITY,
        READ_ADMISSION,
        EXPORT,
        DENIAL_FAILURE,
        CORRECTION_RETENTION
    }

    /**
     * Proposed facts only: evaluating them does not mutate a grant, increment a
     * revision, reserve a slot, send an invitation, or perform acceptance.
     */
    public record Change(
            Slot targetSlot,
            Long expectedRevision,
            PlatformAccessGrant.Scope proposedScope,
            PlatformAccessGrant.Validity proposedValidity,
            Invitation invitation
    ) {
    }

    /**
     * Frozen proposal terms. Acceptance and durable single-use consumption are
     * deliberately absent; the absolute expiry is not recomputed on acceptance.
     */
    public record Invitation(
            UUID issuerUserId,
            UUID issuerGrantId,
            long issuerRevision,
            UUID recipientId,
            UUID recipientBindingIdentity,
            PlatformRole role,
            int catalogVersion,
            int bundleVersion,
            PlatformAccessGrant.Scope scope,
            Instant offeredAt,
            Instant expiresAt
    ) {
    }
}
