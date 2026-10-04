package com.creastrix.platform.platformaccess.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.creastrix.platform.platformaccess.domain.PlatformAccessContext.*;
import static com.creastrix.platform.platformaccess.domain.PlatformAccessDecision.Projection;
import static com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic authorization predicates over synthetic trusted snapshots only.
 * These tests do not establish provider MFA, live uniqueness, audit or DB admission.
 */
class PlatformAccessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID OWNER = id(1);
    private static final UUID SUPPORT = id(2);
    private static final UUID TARGET = id(3);
    private static final UUID OTHER = id(4);
    private static final UUID OWNER_GRANT = id(5);
    private static final UUID SUPPORT_GRANT = id(6);
    private static final UUID ACCOUNT_GENERATION = id(7);
    private static final UUID SESSION_GENERATION = id(8);
    private static final UUID TARGET_BINDING = id(9);
    private static final UUID AUDIT_EVENT = id(10);
    private static final PlatformAccessPolicy POLICY = new PlatformAccessPolicy();

    @ParameterizedTest(name = "{0} {1} {2}: allowed={3}")
    @MethodSource("readMatrix")
    void exhaustsTheFiniteRolePermissionReasonReadMatrix(
            PlatformRole role, PlatformPermission permission, ReadReason reason, boolean allowed) {
        Fixture fixture = Fixture.read(role, permission, reason);

        PlatformAccessDecision result = POLICY.evaluate(fixture.context());

        if (allowed) {
            assertAllowedRead(result, projection(permission));
        } else {
            assertDenied(result);
        }
    }

    static Stream<Arguments> readMatrix() {
        return Arrays.stream(PlatformRole.values()).flatMap(role ->
                Arrays.stream(PlatformPermission.values()).flatMap(permission ->
                        Arrays.stream(ReadReason.values()).map(reason -> {
                            boolean allowed = switch (permission) {
                                case USER_SECURITY_READ -> reason == ReadReason.USER_REQUESTED_SUPPORT
                                        || role == PlatformRole.PLATFORM_OWNER && reason == ReadReason.SECURITY_REVIEW;
                                case STAFF_GRANTS_READ -> role == PlatformRole.PLATFORM_OWNER
                                        && reason == ReadReason.ACCESS_REVIEW;
                                case SECURITY_AUDIT_READ -> role == PlatformRole.PLATFORM_OWNER
                                        && (reason == ReadReason.ACCESS_REVIEW || reason == ReadReason.SECURITY_REVIEW);
                                default -> false;
                            };
                            return Arguments.of(role, permission, reason, allowed);
                        })));
    }

    @Test
    void firstCatalogHasOnlyTheSelectedTwoRolesAndSixPermissions() {
        assertThat(PlatformRole.values()).containsExactly(
                PlatformRole.PLATFORM_OWNER, PlatformRole.SUPPORT_READ);
        assertThat(PlatformPermission.values()).containsExactlyInAnyOrder(
                PlatformPermission.STAFF_GRANTS_READ,
                PlatformPermission.STAFF_INVITE,
                PlatformPermission.STAFF_GRANT_CHANGE,
                PlatformPermission.STAFF_GRANT_SUSPEND_REVOKE,
                PlatformPermission.USER_SECURITY_READ,
                PlatformPermission.SECURITY_AUDIT_READ);
        assertThat(ReadReason.values()).containsExactlyInAnyOrder(
                ReadReason.USER_REQUESTED_SUPPORT, ReadReason.SECURITY_REVIEW, ReadReason.ACCESS_REVIEW);
        assertThat(PlatformRole.PLATFORM_OWNER.permissions()).containsExactlyInAnyOrder(PlatformPermission.values());
        assertThat(PlatformRole.SUPPORT_READ.permissions()).containsExactly(PlatformPermission.USER_SECURITY_READ);
        assertThatThrownBy(() -> PlatformRole.PLATFORM_OWNER.permissions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ACCESS_ADMIN", "owner", " PLATFORM_OWNER", "PLATFORM_OWNER ", "*", "future-role"})
    void unknownOrNormalizedRoleStringsDoNotAcquireAPlatformRole(String value) {
        assertThat(PlatformRole.parse(value)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "USER_SUSPEND", "SECURITY_AUDIT_EXPORT", "OWNER_HANDOVER_PROPOSE",
            "user_security_read", " USER_SECURITY_READ", "USER_SECURITY_READ ", "*"})
    void widerFutureAndMalformedPermissionsStayOutsideTheCatalog(String value) {
        assertThat(PlatformPermission.parse(value)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PlatformPermission.class)
    void exactPermissionNamesHaveAnExplicitParserMapping(PlatformPermission permission) {
        assertThat(PlatformPermission.parse(permission.name())).contains(permission);
    }

    @ParameterizedTest
    @EnumSource(PlatformRole.class)
    void exactRoleNamesHaveAnExplicitParserMapping(PlatformRole role) {
        assertThat(PlatformRole.parse(role.name())).contains(role);
    }

    @ParameterizedTest(name = "target status {0} does not replace actor eligibility")
    @EnumSource(UserStatus.class)
    void inactiveReadTargetStillHasTheSameMinimalUserSecurityProjection(UserStatus status) {
        Fixture fixture = Fixture.ownerRead();
        fixture.resource = new UserResource(TARGET, status, TARGET_BINDING);

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);
    }

    @Test
    void ownerMayReadOwnMetadataButDoesNotGainAnUnboundedBusinessProjection() {
        Fixture fixture = Fixture.ownerRead();
        fixture.resource = new UserResource(OWNER, UserStatus.ACTIVE, TARGET_BINDING);
        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);

        fixture.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
        fixture.resource = new GrantResource(fixture.grant, UserStatus.ACTIVE);
        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.GRANT_SECURITY_METADATA);

        fixture.request = new Request(PlatformPermission.USER_SECURITY_READ, Action.READ, ReadReason.SECURITY_REVIEW);
        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @ParameterizedTest(name = "same grant identity has contradictory {0}")
    @MethodSource("contradictorySelectedGrantSnapshots")
    void sameGrantIdentityCannotSupplyConflictingAuthorityAndReadSnapshots(
            String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.read(PlatformRole.PLATFORM_OWNER,
                PlatformPermission.STAFF_GRANTS_READ, ReadReason.ACCESS_REVIEW);
        fixture.resource = new GrantResource(fixture.grant, UserStatus.ACTIVE);
        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.GRANT_SECURITY_METADATA);
        change.accept(fixture);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> contradictorySelectedGrantSnapshots() {
        return Stream.of(
                change("recipient", f -> f.resource = new GrantResource(copyGrant(f.grant,
                        f.grant.id(), OTHER, f.grant.role(), 1, 1, 1, State.ACTIVE,
                        f.grant.startsAt(), f.grant.validity(), f.grant.scope()), UserStatus.ACTIVE)),
                change("role", f -> f.resource = new GrantResource(copyGrant(f.grant,
                        f.grant.id(), OWNER, PlatformRole.SUPPORT_READ, 1, 1, 1, State.ACTIVE,
                        f.grant.startsAt(), bounded(NOW.plusSeconds(3600)), userScope(List.of(TARGET))),
                        UserStatus.ACTIVE)),
                change("revision", f -> f.resource = new GrantResource(
                        withRevision(f.grant, 2), UserStatus.ACTIVE)));
    }

    @ParameterizedTest(name = "historical grant state={0}, recipient={1}, expired={2}")
    @MethodSource("historicalGrantResources")
    void readingGrantHistoryDoesNotRequireItsRecipientOrGrantToBeActionable(
            State state, UserStatus recipientStatus, boolean expired) {
        Fixture fixture = Fixture.read(PlatformRole.PLATFORM_OWNER,
                PlatformPermission.STAFF_GRANTS_READ, ReadReason.ACCESS_REVIEW);
        PlatformAccessGrant history = withState(supportGrant(), state);
        if (expired) {
            history = withTime(history, NOW.minusSeconds(3600), bounded(NOW.minusSeconds(1)));
        }
        fixture.resource = new GrantResource(history, recipientStatus);

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.GRANT_SECURITY_METADATA);
        assertThat(history.state()).isEqualTo(state);
        assertThat(history.revision()).isEqualTo(1);
    }

    static Stream<Arguments> historicalGrantResources() {
        return Arrays.stream(State.values()).flatMap(state -> Arrays.stream(UserStatus.values())
                .flatMap(status -> Stream.of(false, true).map(expired -> Arguments.of(state, status, expired))));
    }

    @ParameterizedTest
    @EnumSource(AuditEventKind.class)
    void eachClosedHistoricalEventKindHasOnlyTheSecurityAuditProjection(AuditEventKind kind) {
        Fixture fixture = Fixture.read(PlatformRole.PLATFORM_OWNER,
                PlatformPermission.SECURITY_AUDIT_READ, ReadReason.SECURITY_REVIEW);
        fixture.resource = new AuditResource(AUDIT_EVENT, kind);

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.SECURITY_AUDIT);
        assertThat(PlatformPermission.parse("SECURITY_AUDIT_EXPORT")).isEmpty();
    }

    @ParameterizedTest(name = "wrong resource: {0}")
    @MethodSource("wrongResources")
    void readPermissionNeverAdoptsAnotherResourceKind(String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.ownerRead();
        change.accept(fixture);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"ACTIVE", "SUSPENDED"})
    void ownMetadataCannotReportASecondNonRevokedGrantContradictingTheActorSlot(State state) {
        Fixture fixture = Fixture.ownerRead();
        fixture.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
        fixture.resource = new GrantResource(copyGrant(fixture.grant, id(999), OWNER,
                PlatformRole.PLATFORM_OWNER, 1, 1, 1, state, NOW.minusSeconds(3600), unbounded(), ownerScope()),
                UserStatus.ACTIVE);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @Test
    void ownRevokedHistoryDoesNotContradictTheSingleCurrentGrant() {
        Fixture fixture = Fixture.ownerRead();
        fixture.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
        fixture.resource = new GrantResource(copyGrant(fixture.grant, id(999), OWNER,
                PlatformRole.PLATFORM_OWNER, 1, 1, 1, State.REVOKED, NOW.minusSeconds(3600), unbounded(), ownerScope()),
                UserStatus.ACTIVE);

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.GRANT_SECURITY_METADATA);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void ownMetadataCannotContradictTheReportedCurrentActorStatus(UserStatus conflictingStatus) {
        Fixture fixture = Fixture.ownerRead();
        fixture.resource = new UserResource(OWNER, conflictingStatus, TARGET_BINDING);
        assertDenied(POLICY.evaluate(fixture.context()));

        fixture.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
        fixture.resource = new GrantResource(fixture.grant, conflictingStatus);
        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> wrongResources() {
        return Stream.of(
                change("user read with grant", f -> f.resource = new GrantResource(supportGrant(), UserStatus.ACTIVE)),
                change("user read with event", f -> f.resource = auditResource()),
                change("grant read with user", f -> f.request = new Request(
                        PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW)),
                change("grant read with event", f -> {
                    f.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = auditResource();
                }),
                change("audit read with user", f -> f.request = new Request(
                        PlatformPermission.SECURITY_AUDIT_READ, Action.READ, ReadReason.ACCESS_REVIEW)),
                change("audit read with grant", f -> {
                    f.request = new Request(PlatformPermission.SECURITY_AUDIT_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = new GrantResource(supportGrant(), UserStatus.ACTIVE);
                }),
                change("missing resource", f -> f.resource = null),
                change("missing user target", f -> f.resource = new UserResource(null, UserStatus.ACTIVE, TARGET_BINDING)),
                change("nonexisting user target", f -> f.resource = new UserResource(id(999), UserStatus.ACTIVE, TARGET_BINDING)),
                change("missing user status", f -> f.resource = new UserResource(TARGET, null, TARGET_BINDING)),
                change("missing grant target", f -> {
                    f.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = new GrantResource(null, UserStatus.ACTIVE);
                }),
                change("grant recipient existence is unverified", f -> {
                    f.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = new GrantResource(supportGrant(), UserStatus.ACTIVE);
                    f.existingUsers = Set.of(OWNER, TARGET);
                }),
                change("grant start is a future event", f -> {
                    f.request = new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = new GrantResource(withTime(supportGrant(), NOW.plusSeconds(1),
                            bounded(NOW.plusSeconds(3600))), UserStatus.ACTIVE);
                }),
                change("missing audit identity", f -> {
                    f.request = new Request(PlatformPermission.SECURITY_AUDIT_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = new AuditResource(null, AuditEventKind.values()[0]);
                }),
                change("missing audit kind", f -> {
                    f.request = new Request(PlatformPermission.SECURITY_AUDIT_READ, Action.READ, ReadReason.ACCESS_REVIEW);
                    f.resource = new AuditResource(AUDIT_EVENT, null);
                }));
    }

    @ParameterizedTest(name = "invalid ordinary context: {0}")
    @MethodSource("invalidOrdinaryContexts")
    void rejectsMissingForeignOrInconsistentOrdinaryAuthority(String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.ownerRead();
        change.accept(fixture);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> invalidOrdinaryContexts() {
        return Stream.of(
                change("missing now", f -> f.now = null),
                change("missing actor", f -> f.actor = null),
                change("missing grant", f -> f.grant = null),
                change("missing slot", f -> f.actorSlot = null),
                change("missing request", f -> f.request = null),
                change("missing permission", f -> f.request = new Request(null, Action.READ, ReadReason.SECURITY_REVIEW)),
                change("missing action", f -> f.request = new Request(PlatformPermission.USER_SECURITY_READ, null, ReadReason.SECURITY_REVIEW)),
                change("missing read reason", f -> f.request = new Request(PlatformPermission.USER_SECURITY_READ, Action.READ, null)),
                change("missing actor identity", f -> f.actor = new Actor(null, UserStatus.ACTIVE, ACCOUNT_GENERATION, SESSION_GENERATION)),
                change("missing actor status", f -> f.actor = new Actor(OWNER, null, ACCOUNT_GENERATION, SESSION_GENERATION)),
                change("suspended actor", f -> f.actor = new Actor(OWNER, UserStatus.SUSPENDED, ACCOUNT_GENERATION, SESSION_GENERATION)),
                change("deactivated actor", f -> f.actor = new Actor(OWNER, UserStatus.DEACTIVATED, ACCOUNT_GENERATION, SESSION_GENERATION)),
                change("missing account generation", f -> f.actor = new Actor(OWNER, UserStatus.ACTIVE, null, SESSION_GENERATION)),
                change("missing session generation", f -> f.actor = new Actor(OWNER, UserStatus.ACTIVE, ACCOUNT_GENERATION, null)),
                change("foreign actor", f -> f.actor = new Actor(OTHER, UserStatus.ACTIVE, ACCOUNT_GENERATION, SESSION_GENERATION)),
                change("missing grant identity", f -> f.grant = copyGrant(f.grant, null, OWNER, PlatformRole.PLATFORM_OWNER, 1, 1, 1, State.ACTIVE, NOW.minusSeconds(60), unbounded(), ownerScope())),
                change("foreign grant recipient", f -> f.grant = copyGrant(f.grant, OWNER_GRANT, OTHER, PlatformRole.PLATFORM_OWNER, 1, 1, 1, State.ACTIVE, NOW.minusSeconds(60), unbounded(), ownerScope())),
                change("unknown role", f -> f.grant = copyGrant(f.grant, OWNER_GRANT, OWNER, null, 1, 1, 1, State.ACTIVE, NOW.minusSeconds(60), unbounded(), ownerScope())),
                change("unknown catalog", f -> f.grant = withVersions(f.grant, 2, 1)),
                change("unknown bundle", f -> f.grant = withVersions(f.grant, 1, 2)),
                change("zero revision", f -> f.grant = withRevision(f.grant, 0)),
                change("suspended grant", f -> f.grant = withState(f.grant, State.SUSPENDED)),
                change("revoked grant", f -> f.grant = withState(f.grant, State.REVOKED)),
                change("missing lifecycle", f -> f.grant = withState(f.grant, null)),
                change("foreign slot owner", f -> f.actorSlot = new Slot(OTHER, List.of(OWNER_GRANT))),
                change("empty current slot", f -> f.actorSlot = new Slot(OWNER, List.of())),
                change("wrong current grant", f -> f.actorSlot = new Slot(OWNER, List.of(SUPPORT_GRANT))),
                change("two current grants", f -> f.actorSlot = new Slot(OWNER, List.of(OWNER_GRANT, SUPPORT_GRANT))),
                change("duplicate current grant", f -> f.actorSlot = new Slot(OWNER, List.of(OWNER_GRANT, OWNER_GRANT))),
                change("missing slot entries", f -> f.actorSlot = new Slot(OWNER, null)),
                change("null slot entry", f -> f.actorSlot = new Slot(OWNER, Arrays.asList((UUID) null))),
                change("missing existence evidence", f -> f.existingUsers = null),
                change("null existence entry", f -> {
                    f.existingUsers = new HashSet<>(List.of(TARGET));
                    f.existingUsers.add(null);
                }),
                change("read must not carry mutation facts", f -> f.change = new Change(
                        new Slot(TARGET, List.of()), null, null, null, null)),
                change("empty existence evidence", f -> f.existingUsers = Set.of()));
    }

    @Test
    void nullContextReturnsDenialInsteadOfImplicitAuthority() {
        assertDenied(POLICY.evaluate(null));
    }

    @ParameterizedTest(name = "invalid validity: {0}")
    @MethodSource("invalidGrantValidity")
    void rejectsFutureStartExpiryEqualityAndAmbiguousValidity(String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.ownerRead();
        change.accept(fixture);
        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> invalidGrantValidity() {
        return Stream.of(
                change("missing start", f -> f.grant = withTime(f.grant, null, unbounded())),
                change("future start", f -> f.grant = withTime(f.grant, NOW.plusNanos(1), unbounded())),
                change("expiry equality", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), bounded(NOW))),
                change("already expired", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), bounded(NOW.minusNanos(1)))),
                change("expiry before start", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), bounded(NOW.minusSeconds(61)))),
                change("missing validity", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), null)),
                change("unknown validity kind", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), new Validity(null, null))),
                change("bounded without end", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), bounded(null))),
                change("unbounded with end", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), new Validity(ValidityKind.UNBOUNDED, NOW.plusSeconds(60)))));
    }

    @Test
    void permitsStartEqualityAndFutureExpiryWithoutTreatingExpiryAsAnEvent() {
        Fixture fixture = Fixture.ownerRead();
        fixture.grant = withTime(fixture.grant, NOW, bounded(NOW.plusNanos(1)));

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);
    }

    @ParameterizedTest(name = "support scope size {0}")
    @MethodSource("validSupportSizes")
    void supportMayReadAnExactTargetAtBothValidScopeSizeBoundaries(int count) {
        Fixture fixture = Fixture.read(PlatformRole.SUPPORT_READ, PlatformPermission.USER_SECURITY_READ,
                ReadReason.USER_REQUESTED_SUPPORT);
        List<UUID> targets = targetIds(count);
        fixture.grant = withScope(fixture.grant, userScope(targets));
        fixture.existingUsers = usersWith(targets);
        fixture.resource = new UserResource(targets.getLast(), UserStatus.ACTIVE, TARGET_BINDING);

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);
    }

    static Stream<Integer> validSupportSizes() {
        return Stream.of(1, 100);
    }

    @ParameterizedTest(name = "invalid support scope: {0}")
    @MethodSource("invalidSupportScopes")
    void deniesInvalidScopeRatherThanNormalizingIt(String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.read(PlatformRole.SUPPORT_READ, PlatformPermission.USER_SECURITY_READ,
                ReadReason.USER_REQUESTED_SUPPORT);
        change.accept(fixture);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> invalidSupportScopes() {
        return Stream.of(
                change("empty", f -> f.grant = withScope(f.grant, userScope(List.of()))),
                change("101 targets", f -> {
                    List<UUID> targets = targetIds(101);
                    f.grant = withScope(f.grant, userScope(targets));
                    f.existingUsers = usersWith(targets);
                    f.resource = new UserResource(targets.getFirst(), UserStatus.ACTIVE, TARGET_BINDING);
                }),
                change("duplicate targets", f -> f.grant = withScope(f.grant, userScope(List.of(TARGET, TARGET)))),
                change("unverified target in set", f -> f.grant = withScope(f.grant, userScope(List.of(TARGET, id(999))))),
                change("target outside set", f -> f.resource = new UserResource(OTHER, UserStatus.ACTIVE, TARGET_BINDING)),
                change("platform scope is not support wildcard", f -> f.grant = withScope(f.grant, ownerScope())),
                change("missing scope", f -> f.grant = withScope(f.grant, null)),
                change("missing target collection", f -> f.grant = withScope(f.grant, userScope(null))),
                change("null target entry", f -> f.grant = withScope(f.grant, userScope(Arrays.asList(TARGET, null)))),
                change("unknown scope kind", f -> f.grant = withScope(f.grant, new Scope(null, List.of(TARGET)))),
                change("support cannot be unbounded", f -> f.grant = withTime(f.grant, NOW.minusSeconds(60), unbounded())));
    }

    @Test
    void ownerScopeMustBeExactPlatformMetadataAndNotAnArbitraryUserSet() {
        Fixture fixture = Fixture.ownerRead();
        fixture.grant = withScope(fixture.grant, userScope(List.of(TARGET)));
        assertDenied(POLICY.evaluate(fixture.context()));

        fixture.grant = withScope(fixture.grant, new Scope(ScopeKind.PLATFORM_SECURITY_METADATA, List.of(TARGET)));
        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @ParameterizedTest(name = "mismatched assurance stamp: {0}")
    @MethodSource("foreignStamps")
    void elevationMustMatchEveryCurrentStampComponent(String label, Stamp stamp) {
        Fixture fixture = Fixture.ownerRead();
        fixture.assurance = assuranceWithStamp(fixture.assurance, stamp);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> foreignStamps() {
        return Stream.of(
                Arguments.of("actor", new Stamp(OTHER, ACCOUNT_GENERATION, SESSION_GENERATION, OWNER_GRANT, 1)),
                Arguments.of("account eligibility", new Stamp(OWNER, id(999), SESSION_GENERATION, OWNER_GRANT, 1)),
                Arguments.of("session", new Stamp(OWNER, ACCOUNT_GENERATION, id(999), OWNER_GRANT, 1)),
                Arguments.of("grant", new Stamp(OWNER, ACCOUNT_GENERATION, SESSION_GENERATION, SUPPORT_GRANT, 1)),
                Arguments.of("revision", new Stamp(OWNER, ACCOUNT_GENERATION, SESSION_GENERATION, OWNER_GRANT, 2)),
                Arguments.of("missing actor", new Stamp(null, ACCOUNT_GENERATION, SESSION_GENERATION, OWNER_GRANT, 1)),
                Arguments.of("missing account eligibility", new Stamp(OWNER, null, SESSION_GENERATION, OWNER_GRANT, 1)),
                Arguments.of("missing session", new Stamp(OWNER, ACCOUNT_GENERATION, null, OWNER_GRANT, 1)),
                Arguments.of("missing grant", new Stamp(OWNER, ACCOUNT_GENERATION, SESSION_GENERATION, null, 1)),
                Arguments.of("zero revision", new Stamp(OWNER, ACCOUNT_GENERATION, SESSION_GENERATION, OWNER_GRANT, 0)));
    }

    @Test
    void statusReactivationDoesNotReviveThePreviouslyMatchingElevation() {
        Fixture fixture = Fixture.ownerRead();
        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);

        fixture.actor = new Actor(OWNER, UserStatus.SUSPENDED, id(900), SESSION_GENERATION);
        assertDenied(POLICY.evaluate(fixture.context()));
        fixture.actor = new Actor(OWNER, UserStatus.ACTIVE, id(901), SESSION_GENERATION);
        assertDenied(POLICY.evaluate(fixture.context()));

        fixture.assurance = assuranceWithStamp(fixture.assurance,
                new Stamp(OWNER, id(901), SESSION_GENERATION, OWNER_GRANT, 1));
        // The supplied replacement represents separately established evidence, not an issuer implementation.
        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);
    }

    @ParameterizedTest(name = "invalid assurance: {0}")
    @MethodSource("invalidAssurance")
    void deniesUnverifiedMissingFutureAndOutOfOrderAssurance(String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.ownerRead();
        change.accept(fixture);
        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> invalidAssurance() {
        return Stream.of(
                change("missing evidence", f -> f.assurance = null),
                change("unverified", f -> f.assurance = withAssurance(f.assurance, Verification.UNVERIFIED, f.assurance.stamp(), f.assurance.mfaAt(), f.assurance.boundAt(), f.assurance.lastActivityAt(), null)),
                change("wrong verified purpose", f -> f.assurance = withAssurance(f.assurance, Verification.VERIFIED_STEP_UP, f.assurance.stamp(), f.assurance.mfaAt(), f.assurance.boundAt(), f.assurance.lastActivityAt(), null)),
                change("missing verification", f -> f.assurance = withAssurance(f.assurance, null, f.assurance.stamp(), f.assurance.mfaAt(), f.assurance.boundAt(), f.assurance.lastActivityAt(), null)),
                change("missing stamp", f -> f.assurance = assuranceWithStamp(f.assurance, null)),
                change("missing MFA time", f -> f.assurance = withTimes(f.assurance, null, NOW.minusSeconds(60), NOW.minusSeconds(30))),
                change("missing binding time", f -> f.assurance = withTimes(f.assurance, NOW.minusSeconds(120), null, NOW.minusSeconds(30))),
                change("missing activity time", f -> f.assurance = withTimes(f.assurance, NOW.minusSeconds(120), NOW.minusSeconds(60), null)),
                change("future MFA event", f -> f.assurance = withTimes(f.assurance, NOW.plusNanos(1), NOW.plusNanos(1), NOW.plusNanos(1))),
                change("future binding", f -> f.assurance = withTimes(f.assurance, NOW.minusSeconds(120), NOW.plusNanos(1), NOW.plusNanos(1))),
                change("future activity", f -> f.assurance = withTimes(f.assurance, NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.plusNanos(1))),
                change("binding precedes MFA", f -> f.assurance = withTimes(f.assurance, NOW.minusSeconds(60), NOW.minusSeconds(61), NOW.minusSeconds(30))),
                change("activity precedes binding", f -> f.assurance = withTimes(f.assurance, NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(61))),
                change("huge old event is denied without overflow", f -> f.assurance = withTimes(f.assurance, Instant.MIN, Instant.MIN, NOW)));
    }

    @ParameterizedTest(name = "absolute age boundary {0}, allowed={1}")
    @MethodSource("absoluteAgeCases")
    void absoluteAgeUsesMfaEventRatherThanFreshBinding(Instant mfaAt, boolean allowed) {
        Fixture fixture = Fixture.ownerRead();
        fixture.assurance = withTimes(fixture.assurance, mfaAt, NOW, NOW);
        assertReadOutcome(POLICY.evaluate(fixture.context()), allowed);
    }

    static Stream<Arguments> absoluteAgeCases() {
        return Stream.of(
                Arguments.of(NOW, true),
                Arguments.of(NOW.minusSeconds(600).plusNanos(1), true),
                Arguments.of(NOW.minusSeconds(600), false),
                Arguments.of(NOW.minusSeconds(600).minusNanos(1), false));
    }

    @ParameterizedTest(name = "idle boundary {0}, allowed={1}")
    @MethodSource("idleAgeCases")
    void idleUsesPriorActivityWithoutRefreshingIt(Instant activityAt, boolean allowed) {
        Fixture fixture = Fixture.ownerRead();
        fixture.assurance = withTimes(fixture.assurance, NOW.minusSeconds(540), NOW.minusSeconds(500), activityAt);
        Assurance before = fixture.assurance;

        assertReadOutcome(POLICY.evaluate(fixture.context()), allowed);
        assertThat(fixture.assurance).isEqualTo(before);
        assertThat(fixture.assurance.lastActivityAt()).isEqualTo(activityAt);
    }

    static Stream<Arguments> idleAgeCases() {
        return Stream.of(
                Arguments.of(NOW, true),
                Arguments.of(NOW.minusSeconds(300).plusNanos(1), true),
                Arguments.of(NOW.minusSeconds(300), false),
                Arguments.of(NOW.minusSeconds(300).minusNanos(1), false));
    }

    @Test
    void readsDoNotRequireCriticalStepUpButRemainMfaProtected() {
        Fixture fixture = Fixture.ownerRead();
        assertThat(fixture.assurance.stepUp()).isNull();
        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);

        fixture.assurance = withAssurance(fixture.assurance, Verification.UNVERIFIED,
                fixture.assurance.stamp(), NOW, NOW, NOW,
                new StepUp(Verification.VERIFIED_STEP_UP, fixture.assurance.stamp(), NOW));
        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @ParameterizedTest(name = "critical step-up stamp mismatch: {0}")
    @MethodSource("foreignStamps")
    void criticalStepUpMustMatchEveryCurrentStampComponent(String label, Stamp stamp) {
        Fixture fixture = Fixture.invite();
        assertAllowedMutation(POLICY.evaluate(fixture.context()));
        fixture.assurance = withAssurance(fixture.assurance, Verification.VERIFIED_MFA,
                fixture.assurance.stamp(), fixture.assurance.mfaAt(), fixture.assurance.boundAt(),
                fixture.assurance.lastActivityAt(), new StepUp(Verification.VERIFIED_STEP_UP, stamp, NOW));

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @ParameterizedTest(name = "critical step-up boundary {0}, allowed={1}")
    @MethodSource("idleAgeCases")
    void criticalStepUpHasItsOwnStrictFiveMinuteLimit(Instant stepUpAt, boolean allowed) {
        Fixture fixture = Fixture.invite();
        fixture.assurance = withAssurance(fixture.assurance, Verification.VERIFIED_MFA,
                fixture.assurance.stamp(), NOW.minusSeconds(540), NOW.minusSeconds(500), NOW.minusSeconds(30),
                new StepUp(Verification.VERIFIED_STEP_UP, fixture.assurance.stamp(), stepUpAt));

        PlatformAccessDecision decision = POLICY.evaluate(fixture.context());
        if (allowed) {
            assertAllowedMutation(decision);
        } else {
            assertDenied(decision);
        }
    }

    @ParameterizedTest(name = "critical evidence denied: {0}")
    @MethodSource("invalidCriticalEvidence")
    void freshStepUpCannotReplaceOtherRequiredAssurance(String label, Consumer<Fixture> change) {
        Fixture fixture = Fixture.invite();
        assertAllowedMutation(POLICY.evaluate(fixture.context()));
        change.accept(fixture);

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    static Stream<Arguments> invalidCriticalEvidence() {
        return Stream.of(
                change("missing step-up", f -> f.assurance = withAssurance(f.assurance,
                        Verification.VERIFIED_MFA, f.assurance.stamp(), f.assurance.mfaAt(),
                        f.assurance.boundAt(), f.assurance.lastActivityAt(), null)),
                change("unverified step-up", f -> replaceStepUp(f,
                        new StepUp(Verification.UNVERIFIED, f.assurance.stamp(), NOW))),
                change("missing step-up verification", f -> replaceStepUp(f,
                        new StepUp(null, f.assurance.stamp(), NOW))),
                change("MFA label is not critical-purpose proof", f -> replaceStepUp(f,
                        new StepUp(Verification.VERIFIED_MFA, f.assurance.stamp(), NOW))),
                change("missing step-up stamp", f -> replaceStepUp(f,
                        new StepUp(Verification.VERIFIED_STEP_UP, null, NOW))),
                change("missing step-up timestamp", f -> replaceStepUp(f,
                        new StepUp(Verification.VERIFIED_STEP_UP, f.assurance.stamp(), null))),
                change("future step-up", f -> replaceStepUp(f,
                        new StepUp(Verification.VERIFIED_STEP_UP, f.assurance.stamp(), NOW.plusNanos(1)))),
                change("step-up predates MFA", f -> replaceStepUp(f,
                        new StepUp(Verification.VERIFIED_STEP_UP, f.assurance.stamp(), f.assurance.mfaAt().minusNanos(1)))),
                change("fresh step-up with stale absolute age", f -> {
                    f.assurance = withTimes(f.assurance, NOW.minusSeconds(600), NOW.minusSeconds(60), NOW);
                    replaceStepUp(f, new StepUp(Verification.VERIFIED_STEP_UP, f.assurance.stamp(), NOW));
                }),
                change("fresh step-up with expired idle", f -> {
                    f.assurance = withTimes(f.assurance, NOW.minusSeconds(540), NOW.minusSeconds(500), NOW.minusSeconds(300));
                    replaceStepUp(f, new StepUp(Verification.VERIFIED_STEP_UP, f.assurance.stamp(), NOW));
                }),
                change("fresh step-up with expired actor grant", f ->
                        f.grant = withTime(f.grant, NOW.minusSeconds(3600), bounded(NOW))),
                change("read reason cannot authorize a mutation", f -> f.request = new Request(
                        PlatformPermission.STAFF_INVITE, Action.INVITE, ReadReason.SECURITY_REVIEW)));
    }

    @Test
    void evaluationIsRepeatableAndDoesNotReserveAnInvitationSlotOrRefreshActivity() {
        Fixture fixture = Fixture.invite();
        PlatformAccessContext before = fixture.context();
        PlatformAccessDecision first = POLICY.evaluate(before);

        assertAllowedMutation(first);
        assertThat(POLICY.evaluate(before)).isEqualTo(first);
        assertThat(before).isEqualTo(fixture.context());
        assertThat(before.change().targetSlot().nonRevokedGrantIds()).isEmpty();
        assertThat(before.grant().revision()).isEqualTo(1);
        assertThat(before.assurance().lastActivityAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void existenceEvidenceIsResourceSpecificNotASecondActorAuthenticationAuthority() {
        Fixture fixture = Fixture.ownerRead();
        fixture.existingUsers = Set.of(TARGET);

        assertAllowedRead(POLICY.evaluate(fixture.context()), Projection.USER_SECURITY);
    }

    @Test
    void extremeEvaluationTimeCannotWrapTheSupportTermCeilingIntoAuthority() {
        Fixture fixture = Fixture.invite();
        fixture.now = Instant.MAX.minusSeconds(1);
        fixture.grant = withTime(fixture.grant, fixture.now.minusSeconds(60), unbounded());
        fixture.assurance = withTimes(fixture.assurance, fixture.now.minusSeconds(30),
                fixture.now.minusSeconds(20), fixture.now.minusSeconds(10));
        replaceStepUp(fixture, new StepUp(Verification.VERIFIED_STEP_UP, fixture.assurance.stamp(), fixture.now));
        fixture.change = new Change(new Slot(TARGET, List.of()), null, null, null,
                new Invitation(OWNER, OWNER_GRANT, 1, TARGET, TARGET_BINDING, PlatformRole.SUPPORT_READ,
                        1, 1, userScope(List.of(TARGET)), fixture.now, Instant.MAX));

        assertDenied(POLICY.evaluate(fixture.context()));
    }

    @Test
    void snapshotsDefensivelyCopyCollectionsWithoutBecomingLiveProof() {
        List<UUID> targets = new ArrayList<>(List.of(TARGET));
        List<UUID> grants = new ArrayList<>(List.of(SUPPORT_GRANT));
        Set<UUID> existing = new HashSet<>(List.of(OWNER, SUPPORT, TARGET, OTHER));
        Fixture fixture = Fixture.read(PlatformRole.SUPPORT_READ,
                PlatformPermission.USER_SECURITY_READ, ReadReason.USER_REQUESTED_SUPPORT);
        fixture.grant = withScope(fixture.grant, new Scope(ScopeKind.EXACT_USERS, targets));
        fixture.actorSlot = new Slot(SUPPORT, grants);
        fixture.existingUsers = existing;
        PlatformAccessContext snapshot = fixture.context();

        targets.clear();
        grants.clear();
        existing.clear();

        assertThat(snapshot.grant().scope().userIds()).containsExactly(TARGET);
        assertThat(snapshot.actorSlot().nonRevokedGrantIds()).containsExactly(SUPPORT_GRANT);
        assertThat(snapshot.existingUsers()).containsExactlyInAnyOrder(OWNER, SUPPORT, TARGET, OTHER);
        assertAllowedRead(POLICY.evaluate(snapshot), Projection.USER_SECURITY);
        assertThatThrownBy(() -> snapshot.grant().scope().userIds().add(OTHER))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.actorSlot().nonRevokedGrantIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.existingUsers().add(id(999)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertReadOutcome(PlatformAccessDecision result, boolean allowed) {
        if (allowed) {
            assertAllowedRead(result, Projection.USER_SECURITY);
        } else {
            assertDenied(result);
        }
    }

    private static void assertAllowedRead(PlatformAccessDecision result, Projection projection) {
        assertThat(result.allowed()).isTrue();
        assertThat(result.projection()).isEqualTo(projection);
        assertThat(result.projectedNextRevision()).isNull();
    }

    private static void assertDenied(PlatformAccessDecision result) {
        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isNotNull().isNotEqualTo(PlatformAccessDecision.Code.ALLOW);
        assertThat(result.projection()).isEqualTo(Projection.NONE);
        assertThat(result.projectedNextRevision()).isNull();
    }

    private static void assertAllowedMutation(PlatformAccessDecision result) {
        assertThat(result.allowed()).isTrue();
        assertThat(result.code()).isEqualTo(PlatformAccessDecision.Code.ALLOW);
        assertThat(result.projection()).isEqualTo(Projection.NONE);
        assertThat(result.projectedNextRevision()).isNull();
    }

    private static void replaceStepUp(Fixture fixture, StepUp stepUp) {
        fixture.assurance = withAssurance(fixture.assurance, fixture.assurance.verification(),
                fixture.assurance.stamp(), fixture.assurance.mfaAt(), fixture.assurance.boundAt(),
                fixture.assurance.lastActivityAt(), stepUp);
    }

    private static Arguments change(String label, Consumer<Fixture> change) {
        return Arguments.of(label, change);
    }

    private static Projection projection(PlatformPermission permission) {
        return switch (permission) {
            case USER_SECURITY_READ -> Projection.USER_SECURITY;
            case STAFF_GRANTS_READ -> Projection.GRANT_SECURITY_METADATA;
            case SECURITY_AUDIT_READ -> Projection.SECURITY_AUDIT;
            default -> throw new IllegalArgumentException("Not a read permission");
        };
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }

    private static List<UUID> targetIds(int count) {
        return IntStream.range(0, count).mapToObj(i -> id(1000L + i)).toList();
    }

    private static Set<UUID> usersWith(List<UUID> targets) {
        Set<UUID> users = new HashSet<>(targets);
        users.addAll(List.of(OWNER, SUPPORT, TARGET, OTHER));
        return users;
    }

    private static Validity unbounded() {
        return new Validity(ValidityKind.UNBOUNDED, null);
    }

    private static Validity bounded(Instant end) {
        return new Validity(ValidityKind.BOUNDED, end);
    }

    private static Scope ownerScope() {
        return new Scope(ScopeKind.PLATFORM_SECURITY_METADATA, List.of());
    }

    private static Scope userScope(List<UUID> targets) {
        return new Scope(ScopeKind.EXACT_USERS, targets);
    }

    private static PlatformAccessGrant supportGrant() {
        return new PlatformAccessGrant(SUPPORT_GRANT, SUPPORT, PlatformRole.SUPPORT_READ, 1, 1, 1,
                State.ACTIVE, NOW.minusSeconds(3600), bounded(NOW.plusSeconds(86_400)), userScope(List.of(TARGET)));
    }

    private static PlatformAccessGrant copyGrant(PlatformAccessGrant ignored, UUID grantId, UUID recipientId,
            PlatformRole role, int catalog, int bundle, long revision, State state, Instant startsAt,
            Validity validity, Scope scope) {
        return new PlatformAccessGrant(grantId, recipientId, role, catalog, bundle, revision,
                state, startsAt, validity, scope);
    }

    private static PlatformAccessGrant withState(PlatformAccessGrant grant, State state) {
        return copyGrant(grant, grant.id(), grant.recipientId(), grant.role(), grant.catalogVersion(),
                grant.bundleVersion(), grant.revision(), state, grant.startsAt(), grant.validity(), grant.scope());
    }

    private static PlatformAccessGrant withRevision(PlatformAccessGrant grant, long revision) {
        return copyGrant(grant, grant.id(), grant.recipientId(), grant.role(), grant.catalogVersion(),
                grant.bundleVersion(), revision, grant.state(), grant.startsAt(), grant.validity(), grant.scope());
    }

    private static PlatformAccessGrant withVersions(PlatformAccessGrant grant, int catalog, int bundle) {
        return copyGrant(grant, grant.id(), grant.recipientId(), grant.role(), catalog, bundle,
                grant.revision(), grant.state(), grant.startsAt(), grant.validity(), grant.scope());
    }

    private static PlatformAccessGrant withTime(PlatformAccessGrant grant, Instant startsAt, Validity validity) {
        return copyGrant(grant, grant.id(), grant.recipientId(), grant.role(), grant.catalogVersion(),
                grant.bundleVersion(), grant.revision(), grant.state(), startsAt, validity, grant.scope());
    }

    private static PlatformAccessGrant withScope(PlatformAccessGrant grant, Scope scope) {
        return copyGrant(grant, grant.id(), grant.recipientId(), grant.role(), grant.catalogVersion(),
                grant.bundleVersion(), grant.revision(), grant.state(), grant.startsAt(), grant.validity(), scope);
    }

    private static Assurance withAssurance(Assurance ignored, Verification verification, Stamp stamp,
            Instant mfaAt, Instant boundAt, Instant activityAt, StepUp stepUp) {
        return new Assurance(verification, stamp, mfaAt, boundAt, activityAt, stepUp);
    }

    private static Assurance withTimes(Assurance assurance, Instant mfaAt, Instant boundAt, Instant activityAt) {
        return withAssurance(assurance, assurance.verification(), assurance.stamp(), mfaAt, boundAt,
                activityAt, assurance.stepUp());
    }

    private static Assurance assuranceWithStamp(Assurance assurance, Stamp stamp) {
        return withAssurance(assurance, assurance.verification(), stamp, assurance.mfaAt(),
                assurance.boundAt(), assurance.lastActivityAt(), assurance.stepUp());
    }

    private static AuditResource auditResource() {
        return new AuditResource(AUDIT_EVENT, AuditEventKind.ACCESS_CHANGE);
    }

    private static final class Fixture {
        private Instant now = NOW;
        private Actor actor;
        private PlatformAccessGrant grant;
        private Slot actorSlot;
        private Assurance assurance;
        private Request request;
        private Resource resource;
        private Change change;
        private Set<UUID> existingUsers = Set.of(OWNER, SUPPORT, TARGET, OTHER);

        private static Fixture ownerRead() {
            return read(PlatformRole.PLATFORM_OWNER, PlatformPermission.USER_SECURITY_READ, ReadReason.SECURITY_REVIEW);
        }

        private static Fixture invite() {
            Fixture fixture = ownerRead();
            fixture.request = new Request(PlatformPermission.STAFF_INVITE, Action.INVITE, null);
            fixture.change = new Change(new Slot(TARGET, List.of()), null, null, null,
                    new Invitation(OWNER, OWNER_GRANT, 1, TARGET, TARGET_BINDING, PlatformRole.SUPPORT_READ,
                            1, 1, userScope(List.of(TARGET)), NOW, NOW.plusSeconds(86_400)));
            replaceStepUp(fixture, new StepUp(Verification.VERIFIED_STEP_UP, fixture.assurance.stamp(), NOW.minusSeconds(120)));
            return fixture;
        }

        private static Fixture read(PlatformRole role, PlatformPermission permission, ReadReason reason) {
            Fixture fixture = new Fixture();
            UUID actorId = role == PlatformRole.PLATFORM_OWNER ? OWNER : SUPPORT;
            UUID grantId = role == PlatformRole.PLATFORM_OWNER ? OWNER_GRANT : SUPPORT_GRANT;
            fixture.actor = new Actor(actorId, UserStatus.ACTIVE, ACCOUNT_GENERATION, SESSION_GENERATION);
            fixture.grant = role == PlatformRole.PLATFORM_OWNER
                    ? new PlatformAccessGrant(grantId, actorId, role, 1, 1, 1, State.ACTIVE,
                            NOW.minusSeconds(3600), unbounded(), ownerScope())
                    : supportGrant();
            fixture.actorSlot = new Slot(actorId, List.of(grantId));
            fixture.assurance = new Assurance(Verification.VERIFIED_MFA,
                    new Stamp(actorId, ACCOUNT_GENERATION, SESSION_GENERATION, grantId, 1),
                    NOW.minusSeconds(360), NOW.minusSeconds(300), NOW.minusSeconds(60), null);
            fixture.request = new Request(permission, Action.READ, reason);
            fixture.resource = switch (permission) {
                case STAFF_GRANTS_READ -> new GrantResource(supportGrant(), UserStatus.ACTIVE);
                case SECURITY_AUDIT_READ -> auditResource();
                default -> new UserResource(TARGET, UserStatus.ACTIVE, TARGET_BINDING);
            };
            return fixture;
        }

        private PlatformAccessContext context() {
            return new PlatformAccessContext(now, actor, grant, actorSlot, assurance, request,
                    resource, change, existingUsers);
        }
    }
}
