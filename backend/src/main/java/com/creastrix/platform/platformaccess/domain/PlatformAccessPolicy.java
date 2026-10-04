package com.creastrix.platform.platformaccess.domain;

import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Action;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.AuditResource;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.GrantResource;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.ReadReason;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Slot;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Stamp;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.UserResource;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Verification;
import com.creastrix.platform.platformaccess.domain.PlatformAccessDecision.Code;
import com.creastrix.platform.platformaccess.domain.PlatformAccessDecision.Projection;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.Scope;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.ScopeKind;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.State;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.Validity;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.ValidityKind;
import com.creastrix.platform.user.domain.UserStatus;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, unwired evaluation of ordinary platform-access predicates.
 *
 * <p>The result describes only supplied immutable facts. It does not authenticate
 * a caller, establish snapshot freshness or slot uniqueness, execute an invitation,
 * mutate state, or prove audit/commit. A future trusted boundary must refresh and
 * serialize final admission, preserve exact ownership, and perform required audit.
 * Maintenance, provider verification, storage, HTTP and browser behavior are absent.
 * No clock, I/O, logging, or mutable cache is consulted by this policy.
 */
public final class PlatformAccessPolicy {

    private static final Duration ABSOLUTE_AGE = Duration.ofMinutes(10);
    private static final Duration IDLE_AGE = Duration.ofMinutes(5);
    private static final Duration STEP_UP_AGE = Duration.ofMinutes(5);
    private static final Duration SUPPORT_TERM = Duration.ofDays(30);

    public PlatformAccessDecision evaluate(PlatformAccessContext context) {
        Code failure = common(context);
        if (failure != null) {
            return PlatformAccessDecision.deny(failure);
        }
        return context.request().action() == Action.READ ? read(context) : mutate(context);
    }

    private Code common(PlatformAccessContext c) {
        if (c == null || c.now() == null || c.actor() == null || c.grant() == null
                || c.request() == null || c.resource() == null || c.existingUsers() == null) {
            return Code.MISSING_FACTS;
        }
        if (c.existingUsers().contains(null)) {
            return Code.INCONSISTENT_FACTS;
        }
        var actor = c.actor();
        if (actor.userId() == null || actor.status() == null
                || actor.accountEligibilityGeneration() == null || actor.sessionGeneration() == null) {
            return Code.MISSING_FACTS;
        }
        if (actor.status() != UserStatus.ACTIVE) {
            return Code.INACTIVE_ACTOR;
        }
        Code shape = grantShape(c.grant(), c.existingUsers());
        if (shape != null) {
            return shape;
        }
        if (!actor.userId().equals(c.grant().recipientId())) {
            return Code.INVALID_GRANT;
        }
        if (!occupiedSlot(c.actorSlot(), actor.userId(), c.grant().id())) {
            return Code.SLOT_MISMATCH;
        }
        if (c.grant().state() != State.ACTIVE) {
            return Code.STATE_DENIED;
        }
        if (c.grant().startsAt().isAfter(c.now())) {
            return Code.NOT_YET_VALID;
        }
        if (expired(c.grant(), c.now())) {
            return Code.EXPIRED;
        }
        var request = c.request();
        if (request.permission() == null || request.action() == null) {
            return Code.MISSING_FACTS;
        }
        if (!matchesAction(request.permission(), request.action())) {
            return Code.ACTION_MISMATCH;
        }
        if (!c.grant().role().permissions().contains(request.permission())) {
            return Code.PERMISSION_DENIED;
        }
        if (request.action() == Action.READ && c.change() != null
                || request.action() != Action.READ && request.reason() != null) {
            return Code.INCONSISTENT_FACTS;
        }
        return assurance(c);
    }

    private Code grantShape(PlatformAccessGrant grant, Set<UUID> existingUsers) {
        if (grant == null || grant.id() == null || grant.recipientId() == null || grant.role() == null
                || grant.state() == null || grant.startsAt() == null || grant.revision() < 1) {
            return Code.INVALID_GRANT;
        }
        if (grant.catalogVersion() != 1 || grant.bundleVersion() != 1) {
            return Code.UNKNOWN_VERSION;
        }
        Code scope = scope(grant.scope(), grant.role(), existingUsers);
        if (scope != null) {
            return scope;
        }
        var validity = grant.validity();
        if (validity == null || validity.kind() == null) {
            return Code.INVALID_VALIDITY;
        }
        if (validity.kind() == ValidityKind.UNBOUNDED) {
            return grant.role() == PlatformRole.PLATFORM_OWNER && validity.expiresAt() == null
                    ? null : Code.INVALID_VALIDITY;
        }
        return validity.expiresAt() != null && validity.expiresAt().isAfter(grant.startsAt())
                ? null : Code.INVALID_VALIDITY;
    }

    private Code scope(Scope scope, PlatformRole role, Set<UUID> existingUsers) {
        if (scope == null || scope.kind() == null || scope.userIds() == null
                || scope.userIds().contains(null)) {
            return Code.INVALID_SCOPE;
        }
        if (role == PlatformRole.PLATFORM_OWNER) {
            return scope.kind() == ScopeKind.PLATFORM_SECURITY_METADATA && scope.userIds().isEmpty()
                    ? null : Code.INVALID_SCOPE;
        }
        return scope.kind() == ScopeKind.EXACT_USERS && !scope.userIds().isEmpty()
                && scope.userIds().size() <= 100
                && new HashSet<>(scope.userIds()).size() == scope.userIds().size()
                && existingUsers.containsAll(scope.userIds()) ? null : Code.INVALID_SCOPE;
    }

    private boolean occupiedSlot(Slot slot, UUID recipient, UUID grantId) {
        return slot != null && recipient.equals(slot.userId()) && slot.nonRevokedGrantIds() != null
                && slot.nonRevokedGrantIds().size() == 1
                && grantId.equals(slot.nonRevokedGrantIds().getFirst());
    }

    private boolean expired(PlatformAccessGrant grant, Instant now) {
        return grant.validity().kind() == ValidityKind.BOUNDED
                && !now.isBefore(grant.validity().expiresAt());
    }

    private boolean matchesAction(PlatformPermission permission, Action action) {
        return switch (permission) {
            case STAFF_GRANTS_READ, USER_SECURITY_READ, SECURITY_AUDIT_READ -> action == Action.READ;
            case STAFF_INVITE -> action == Action.INVITE;
            case STAFF_GRANT_CHANGE -> action == Action.CHANGE || action == Action.RESUME;
            case STAFF_GRANT_SUSPEND_REVOKE -> action == Action.SUSPEND || action == Action.REVOKE;
        };
    }

    private Code assurance(PlatformAccessContext c) {
        var a = c.assurance();
        if (a == null || a.verification() != Verification.VERIFIED_MFA) {
            return Code.ASSURANCE_REQUIRED;
        }
        if (!stampMatches(a.stamp(), c)) {
            return Code.STAMP_MISMATCH;
        }
        if (a.mfaAt() == null || a.boundAt() == null || a.lastActivityAt() == null
                || a.mfaAt().isAfter(a.boundAt()) || a.boundAt().isAfter(a.lastActivityAt())
                || a.lastActivityAt().isAfter(c.now())) {
            return Code.TIME_INVALID;
        }
        if (!youngerThan(a.mfaAt(), c.now(), ABSOLUTE_AGE)
                || !youngerThan(a.lastActivityAt(), c.now(), IDLE_AGE)) {
            return Code.ASSURANCE_EXPIRED;
        }
        if (c.request().action() != Action.READ) {
            var step = a.stepUp();
            if (step == null || step.verification() != Verification.VERIFIED_STEP_UP) {
                return Code.STEP_UP_REQUIRED;
            }
            if (!stampMatches(step.stamp(), c)) {
                return Code.STAMP_MISMATCH;
            }
            if (step.occurredAt() == null || step.occurredAt().isBefore(a.mfaAt())
                    || step.occurredAt().isAfter(c.now())) {
                return Code.TIME_INVALID;
            }
            if (!youngerThan(step.occurredAt(), c.now(), STEP_UP_AGE)) {
                return Code.STEP_UP_EXPIRED;
            }
        }
        return null;
    }

    private boolean stampMatches(Stamp stamp, PlatformAccessContext c) {
        return stamp != null && c.actor().userId().equals(stamp.userId())
                && c.actor().accountEligibilityGeneration().equals(stamp.accountEligibilityGeneration())
                && c.actor().sessionGeneration().equals(stamp.sessionGeneration())
                && c.grant().id().equals(stamp.grantId()) && c.grant().revision() == stamp.grantRevision();
    }

    private boolean youngerThan(Instant event, Instant now, Duration maximum) {
        // Compare mathematical durations rather than truncate seconds or add to an event near Instant.MAX.
        Duration age = Duration.between(event, now);
        return !age.isNegative() && age.compareTo(maximum) < 0;
    }

    private PlatformAccessDecision read(PlatformAccessContext c) {
        ReadReason reason = c.request().reason();
        if (reason == null) {
            return PlatformAccessDecision.deny(Code.REASON_DENIED);
        }
        return switch (c.request().permission()) {
            case USER_SECURITY_READ -> readUser(c, reason);
            case STAFF_GRANTS_READ -> {
                if (reason != ReadReason.ACCESS_REVIEW) {
                    yield PlatformAccessDecision.deny(Code.REASON_DENIED);
                }
                if (!(c.resource() instanceof GrantResource r) || r.recipientStatus() == null) {
                    yield PlatformAccessDecision.deny(Code.RESOURCE_MISMATCH);
                }
                Code shape = grantShape(r.grant(), c.existingUsers());
                if (shape == null && (!c.existingUsers().contains(r.grant().recipientId())
                        || r.grant().startsAt().isAfter(c.now()) || identityAlias(r.grant(), c)
                        || r.grant().recipientId().equals(c.actor().userId())
                        && r.recipientStatus() != c.actor().status())) {
                    shape = Code.INCONSISTENT_FACTS;
                }
                // Historical revoked/expired metadata remains readable; it confers no authority.
                yield shape == null
                        ? PlatformAccessDecision.allow(Projection.GRANT_SECURITY_METADATA, null)
                        : PlatformAccessDecision.deny(shape);
            }
            case SECURITY_AUDIT_READ -> {
                if (reason != ReadReason.ACCESS_REVIEW && reason != ReadReason.SECURITY_REVIEW) {
                    yield PlatformAccessDecision.deny(Code.REASON_DENIED);
                }
                yield c.resource() instanceof AuditResource r && r.eventId() != null && r.eventKind() != null
                        ? PlatformAccessDecision.allow(Projection.SECURITY_AUDIT, null)
                        : PlatformAccessDecision.deny(Code.RESOURCE_MISMATCH);
            }
            default -> PlatformAccessDecision.deny(Code.ACTION_MISMATCH);
        };
    }

    private PlatformAccessDecision readUser(PlatformAccessContext c, ReadReason reason) {
        if (!(c.resource() instanceof UserResource r) || r.userId() == null || r.status() == null
                || !c.existingUsers().contains(r.userId())) {
            return PlatformAccessDecision.deny(Code.RESOURCE_MISMATCH);
        }
        if (r.userId().equals(c.actor().userId()) && r.status() != c.actor().status()) {
            return PlatformAccessDecision.deny(Code.INCONSISTENT_FACTS);
        }
        if (c.grant().role() == PlatformRole.SUPPORT_READ) {
            if (reason != ReadReason.USER_REQUESTED_SUPPORT) {
                return PlatformAccessDecision.deny(Code.REASON_DENIED);
            }
            if (!c.grant().scope().userIds().contains(r.userId())) {
                return PlatformAccessDecision.deny(Code.PERMISSION_DENIED);
            }
        } else if (reason != ReadReason.USER_REQUESTED_SUPPORT && reason != ReadReason.SECURITY_REVIEW) {
            return PlatformAccessDecision.deny(Code.REASON_DENIED);
        }
        return PlatformAccessDecision.allow(Projection.USER_SECURITY, null);
    }

    private PlatformAccessDecision mutate(PlatformAccessContext c) {
        if (c.grant().role() != PlatformRole.PLATFORM_OWNER) {
            return PlatformAccessDecision.deny(Code.PERMISSION_DENIED);
        }
        if (c.change() == null) {
            return PlatformAccessDecision.deny(Code.MISSING_FACTS);
        }
        if (c.request().action() == Action.INVITE) {
            return invite(c);
        }
        if (!(c.resource() instanceof GrantResource r) || r.recipientStatus() == null) {
            return PlatformAccessDecision.deny(Code.RESOURCE_MISMATCH);
        }
        var target = r.grant();
        Code shape = grantShape(target, c.existingUsers());
        if (shape != null) {
            return PlatformAccessDecision.deny(shape);
        }
        if (!c.existingUsers().contains(target.recipientId())) {
            return PlatformAccessDecision.deny(Code.RESOURCE_MISMATCH);
        }
        if (identityAlias(target, c)) {
            return PlatformAccessDecision.deny(Code.INCONSISTENT_FACTS);
        }
        if (target.recipientId().equals(c.actor().userId())) {
            return PlatformAccessDecision.deny(Code.SELF_TARGET);
        }
        if (target.role() != PlatformRole.SUPPORT_READ) {
            return PlatformAccessDecision.deny(Code.OWNER_TARGET);
        }
        if (!occupiedSlot(c.change().targetSlot(), target.recipientId(), target.id())) {
            return PlatformAccessDecision.deny(Code.SLOT_MISMATCH);
        }
        if (c.change().expectedRevision() == null || c.change().expectedRevision() != target.revision()) {
            return PlatformAccessDecision.deny(Code.STALE_REVISION);
        }
        if (c.change().invitation() != null || c.request().action() != Action.CHANGE
                && (c.change().proposedScope() != null || c.change().proposedValidity() != null)) {
            return PlatformAccessDecision.deny(Code.INCONSISTENT_FACTS);
        }
        if (target.startsAt().isAfter(c.now())) {
            return PlatformAccessDecision.deny(Code.NOT_YET_VALID);
        }
        Code denial = switch (c.request().action()) {
            case CHANGE, RESUME -> enable(c, r);
            case SUSPEND -> target.state() == State.ACTIVE ? null : Code.STATE_DENIED;
            case REVOKE -> target.state() != State.REVOKED ? null : Code.STATE_DENIED;
            default -> Code.ACTION_MISMATCH;
        };
        if (denial != null) {
            return PlatformAccessDecision.deny(denial);
        }
        try {
            return PlatformAccessDecision.allow(Projection.NONE, Math.addExact(target.revision(), 1));
        } catch (ArithmeticException overflow) {
            return PlatformAccessDecision.deny(Code.REVISION_OVERFLOW);
        }
    }

    private Code enable(PlatformAccessContext c, GrantResource resource) {
        var target = resource.grant();
        if (resource.recipientStatus() != UserStatus.ACTIVE) {
            return Code.INACTIVE_TARGET;
        }
        if (target.state() == State.REVOKED
                || c.request().action() == Action.RESUME && target.state() != State.SUSPENDED) {
            return Code.STATE_DENIED;
        }
        if (expired(target, c.now())) {
            return Code.EXPIRED;
        }
        if (c.request().action() == Action.RESUME) {
            return supportEnd(target.validity(), c);
        }
        Code scope = scope(c.change().proposedScope(), PlatformRole.SUPPORT_READ, c.existingUsers());
        if (scope != null) {
            return scope;
        }
        Code validity = supportEnd(c.change().proposedValidity(), c);
        if (validity != null) {
            return validity;
        }
        // Scope is a set semantically; reordering the list is not an authorization change.
        return new HashSet<>(target.scope().userIds()).equals(new HashSet<>(c.change().proposedScope().userIds()))
                && target.validity().equals(c.change().proposedValidity()) ? Code.NO_CHANGE : null;
    }

    private PlatformAccessDecision invite(PlatformAccessContext c) {
        if (!(c.resource() instanceof UserResource r) || r.userId() == null || r.status() == null
                || r.bindingIdentity() == null || !c.existingUsers().contains(r.userId())) {
            return PlatformAccessDecision.deny(Code.RESOURCE_MISMATCH);
        }
        if (r.userId().equals(c.actor().userId())) {
            return PlatformAccessDecision.deny(Code.SELF_TARGET);
        }
        if (r.status() != UserStatus.ACTIVE) {
            return PlatformAccessDecision.deny(Code.INACTIVE_TARGET);
        }
        var change = c.change();
        var slot = change.targetSlot();
        if (slot == null || !r.userId().equals(slot.userId()) || slot.nonRevokedGrantIds() == null) {
            return PlatformAccessDecision.deny(Code.SLOT_MISMATCH);
        }
        if (!slot.nonRevokedGrantIds().isEmpty()) {
            return PlatformAccessDecision.deny(Code.OCCUPIED_SLOT);
        }
        if (change.expectedRevision() != null || change.proposedScope() != null || change.proposedValidity() != null) {
            return PlatformAccessDecision.deny(Code.INCONSISTENT_FACTS);
        }
        var offer = change.invitation();
        if (offer == null || !c.actor().userId().equals(offer.issuerUserId())
                || !c.grant().id().equals(offer.issuerGrantId()) || c.grant().revision() != offer.issuerRevision()
                || !r.userId().equals(offer.recipientId()) || !r.bindingIdentity().equals(offer.recipientBindingIdentity())
                || offer.role() != PlatformRole.SUPPORT_READ || offer.catalogVersion() != 1 || offer.bundleVersion() != 1) {
            return PlatformAccessDecision.deny(Code.INVITATION_MISMATCH);
        }
        if (offer.offeredAt() == null || offer.offeredAt().isAfter(c.now())
                || offer.offeredAt().isBefore(c.grant().startsAt())) {
            return PlatformAccessDecision.deny(Code.TIME_INVALID);
        }
        Code scope = scope(offer.scope(), PlatformRole.SUPPORT_READ, c.existingUsers());
        if (scope != null) {
            return PlatformAccessDecision.deny(scope);
        }
        Code validity = supportEnd(new Validity(ValidityKind.BOUNDED, offer.expiresAt()), c);
        if (validity == null) {
            // Frozen terms cannot acquire a new original 30-day term on a later evaluation.
            try {
                if (offer.expiresAt().isAfter(offer.offeredAt().plus(SUPPORT_TERM))) {
                    validity = Code.INVALID_VALIDITY;
                }
            } catch (DateTimeException | ArithmeticException overflow) {
                validity = Code.TIME_INVALID;
            }
        }
        // INVITE creates no grant and therefore proposes no committed next revision.
        return validity == null ? PlatformAccessDecision.allow(Projection.NONE, null)
                : PlatformAccessDecision.deny(validity);
    }

    private Code supportEnd(Validity validity, PlatformAccessContext c) {
        if (validity == null || validity.kind() != ValidityKind.BOUNDED || validity.expiresAt() == null
                || !validity.expiresAt().isAfter(c.now())) {
            return Code.INVALID_VALIDITY;
        }
        try {
            if (validity.expiresAt().isAfter(c.now().plus(SUPPORT_TERM))) {
                return Code.INVALID_VALIDITY;
            }
        } catch (DateTimeException | ArithmeticException overflow) {
            return Code.TIME_INVALID;
        }
        return c.grant().validity().kind() == ValidityKind.BOUNDED
                && validity.expiresAt().isAfter(c.grant().validity().expiresAt()) ? Code.INVALID_VALIDITY : null;
    }

    private boolean identityAlias(PlatformAccessGrant target, PlatformAccessContext c) {
        return target.id().equals(c.grant().id()) && !target.equals(c.grant())
                || target.recipientId().equals(c.actor().userId()) && target.state() != State.REVOKED
                && !target.id().equals(c.grant().id());
    }
}
