package com.creastrix.platform.platformaccess.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.creastrix.platform.platformaccess.domain.PlatformAccessContext.*;
import static com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synthetic delegation predicates only: no invitation is sent or redeemed, no
 * slot uniqueness is established, and an allowed decision performs no mutation.
 */
class PlatformAccessDelegationTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final UUID OWNER = id(1);
    private static final UUID TARGET = id(2);
    private static final UUID SCOPED_USER = id(3);
    private static final UUID OTHER_SCOPED_USER = id(4);
    private static final UUID OWNER_GRANT = id(11);
    private static final UUID TARGET_GRANT = id(12);
    private static final UUID TARGET_BINDING = id(13);
    private static final UUID ACCOUNT_GENERATION = id(14);
    private static final UUID SESSION_GENERATION = id(15);
    private static final Duration THIRTY_DAYS = Duration.ofHours(30L * 24);
    private static final Scope ORIGINAL_SCOPE = supportScope(SCOPED_USER);
    private static final Scope CHANGED_SCOPE = supportScope(OTHER_SCOPED_USER);
    private static final Validity SUPPORT_VALIDITY = bounded(NOW.plus(Duration.ofDays(10)));
    private static final List<Action> MUTATIONS = List.of(
            Action.INVITE, Action.CHANGE, Action.RESUME, Action.SUSPEND, Action.REVOKE);

    private final PlatformAccessPolicy policy = new PlatformAccessPolicy();

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void ownerMayPerformEachExplicitOrdinaryActionForAnotherSupportUser(Action action) {
        Fixture fixture = new Fixture(action);
        PlatformAccessContext before = fixture.context();

        PlatformAccessDecision decision = policy.evaluate(before);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.projection()).isEqualTo(PlatformAccessDecision.Projection.NONE);
        if (action != Action.INVITE) {
            assertThat(decision.projectedNextRevision()).isEqualTo(5L);
        } else {
            assertThat(decision.projectedNextRevision()).isNull();
        }
        // A decision is not a changed grant, accepted invitation, or commit.
        assertThat(fixture.context()).isEqualTo(before);
        assertThat(fixture.target.revision()).isEqualTo(4L);
        assertThat(fixture.target.recipientId()).isEqualTo(TARGET);
    }

    @ParameterizedTest
    @MethodSource("wrongPermissionPairs")
    void mutationCannotBorrowAnyOtherPermission(Action action, PlatformPermission permission) {
        Fixture fixture = new Fixture(action);
        fixture.request = new Request(permission, action, null);
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void everyOrdinaryMutationRequiresItsAdditionalStepUp(Action action) {
        Fixture fixture = new Fixture(action);
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        Assurance valid = fixture.assurance;
        fixture.assurance = new Assurance(valid.verification(), valid.stamp(), valid.mfaAt(),
                valid.boundAt(), valid.lastActivityAt(), null);
        assertDenied(fixture);
    }

    @ParameterizedTest
    @MethodSource("readReasonsOnMutations")
    void readJustificationDoesNotBecomeAnOrdinaryMutationContract(Action action, ReadReason reason) {
        Fixture fixture = new Fixture(action);
        fixture.request = new Request(required(action), action, reason);
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void mutationRequiresTheConcreteResourceKindForItsAction(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.resource = action == Action.INVITE
                ? new GrantResource(fixture.target, UserStatus.ACTIVE)
                : new UserResource(TARGET, UserStatus.ACTIVE, TARGET_BINDING);
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void reportedTargetMustBeAnExistingUser(Action action) {
        Fixture fixture = new Fixture(action);
        PlatformAccessContext original = fixture.context();
        PlatformAccessContext withoutRecipient = new PlatformAccessContext(original.now(), original.actor(),
                original.grant(), original.actorSlot(), original.assurance(), original.request(),
                original.resource(), original.change(), Set.of(OWNER, SCOPED_USER, OTHER_SCOPED_USER));
        assertThat(policy.evaluate(withoutRecipient).allowed()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void supportCannotDelegateEvenToADifferentUser(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.owner = grant(OWNER_GRANT, OWNER, PlatformRole.SUPPORT_READ,
                State.ACTIVE, 7, NOW.minusSeconds(3600), SUPPORT_VALIDITY, supportScope(TARGET));
        fixture.refreshAssurance();
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void ownerCannotTargetTheSameUser(Action action) {
        Fixture fixture = new Fixture(action);
        if (action == Action.INVITE) {
            fixture.resource = new UserResource(OWNER, UserStatus.ACTIVE, TARGET_BINDING);
            fixture.change = new Change(new Slot(OWNER, List.of()), null, null, null,
                    invitation(fixture, OWNER, TARGET_BINDING, CHANGED_SCOPE, SUPPORT_VALIDITY.expiresAt()));
        } else {
            fixture.replaceTarget(grant(TARGET_GRANT, OWNER, PlatformRole.SUPPORT_READ,
                    fixture.target.state(), 4, fixture.target.startsAt(), SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        }
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"INVITE", "CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void ordinaryPathCannotAssignOrMutateOwnerRole(Action action) {
        Fixture fixture = new Fixture(action);
        if (action == Action.INVITE) {
            Invitation old = fixture.change.invitation();
            fixture.withInvitation(new Invitation(old.issuerUserId(), old.issuerGrantId(), old.issuerRevision(),
                    old.recipientId(), old.recipientBindingIdentity(), PlatformRole.PLATFORM_OWNER,
                    1, 1, new Scope(ScopeKind.PLATFORM_SECURITY_METADATA, List.of()),
                    old.offeredAt(), old.expiresAt()));
        } else {
            fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.PLATFORM_OWNER,
                    fixture.target.state(), 4, fixture.target.startsAt(), SUPPORT_VALIDITY,
                    new Scope(ScopeKind.PLATFORM_SECURITY_METADATA, List.of())));
        }
        assertDenied(fixture);
    }

    @ParameterizedTest
    @MethodSource("enablingInactiveTargets")
    void enablingAndChangeRequireActiveTargetUser(Action action, UserStatus status) {
        Fixture fixture = new Fixture(action);
        fixture.withTargetStatus(status);
        assertDenied(fixture);
    }

    @ParameterizedTest
    @MethodSource("disablingInactiveTargets")
    void disablingInactiveTargetDoesNotGrantThatUserAuthority(Action action, UserStatus status) {
        Fixture fixture = new Fixture(action);
        fixture.withTargetStatus(status);
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        assertThat(fixture.target.recipientId()).isEqualTo(TARGET);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"ACTIVE", "SUSPENDED"})
    void reportedOccupiedSlotBlocksInvitationEvenWhenExistingGrantIsExpired(State state) {
        Fixture fixture = new Fixture(Action.INVITE);
        PlatformAccessGrant expired = grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ,
                state, 4, NOW.minusSeconds(7200), bounded(NOW.minusSeconds(1)), ORIGINAL_SCOPE);
        fixture.change = new Change(new Slot(TARGET, List.of(expired.id())), null,
                null, null, fixture.change.invitation());
        assertDenied(fixture);
        assertThat(expired.state()).isEqualTo(state);
    }

    @Test
    void revokedHistoryDoesNotOccupyTheReportedSlotForANewOffer() {
        Fixture fixture = new Fixture(Action.REVOKE);
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        PlatformAccessGrant history = grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ,
                State.REVOKED, 5, fixture.target.startsAt(), SUPPORT_VALIDITY, ORIGINAL_SCOPE);

        Fixture newOffer = new Fixture(Action.INVITE);
        assertThat(history.id()).isEqualTo(TARGET_GRANT);
        assertThat(newOffer.change.targetSlot().nonRevokedGrantIds()).isEmpty();
        assertThat(policy.evaluate(newOffer.context()).allowed()).isTrue();
        // The supplied empty-slot fact is not a database proof or actual issuance.
        assertThat(history.state()).isEqualTo(State.REVOKED);
    }

    @ParameterizedTest
    @MethodSource("invalidSlots")
    void exactTargetAndSingleReportedSlotAreRequired(Slot slot) {
        Fixture fixture = new Fixture(Action.CHANGE);
        fixture.change = new Change(slot, 4L, CHANGED_SCOPE, SUPPORT_VALIDITY, null);
        assertDenied(fixture);
    }

    @Test
    void invitationRequiresAReportedEmptySlotForTheExactRecipient() {
        Fixture fixture = new Fixture(Action.INVITE);
        fixture.change = new Change(new Slot(OTHER_SCOPED_USER, List.of()), null,
                null, null, fixture.change.invitation());
        assertDenied(fixture);
    }

    @Test
    void invitationCannotSilentlyAdoptAnotherRecipientBinding() {
        Fixture fixture = new Fixture(Action.INVITE);
        Invitation offered = fixture.change.invitation();
        fixture.withInvitation(new Invitation(offered.issuerUserId(), offered.issuerGrantId(), offered.issuerRevision(),
                offered.recipientId(), id(999), offered.role(), offered.catalogVersion(), offered.bundleVersion(),
                offered.scope(), offered.offeredAt(), offered.expiresAt()));
        assertDenied(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-offer", "missing-binding", "different-recipient", "missing-role",
            "missing-scope", "missing-expiry", "missing-offer-time"})
    void invitationDeniesMissingOrInconsistentFrozenTerms(String malformedField) {
        Fixture fixture = new Fixture(Action.INVITE);
        Invitation offer = fixture.change.invitation();
        fixture.withInvitation(switch (malformedField) {
            case "missing-offer" -> null;
            case "missing-binding" -> new Invitation(offer.issuerUserId(), offer.issuerGrantId(), offer.issuerRevision(),
                    offer.recipientId(), null, offer.role(), 1, 1, offer.scope(), offer.offeredAt(), offer.expiresAt());
            case "different-recipient" -> new Invitation(offer.issuerUserId(), offer.issuerGrantId(), offer.issuerRevision(),
                    OTHER_SCOPED_USER, offer.recipientBindingIdentity(), offer.role(), 1, 1, offer.scope(),
                    offer.offeredAt(), offer.expiresAt());
            case "missing-role" -> new Invitation(offer.issuerUserId(), offer.issuerGrantId(), offer.issuerRevision(),
                    offer.recipientId(), offer.recipientBindingIdentity(), null, 1, 1, offer.scope(),
                    offer.offeredAt(), offer.expiresAt());
            case "missing-scope" -> new Invitation(offer.issuerUserId(), offer.issuerGrantId(), offer.issuerRevision(),
                    offer.recipientId(), offer.recipientBindingIdentity(), offer.role(), 1, 1, null,
                    offer.offeredAt(), offer.expiresAt());
            case "missing-expiry" -> new Invitation(offer.issuerUserId(), offer.issuerGrantId(), offer.issuerRevision(),
                    offer.recipientId(), offer.recipientBindingIdentity(), offer.role(), 1, 1, offer.scope(),
                    offer.offeredAt(), null);
            case "missing-offer-time" -> new Invitation(offer.issuerUserId(), offer.issuerGrantId(), offer.issuerRevision(),
                    offer.recipientId(), offer.recipientBindingIdentity(), offer.role(), 1, 1, offer.scope(),
                    null, offer.expiresAt());
            default -> throw new IllegalArgumentException("Unsupported synthetic malformed field");
        });
        assertDenied(fixture);
    }

    @ParameterizedTest
    @MethodSource("invalidInvitationAuthority")
    void invitationCannotUseStaleOrForeignIssuerAuthority(UUID issuerUser, UUID issuerGrant, long revision) {
        Fixture fixture = new Fixture(Action.INVITE);
        Invitation offered = fixture.change.invitation();
        fixture.withInvitation(new Invitation(issuerUser, issuerGrant, revision,
                offered.recipientId(), offered.recipientBindingIdentity(), offered.role(),
                offered.catalogVersion(), offered.bundleVersion(), offered.scope(),
                offered.offeredAt(), offered.expiresAt()));
        assertDenied(fixture);
    }

    @Test
    void pendingOfferDoesNotBecomeValidAfterIssuerRevisionChanges() {
        Fixture fixture = new Fixture(Action.INVITE);
        Invitation frozen = fixture.change.invitation();
        fixture.owner = grant(OWNER_GRANT, OWNER, PlatformRole.PLATFORM_OWNER,
                State.ACTIVE, 8, fixture.owner.startsAt(), fixture.owner.validity(), fixture.owner.scope());
        fixture.refreshAssurance();
        assertDenied(fixture);
        assertThat(frozen.issuerRevision()).isEqualTo(7);
    }

    @ParameterizedTest
    @MethodSource("invalidOfferVersions")
    void invitationRejectsUnknownCatalogOrBundleVersion(int catalog, int bundle) {
        Fixture fixture = new Fixture(Action.INVITE);
        Invitation offered = fixture.change.invitation();
        fixture.withInvitation(new Invitation(offered.issuerUserId(), offered.issuerGrantId(), offered.issuerRevision(),
                offered.recipientId(), offered.recipientBindingIdentity(), offered.role(), catalog, bundle,
                offered.scope(), offered.offeredAt(), offered.expiresAt()));
        assertDenied(fixture);
    }

    @Test
    void fixedInvitationExpiryIsNotExtendedAtALaterEvaluation() {
        Fixture fixture = new Fixture(Action.INVITE);
        Instant exactEnd = NOW.plus(Duration.ofDays(2));
        fixture.withInvitation(invitation(fixture, TARGET, TARGET_BINDING, CHANGED_SCOPE, exactEnd));
        fixture.now = NOW.plusSeconds(60);
        fixture.refreshAssurance();

        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        assertThat(fixture.change.invitation().expiresAt()).isEqualTo(exactEnd);
        fixture.now = exactEnd;
        fixture.refreshAssurance();
        assertDenied(fixture);
        assertThat(fixture.change.invitation().expiresAt()).isEqualTo(exactEnd);
    }

    @Test
    void oldOfferCannotLaunderAnOriginallyOverlongFrozenTerm() {
        Fixture fixture = new Fixture(Action.INVITE);
        fixture.owner = grant(OWNER_GRANT, OWNER, PlatformRole.PLATFORM_OWNER, State.ACTIVE,
                7, NOW.minus(Duration.ofDays(45)), fixture.owner.validity(), fixture.owner.scope());
        fixture.refreshAssurance();
        fixture.withInvitation(new Invitation(OWNER, OWNER_GRANT, 7, TARGET, TARGET_BINDING,
                PlatformRole.SUPPORT_READ, 1, 1, CHANGED_SCOPE,
                NOW.minus(Duration.ofDays(40)), NOW.plus(Duration.ofDays(1))));

        // Being within today's remaining-term ceiling does not validate the
        // offer's original 41-day absolute term or execute its acceptance.
        assertDenied(fixture);
    }

    @Test
    void expiredOrRevokedIssuerCannotAuthorizeAnOffer() {
        for (State state : List.of(State.SUSPENDED, State.REVOKED)) {
            Fixture fixture = new Fixture(Action.INVITE);
            fixture.owner = grant(OWNER_GRANT, OWNER, PlatformRole.PLATFORM_OWNER, state, 7,
                    fixture.owner.startsAt(), fixture.owner.validity(), fixture.owner.scope());
            fixture.refreshAssurance();
            assertDenied(fixture);
        }
        Fixture expired = new Fixture(Action.INVITE);
        expired.withOwnerValidity(bounded(NOW));
        assertDenied(expired);
    }

    @ParameterizedTest
    @MethodSource("enablingActions")
    void exactThirtyDayEndIsAllowedButOneNanosecondMoreIsDenied(Action action) {
        Fixture allowed = new Fixture(action);
        allowed.withResultExpiry(NOW.plus(THIRTY_DAYS));
        if (action == Action.INVITE) {
            allowed.withOfferTime(NOW);
        }
        assertThat(policy.evaluate(allowed.context()).allowed()).isTrue();

        Fixture excessive = new Fixture(action);
        excessive.withResultExpiry(NOW.plus(THIRTY_DAYS).plusNanos(1));
        if (action == Action.INVITE) {
            excessive.withOfferTime(NOW);
        }
        assertDenied(excessive);
    }

    @Test
    void agedOfferRetainsItsOriginalExactThirtyDayCeiling() {
        Instant offeredAt = NOW.minusSeconds(30);
        Instant originalCeiling = offeredAt.plus(THIRTY_DAYS);
        Fixture allowed = new Fixture(Action.INVITE);
        allowed.withResultExpiry(originalCeiling);
        allowed.withOfferTime(offeredAt);
        assertThat(allowed.change.invitation().offeredAt()).isBefore(allowed.now);
        assertThat(policy.evaluate(allowed.context()).allowed()).isTrue();

        Fixture excessive = new Fixture(Action.INVITE);
        excessive.withResultExpiry(originalCeiling.plusNanos(1));
        excessive.withOfferTime(offeredAt);
        assertDenied(excessive);
    }

    @ParameterizedTest
    @MethodSource("enablingActions")
    void boundedOwnerEndIsInclusiveButNeverExceeded(Action action) {
        Instant ownerEnd = NOW.plus(Duration.ofDays(3));
        Fixture allowed = new Fixture(action);
        allowed.withOwnerValidity(bounded(ownerEnd));
        allowed.withResultExpiry(ownerEnd);
        assertThat(policy.evaluate(allowed.context()).allowed()).isTrue();

        Fixture excessive = new Fixture(action);
        excessive.withOwnerValidity(bounded(ownerEnd));
        excessive.withResultExpiry(ownerEnd.plusNanos(1));
        assertDenied(excessive);
    }

    @ParameterizedTest
    @MethodSource("enablingActions")
    void equalityAtSupportExpiryIsDenied(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.withResultExpiry(NOW);
        assertDenied(fixture);
    }

    @Test
    void scopeOnlyChangeRevalidatesThePreservedExpiryAgainstCurrentOwner() {
        Fixture fixture = new Fixture(Action.CHANGE);
        fixture.withOwnerValidity(bounded(NOW.plus(Duration.ofDays(1))));
        assertDenied(fixture);
        assertThat(fixture.change.proposedValidity()).isEqualTo(SUPPORT_VALIDITY);
        assertThat(fixture.target.validity()).isEqualTo(SUPPORT_VALIDITY);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"ACTIVE", "SUSPENDED"})
    void scopeAndExpiryChangesPreserveIdentityStartRoleAndLifecycle(State state) {
        Fixture fixture = new Fixture(Action.CHANGE);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, state,
                4, NOW.minusSeconds(7200), SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        PlatformAccessGrant before = fixture.target;
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        assertThat(fixture.target).isEqualTo(before);
        assertThat(fixture.target.state()).isEqualTo(state);
        assertThat(fixture.target.startsAt()).isEqualTo(NOW.minusSeconds(7200));

        fixture.change = new Change(fixture.change.targetSlot(), 4L, ORIGINAL_SCOPE,
                bounded(NOW.plus(Duration.ofDays(1))), null);
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        assertThat(fixture.target).isEqualTo(before);
    }

    @Test
    void unchangedScopeAndExpiryAreNotASuccessfulMutation() {
        Fixture fixture = new Fixture(Action.CHANGE);
        fixture.change = new Change(fixture.change.targetSlot(), 4L, ORIGINAL_SCOPE, SUPPORT_VALIDITY, null);
        assertDenied(fixture);
    }

    @Test
    void merelyReorderingAnExactScopeSetIsStillANoOp() {
        Fixture fixture = new Fixture(Action.CHANGE);
        Scope twoUsers = supportScope(SCOPED_USER, OTHER_SCOPED_USER);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, State.ACTIVE,
                4, fixture.target.startsAt(), SUPPORT_VALIDITY, twoUsers));
        fixture.change = new Change(fixture.change.targetSlot(), 4L,
                supportScope(OTHER_SCOPED_USER, SCOPED_USER), SUPPORT_VALIDITY, null);
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void missingOrStaleExpectedRevisionNeverOverwrites(Action action) {
        for (Long expected : new Long[]{null, 0L, 3L, 5L, Long.MAX_VALUE}) {
            Fixture fixture = new Fixture(action);
            fixture.change = new Change(fixture.change.targetSlot(), expected,
                    fixture.change.proposedScope(), fixture.change.proposedValidity(), null);
            assertDenied(fixture);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void targetCannotReuseActorGrantIdentityForADifferentRecipient(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.replaceTarget(grant(OWNER_GRANT, TARGET, PlatformRole.SUPPORT_READ,
                fixture.target.state(), 4, fixture.target.startsAt(), SUPPORT_VALIDITY, ORIGINAL_SCOPE));

        assertThat(fixture.target.id()).isEqualTo(fixture.owner.id());
        assertThat(fixture.target.recipientId()).isNotEqualTo(fixture.owner.recipientId());
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void revisionIncrementIsCheckedAndNeverWraps(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, fixture.target.state(),
                Long.MAX_VALUE, fixture.target.startsAt(), SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        fixture.change = new Change(fixture.change.targetSlot(), Long.MAX_VALUE,
                fixture.change.proposedScope(), fixture.change.proposedValidity(), null);
        assertDenied(fixture);
        assertThat(fixture.target.revision()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CHANGE", "RESUME", "SUSPEND", "REVOKE"})
    void lastRepresentableRevisionCanBeProjectedWithoutChangingTheSnapshot(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, fixture.target.state(),
                Long.MAX_VALUE - 1, fixture.target.startsAt(), SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        fixture.change = new Change(fixture.change.targetSlot(), Long.MAX_VALUE - 1,
                fixture.change.proposedScope(), fixture.change.proposedValidity(), null);
        PlatformAccessDecision decision = policy.evaluate(fixture.context());
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.projectedNextRevision()).isEqualTo(Long.MAX_VALUE);
        assertThat(fixture.target.revision()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"RESUME", "SUSPEND", "REVOKE"})
    void lifecycleActionCannotSmuggleScopeOrExpiryChange(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.change = new Change(fixture.change.targetSlot(), 4L,
                CHANGED_SCOPE, bounded(NOW.plusSeconds(60)), null);
        assertDenied(fixture);
        assertThat(fixture.target.scope()).isEqualTo(ORIGINAL_SCOPE);
        assertThat(fixture.target.validity()).isEqualTo(SUPPORT_VALIDITY);
    }

    @ParameterizedTest
    @MethodSource("invalidLifecyclePairs")
    void invalidOrSameStateLifecycleRequestsAreDenied(Action action, State state) {
        Fixture fixture = new Fixture(action);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, state,
                4, fixture.target.startsAt(), SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CHANGE", "RESUME"})
    void expiredTargetCannotBeExtendedChangedOrResumed(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, fixture.target.state(),
                4, fixture.target.startsAt(), bounded(NOW), ORIGINAL_SCOPE));
        if (action == Action.CHANGE) {
            fixture.change = new Change(fixture.change.targetSlot(), 4L,
                    CHANGED_SCOPE, bounded(NOW.plusSeconds(60)), null);
        }
        assertDenied(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"SUSPEND", "REVOKE"})
    void expiredActiveTargetMayBeDisabledWithoutRenewal(Action action) {
        Fixture fixture = new Fixture(action);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, State.ACTIVE,
                4, fixture.target.startsAt(), bounded(NOW.minusSeconds(1)), ORIGINAL_SCOPE));
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
        assertThat(fixture.target.validity().expiresAt()).isBefore(NOW);
    }

    @Test
    void resumeRequiresSeparateAuthorizedShorteningWhenOwnerCeilingIsLower() {
        Instant ceiling = NOW.plus(Duration.ofDays(2));
        Fixture resume = new Fixture(Action.RESUME);
        resume.withOwnerValidity(bounded(ceiling));
        assertDenied(resume);
        assertThat(resume.target.validity()).isEqualTo(SUPPORT_VALIDITY);

        Fixture shortening = new Fixture(Action.CHANGE);
        shortening.withOwnerValidity(bounded(ceiling));
        shortening.replaceTarget(resume.target);
        shortening.change = new Change(shortening.change.targetSlot(), 4L,
                ORIGINAL_SCOPE, bounded(ceiling), null);
        assertThat(policy.evaluate(shortening.context()).allowed()).isTrue();

        // This supplied snapshot models a later successful CHANGE; policy does
        // not execute it or claim that a transaction committed between calls.
        PlatformAccessGrant shorter = grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, State.SUSPENDED,
                5, resume.target.startsAt(), bounded(ceiling), ORIGINAL_SCOPE);
        resume.replaceTarget(shorter);
        resume.change = new Change(resume.change.targetSlot(), 5L, null, null, null);
        assertThat(policy.evaluate(resume.context()).allowed()).isTrue();
        assertThat(resume.target.state()).isEqualTo(State.SUSPENDED);
        assertThat(resume.target.validity().expiresAt()).isEqualTo(ceiling);
    }

    @ParameterizedTest
    @MethodSource("invalidProposalScopes")
    void invitationAndChangeNeverTrimOrRepairInvalidScope(Scope proposed) {
        for (Action action : List.of(Action.INVITE, Action.CHANGE)) {
            Fixture fixture = new Fixture(action);
            if (action == Action.INVITE) {
                fixture.withInvitation(invitation(fixture, TARGET, TARGET_BINDING, proposed,
                        SUPPORT_VALIDITY.expiresAt()));
            } else {
                fixture.change = new Change(fixture.change.targetSlot(), 4L, proposed, SUPPORT_VALIDITY, null);
            }
            assertDenied(fixture);
        }
    }

    @Test
    void supportProposalRequiresExplicitBoundedValidity() {
        Fixture fixture = new Fixture(Action.CHANGE);
        fixture.change = new Change(fixture.change.targetSlot(), 4L, CHANGED_SCOPE,
                new Validity(ValidityKind.UNBOUNDED, null), null);
        assertDenied(fixture);
    }

    @Test
    void futureOfferOrGrantStartCannotBeTreatedAsAnOccurredEvent() {
        Fixture invitation = new Fixture(Action.INVITE);
        Invitation old = invitation.change.invitation();
        invitation.withInvitation(new Invitation(old.issuerUserId(), old.issuerGrantId(), old.issuerRevision(),
                old.recipientId(), old.recipientBindingIdentity(), old.role(), old.catalogVersion(),
                old.bundleVersion(), old.scope(), NOW.plusNanos(1), old.expiresAt()));
        assertDenied(invitation);

        Fixture change = new Fixture(Action.CHANGE);
        change.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, State.ACTIVE,
                4, NOW.plusNanos(1), SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        assertDenied(change);
    }

    @Test
    void exactGrantStartIsAllowedForAnOtherwiseValidMutation() {
        Fixture fixture = new Fixture(Action.CHANGE);
        fixture.replaceTarget(grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ, State.ACTIVE,
                4, NOW, SUPPORT_VALIDITY, ORIGINAL_SCOPE));
        assertThat(policy.evaluate(fixture.context()).allowed()).isTrue();
    }

    private void assertDenied(Fixture fixture) {
        PlatformAccessDecision decision = policy.evaluate(fixture.context());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.projectedNextRevision()).isNull();
    }

    private static Stream<Arguments> wrongPermissionPairs() {
        return MUTATIONS.stream().flatMap(action -> Stream.of(PlatformPermission.values())
                .filter(permission -> permission != required(action))
                .map(permission -> Arguments.of(action, permission)));
    }

    private static Stream<Arguments> readReasonsOnMutations() {
        return MUTATIONS.stream().flatMap(action -> Stream.of(ReadReason.values())
                .map(reason -> Arguments.of(action, reason)));
    }

    private static Stream<Arguments> enablingInactiveTargets() {
        return Stream.of(Action.INVITE, Action.CHANGE, Action.RESUME).flatMap(action ->
                Stream.of(UserStatus.SUSPENDED, UserStatus.DEACTIVATED)
                        .map(status -> Arguments.of(action, status)));
    }

    private static Stream<Arguments> disablingInactiveTargets() {
        return Stream.of(Action.SUSPEND, Action.REVOKE).flatMap(action ->
                Stream.of(UserStatus.SUSPENDED, UserStatus.DEACTIVATED)
                        .map(status -> Arguments.of(action, status)));
    }

    private static Stream<Action> enablingActions() {
        return Stream.of(Action.INVITE, Action.CHANGE, Action.RESUME);
    }

    private static Stream<Slot> invalidSlots() {
        return Stream.of(new Slot(TARGET, List.of()), new Slot(OWNER, List.of(TARGET_GRANT)),
                new Slot(TARGET, List.of(OWNER_GRANT)),
                new Slot(TARGET, List.of(TARGET_GRANT, OWNER_GRANT)),
                new Slot(TARGET, List.of(TARGET_GRANT, TARGET_GRANT)));
    }

    private static Stream<Arguments> invalidInvitationAuthority() {
        return Stream.of(Arguments.of(OTHER_SCOPED_USER, OWNER_GRANT, 7L),
                Arguments.of(OWNER, TARGET_GRANT, 7L), Arguments.of(OWNER, OWNER_GRANT, 6L),
                Arguments.of(OWNER, OWNER_GRANT, 8L));
    }

    private static Stream<Arguments> invalidOfferVersions() {
        return Stream.of(Arguments.of(0, 1), Arguments.of(2, 1), Arguments.of(1, 0), Arguments.of(1, 2));
    }

    private static Stream<Arguments> invalidLifecyclePairs() {
        return Stream.of(Arguments.of(Action.RESUME, State.ACTIVE), Arguments.of(Action.RESUME, State.REVOKED),
                Arguments.of(Action.SUSPEND, State.SUSPENDED), Arguments.of(Action.SUSPEND, State.REVOKED),
                Arguments.of(Action.REVOKE, State.REVOKED));
    }

    private static Stream<Scope> invalidProposalScopes() {
        return Stream.of(supportScope(), supportScope(SCOPED_USER, SCOPED_USER), supportScope(id(999)),
                new Scope(ScopeKind.PLATFORM_SECURITY_METADATA, List.of()));
    }

    private static PlatformPermission required(Action action) {
        return switch (action) {
            case INVITE -> PlatformPermission.STAFF_INVITE;
            case CHANGE, RESUME -> PlatformPermission.STAFF_GRANT_CHANGE;
            case SUSPEND, REVOKE -> PlatformPermission.STAFF_GRANT_SUSPEND_REVOKE;
            case READ -> throw new IllegalArgumentException("Delegation fixture requires a mutation action");
        };
    }

    private static Invitation invitation(Fixture fixture, UUID recipient, UUID binding, Scope scope, Instant end) {
        return new Invitation(OWNER, OWNER_GRANT, fixture.owner.revision(), recipient, binding,
                PlatformRole.SUPPORT_READ, 1, 1, scope, NOW.minusSeconds(30), end);
    }

    private static PlatformAccessGrant grant(UUID grantId, UUID recipient, PlatformRole role, State state,
            long revision, Instant start, Validity validity, Scope scope) {
        return new PlatformAccessGrant(grantId, recipient, role, 1, 1, revision, state, start, validity, scope);
    }

    private static Scope supportScope(UUID... users) {
        return new Scope(ScopeKind.EXACT_USERS, List.of(users));
    }

    private static Validity bounded(Instant end) {
        return new Validity(ValidityKind.BOUNDED, end);
    }

    private static UUID id(long suffix) {
        return new UUID(0, suffix);
    }

    private static final class Fixture {
        private Instant now = NOW;
        private final Actor actor = new Actor(OWNER, UserStatus.ACTIVE, ACCOUNT_GENERATION, SESSION_GENERATION);
        private PlatformAccessGrant owner = grant(OWNER_GRANT, OWNER, PlatformRole.PLATFORM_OWNER,
                State.ACTIVE, 7, NOW.minusSeconds(86400), new Validity(ValidityKind.UNBOUNDED, null),
                new Scope(ScopeKind.PLATFORM_SECURITY_METADATA, List.of()));
        private PlatformAccessGrant target;
        private Assurance assurance;
        private Request request;
        private Resource resource;
        private Change change;

        private Fixture(Action action) {
            target = grant(TARGET_GRANT, TARGET, PlatformRole.SUPPORT_READ,
                    action == Action.RESUME ? State.SUSPENDED : State.ACTIVE,
                    4, NOW.minusSeconds(3600), SUPPORT_VALIDITY, ORIGINAL_SCOPE);
            request = new Request(required(action), action, null);
            if (action == Action.INVITE) {
                resource = new UserResource(TARGET, UserStatus.ACTIVE, TARGET_BINDING);
                change = new Change(new Slot(TARGET, List.of()), null, null, null,
                        invitation(this, TARGET, TARGET_BINDING, CHANGED_SCOPE, SUPPORT_VALIDITY.expiresAt()));
            } else {
                resource = new GrantResource(target, UserStatus.ACTIVE);
                change = new Change(new Slot(TARGET, List.of(TARGET_GRANT)), 4L,
                        action == Action.CHANGE ? CHANGED_SCOPE : null,
                        action == Action.CHANGE ? SUPPORT_VALIDITY : null, null);
            }
            refreshAssurance();
        }

        private PlatformAccessContext context() {
            return new PlatformAccessContext(now, actor, owner, new Slot(OWNER, List.of(OWNER_GRANT)),
                    assurance, request, resource, change,
                    Set.of(OWNER, TARGET, SCOPED_USER, OTHER_SCOPED_USER));
        }

        private void refreshAssurance() {
            Stamp stamp = new Stamp(OWNER, ACCOUNT_GENERATION, SESSION_GENERATION, owner.id(), owner.revision());
            assurance = new Assurance(Verification.VERIFIED_MFA, stamp, now.minusSeconds(120),
                    now.minusSeconds(110), now.minusSeconds(60),
                    new StepUp(Verification.VERIFIED_STEP_UP, stamp, now.minusSeconds(30)));
        }

        private void withInvitation(Invitation invitation) {
            change = new Change(change.targetSlot(), change.expectedRevision(), change.proposedScope(),
                    change.proposedValidity(), invitation);
        }

        private void withOfferTime(Instant offeredAt) {
            Invitation old = change.invitation();
            withInvitation(new Invitation(old.issuerUserId(), old.issuerGrantId(), old.issuerRevision(),
                    old.recipientId(), old.recipientBindingIdentity(), old.role(), old.catalogVersion(),
                    old.bundleVersion(), old.scope(), offeredAt, old.expiresAt()));
        }

        private void replaceTarget(PlatformAccessGrant replacement) {
            UserStatus status = resource instanceof GrantResource grantResource
                    ? grantResource.recipientStatus() : UserStatus.ACTIVE;
            target = replacement;
            resource = new GrantResource(target, status);
            change = new Change(new Slot(target.recipientId(), List.of(target.id())),
                    change.expectedRevision(), change.proposedScope(), change.proposedValidity(), change.invitation());
        }

        private void withTargetStatus(UserStatus status) {
            resource = resource instanceof UserResource user
                    ? new UserResource(user.userId(), status, user.bindingIdentity())
                    : new GrantResource(target, status);
        }

        private void withOwnerValidity(Validity validity) {
            owner = grant(owner.id(), owner.recipientId(), owner.role(), owner.state(), owner.revision(),
                    owner.startsAt(), validity, owner.scope());
            refreshAssurance();
        }

        private void withResultExpiry(Instant expiry) {
            if (request.action() == Action.INVITE) {
                withInvitation(invitation(this, TARGET, TARGET_BINDING, CHANGED_SCOPE, expiry));
            } else if (request.action() == Action.CHANGE) {
                change = new Change(change.targetSlot(), 4L, CHANGED_SCOPE, bounded(expiry), null);
            } else {
                replaceTarget(grant(target.id(), target.recipientId(), target.role(), target.state(), target.revision(),
                        target.startsAt(), bounded(expiry), target.scope()));
            }
        }
    }
}
