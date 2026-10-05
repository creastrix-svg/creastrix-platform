package com.creastrix.platform.platformaccess;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.creastrix.platform.observability.Diagnostics.Completion;
import com.creastrix.platform.observability.TransactionDiagnostics.Operation;
import com.creastrix.platform.platformaccess.application.OwnedPlatformAccessTransactions;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.*;
import com.creastrix.platform.platformaccess.application.PlatformAccessService;
import com.creastrix.platform.platformaccess.application.TrustedPlatformAccessFacts;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.*;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.State;
import com.creastrix.platform.platformaccess.domain.PlatformPermission;
import com.creastrix.platform.platformaccess.persistence.JdbcPlatformAccessRepository;
import com.creastrix.platform.user.domain.UserStatus;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.creastrix.platform.platformaccess.PlatformAccessTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unwired application/JDBC acceptance on synthetic grants, not live MFA or
 * employee activation. Privileged setup does not replace service admission.
 * Each case owns its database; immutable S storage and I1 remain unchanged.
 */
@Testcontainers
@Timeout(120)
class PlatformAccessWorkflowIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static DataSource fresh() throws Exception {
        DataSource source = database(POSTGRES);
        assertThat(flyway(source, "12").migrate().migrationsExecuted).isEqualTo(12);
        return source;
    }

    enum RevokeEntry { REGISTER, EXECUTE, RECEIPT }

    @ParameterizedTest(name = "Stable discovery: {0} with {1}")
    @CsvSource({"REGISTER,SUPPORT_READ", "EXECUTE,SUPPORT_READ", "RECEIPT,SUPPORT_READ",
            "REGISTER,SUSPENDED_OWNER", "EXECUTE,SUSPENDED_OWNER", "RECEIPT,SUSPENDED_OWNER"})
    void stableRecipientMismatchDoesNotDiscloseGrantBeforeCurrentAuthority(RevokeEntry entry, String actorKind)
            throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, actorKind.equals("SUSPENDED_OWNER"));
        if (actorKind.equals("SUSPENDED_OWNER")) {
            status(source, f.actor(), UserStatus.SUSPENDED);
            f.refreshFacts();
        }
        UUID suppliedTarget = tx(source, PlatformAccessTestSupport::user);
        Map<String, List<String>> before = discoveryBusinessState(source);
        HistoryObserver history = new HistoryObserver(source);
        AtomicInteger factsReads = new AtomicInteger();
        PlatformAccessService service = new PlatformAccessService(new OwnedPlatformAccessTransactions(history),
                new JdbcPlatformAccessRepository(), () -> {
                    factsReads.incrementAndGet();
                    return f.facts().get();
                });
        UUID operation = UUID.randomUUID();
        Intent absent = new Intent(f.actor(), operation, Kind.SUPPORT_REVOKE,
                suppliedTarget, UUID.randomUUID(), null, 1L);
        Intent aligned = new Intent(f.actor(), operation, Kind.SUPPORT_REVOKE,
                f.target(), f.targetGrant(), null, 1L);
        Intent mismatched = new Intent(f.actor(), operation, Kind.SUPPORT_REVOKE,
                suppliedTarget, f.targetGrant(), null, 1L);

        Result<?> absentResult = callRevokeEntry(service, entry, absent);
        int absentReads = factsReads.getAndSet(0);
        Result<?> alignedResult = callRevokeEntry(service, entry, aligned);
        int alignedReads = factsReads.getAndSet(0);
        Result<?> mismatchedResult = callRevokeEntry(service, entry, mismatched);
        int mismatchReads = factsReads.getAndSet(0);
        Result<?> repeatedMismatch = callRevokeEntry(service, entry, mismatched);
        int repeatedReads = factsReads.get();

        // Retain the reviewer's non-disclosure comparison, with real SQL and real service results.
        // Related assertions are soft so unchanged production reports the semantic mismatch,
        // rather than stopping first on the missing second facts read or wrong audit kind.
        long absentAudits = suppliedOnlyDenialCount(source, absent, entry);
        long alignedAudits = suppliedOnlyDenialCount(source, aligned, entry);
        long mismatchedAudits = suppliedOnlyDenialCount(source, mismatched, entry);
        long auditRows = count(source, "platform_access_audit_events");
        long technicalAudits = auditCount(source, "OPERATION_FAILED");
        Map<String, List<String>> after = discoveryBusinessState(source);
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(absentResult).as("absent grant").isEqualTo(new Denied<>());
            soft.assertThat(alignedResult).as("existing aligned grant").isEqualTo(new Denied<>());
            soft.assertThat(mismatchedResult).as("immutable recipient discovery is not a database outage")
                    .isEqualTo(absentResult);
            soft.assertThat(repeatedMismatch).as("stable repeated mismatch").isEqualTo(absentResult);
            soft.assertThat(absentReads).as("absent current-facts gate").isGreaterThanOrEqualTo(2);
            soft.assertThat(alignedReads).as("aligned current-facts gate").isGreaterThanOrEqualTo(2);
            soft.assertThat(mismatchReads).as("mismatch current-facts gate").isGreaterThanOrEqualTo(2);
            soft.assertThat(repeatedReads).as("repeated current-facts gate").isGreaterThanOrEqualTo(2);
            soft.assertThat(history.lookups.get()).as("operation history must remain unread").isZero();
            soft.assertThat(after).as("no business, intent or outcome changes").isEqualTo(before);
            soft.assertThat(auditRows).isEqualTo(4);
            soft.assertThat(absentAudits).as("safe supplied-only absent audit").isEqualTo(1);
            soft.assertThat(alignedAudits).as("safe supplied-only aligned audit").isEqualTo(1);
            soft.assertThat(mismatchedAudits).as("safe supplied-only mismatch and retry audit").isEqualTo(2);
            soft.assertThat(technicalAudits).as("semantic denials are not technical failures").isZero();
        });
    }

    @ParameterizedTest(name = "Active owner retains exact recipient: {0}")
    @ValueSource(strings = {"REGISTER", "EXECUTE", "RECEIPT"})
    void activeOwnerCannotAdoptDiscoveredRecipientButAlignedOperationStillSucceeds(String entryName)
            throws Exception {
        RevokeEntry entry = RevokeEntry.valueOf(entryName);
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        Intent aligned = f.revokeIntent(UUID.randomUUID());
        if (entry != RevokeEntry.REGISTER) { success(f.service().registerSupportRevoke(aligned)); }
        if (entry == RevokeEntry.RECEIPT) { success(f.service().revokeSupportGrant(aligned)); }
        UUID suppliedTarget = tx(source, PlatformAccessTestSupport::user);
        Intent mismatched = new Intent(f.actor(), aligned.operationId(), Kind.SUPPORT_REVOKE,
                suppliedTarget, f.targetGrant(), null, 1L);
        Map<String, List<String>> before = discoveryBusinessState(source);
        HistoryObserver history = new HistoryObserver(source);
        AtomicInteger factsReads = new AtomicInteger();
        PlatformAccessService service = new PlatformAccessService(new OwnedPlatformAccessTransactions(history),
                new JdbcPlatformAccessRepository(), () -> {
                    factsReads.incrementAndGet();
                    return f.facts().get();
                });

        assertThat(callRevokeEntry(service, entry, mismatched)).isEqualTo(new Denied<>());
        assertThat(factsReads.get()).as("owner mismatch still reaches the current-facts gate")
                .isGreaterThanOrEqualTo(2);
        assertThat(history.lookups).hasValue(0);
        assertThat(discoveryBusinessState(source)).isEqualTo(before);
        assertThat(suppliedOnlyDenialCount(source, mismatched, entry)).isEqualTo(1);
        assertThat(auditCount(source, "OPERATION_FAILED")).isZero();

        // A real aligned request must retain its existing registration/execution/receipt path.
        Object payload = success(callRevokeEntry(service, entry, aligned));
        if (entry == RevokeEntry.REGISTER) {
            assertThat(payload).isInstanceOf(RegistrationAck.class);
            assertThat(((RegistrationAck) payload).intent()).isEqualTo(aligned);
            assertThat(count(source, "platform_access_operation_outcomes")).isZero();
        } else {
            assertThat(payload).isInstanceOf(Receipt.class);
            Receipt receipt = (Receipt) payload;
            assertThat(receipt.intent()).isEqualTo(aligned);
            assertThat(receipt.kind()).isEqualTo(OutcomeKind.REVOKED);
            assertThat(receipt.afterState()).isEqualTo(State.REVOKED);
            assertThat(count(source, "platform_access_operation_outcomes")).isEqualTo(1);
        }
        assertThat(count(source, "platform_access_operation_intents")).isEqualTo(1);
    }

    @Test
    void mismatchedGrantLockTimeoutRemainsTechnicalThenStableRetryIsDenied() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, false);
        UUID suppliedTarget = tx(source, PlatformAccessTestSupport::user);
        Intent mismatched = new Intent(f.actor(), UUID.randomUUID(), Kind.SUPPORT_REVOKE,
                suppliedTarget, f.targetGrant(), null, 1L);
        Map<String, List<String>> before = discoveryBusinessState(source);
        HistoryObserver history = new HistoryObserver(source);
        ArrayBlockingQueue<Integer> connected = new ArrayBlockingQueue<>(4);
        DataSource observed = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = history.getConnection();
                connected.add(pid(connection));
                return connection;
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                return getConnection();
            }
        };
        var service = f.service(observed);
        try (Connection blocker = source.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            blocker.setAutoCommit(false);
            try {
                assertThat(scalar(blocker, "SELECT id FROM platform_access_grants WHERE id=? FOR NO KEY UPDATE",
                        UUID.class, f.targetGrant())).isEqualTo(f.targetGrant());
                int blockerPid = pid(blocker);
                var future = executor.submit(() -> service.registerSupportRevoke(mismatched));
                Integer waiterPid = connected.poll(5, TimeUnit.SECONDS);
                assertThat(waiterPid).isNotNull();
                awaitBlocked(source, waiterPid, blockerPid);
                failure(future.get(8, TimeUnit.SECONDS), Code.DATABASE_UNAVAILABLE);
            } finally {
                blocker.rollback();
            }
        }
        assertThat(auditCount(source, "OPERATION_FAILED")).isEqualTo(1);
        assertThat(auditCount(source, "ACCESS_DENIED")).isZero();
        assertThat(history.lookups).hasValue(0);
        assertThat(discoveryBusinessState(source)).isEqualTo(before);
        assertThat(service.registerSupportRevoke(mismatched)).isEqualTo(new Denied<>());
        assertThat(suppliedOnlyDenialCount(source, mismatched, RevokeEntry.REGISTER)).isEqualTo(1);
        assertThat(auditCount(source, "OPERATION_FAILED")).isEqualTo(1);
        assertThat(history.lookups).hasValue(0);
        assertThat(discoveryBusinessState(source)).isEqualTo(before);
    }

    @Test
    void terminatedDiscoveryConnectionIsNotNormalizedToResourceDenial() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, false);
        UUID suppliedTarget = tx(source, PlatformAccessTestSupport::user);
        Intent mismatched = new Intent(f.actor(), UUID.randomUUID(), Kind.SUPPORT_REVOKE,
                suppliedTarget, f.targetGrant(), null, 1L);
        Map<String, List<String>> before = discoveryBusinessState(source);
        HistoryObserver history = new HistoryObserver(source);
        AtomicBoolean terminated = new AtomicBoolean();
        AtomicReference<Throwable> discoveryFailure = new AtomicReference<>();
        DataSource fault = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection real = history.getConnection();
                int victimPid = pid(real);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            boolean discovery = method.getName().equals("prepareStatement")
                                    && args[0] instanceof String sql
                                    && sql.equals("SELECT recipient_user_id FROM platform_access_grants WHERE id=?");
                            if (discovery && terminated.compareAndSet(false, true)) {
                                // Terminate only this test's owned backend, never fabricate SQL or its result.
                                try (Connection observer = source.getConnection()) {
                                    assertThat(scalar(observer, "SELECT pg_terminate_backend(?)", Boolean.class,
                                            victimPid)).isTrue();
                                }
                            }
                            try {
                                Object result = method.invoke(real, args);
                                if (!discovery) { return result; }
                                PreparedStatement actual = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                            try { return m.invoke(actual, a); }
                                            catch (InvocationTargetException failure) {
                                                discoveryFailure.compareAndSet(null, failure.getCause());
                                                throw failure.getCause();
                                            }
                                        });
                            } catch (InvocationTargetException failure) {
                                if (discovery) { discoveryFailure.compareAndSet(null, failure.getCause()); }
                                throw failure.getCause();
                            }
                        });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                return getConnection();
            }
        };
        failure(f.service(fault).registerSupportRevoke(mismatched), Code.COMMIT_UNKNOWN);
        assertThat(terminated).isTrue();
        postgresFailure(discoveryFailure.get(), "57P01", null);
        assertThat(history.lookups).hasValue(0);
        assertThat(count(source, "platform_access_audit_events")).isZero();
        assertThat(discoveryBusinessState(source)).isEqualTo(before);
        // A lost rollback acknowledgement stays UNKNOWN, despite observer-confirmed no writes.
        assertThat(f.service().registerSupportRevoke(mismatched)).isEqualTo(new Denied<>());
        assertThat(suppliedOnlyDenialCount(source, mismatched, RevokeEntry.REGISTER)).isEqualTo(1);
        assertThat(discoveryBusinessState(source)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"true,USER_REQUESTED_SUPPORT,ACTIVE", "true,SECURITY_REVIEW,SUSPENDED",
            "false,USER_REQUESTED_SUPPORT,DEACTIVATED"})
    void currentReadReturnsOnlyExactUuidAndStatusAfterRegistrationAndAdmission(boolean owner, ReadReason reason,
                                                                              UserStatus targetStatus) throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, owner);
        status(source, f.target(), targetStatus);
        Intent intent = new Intent(f.actor(), UUID.randomUUID(), Kind.USER_SECURITY_READ,
                f.target(), null, reason, null);
        var service = f.service();
        RegistrationAck ack = success(service.registerUserSecurityRead(intent));
        assertThat(ack.intent()).isEqualTo(intent);
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
        assertThat(success(service.readUserSecurity(intent))).isEqualTo(new UserSecurity(f.target(), targetStatus));
        assertThat(Arrays.stream(UserSecurity.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("userId", "accountStatus");
        assertThat(count(source, "platform_access_operation_outcomes")).isEqualTo(1);
        assertThat(auditCount(source, "INTENT_RECORDED")).isEqualTo(1);
        assertThat(auditCount(source, "READ_ADMITTED")).isEqualTo(1);
        failure(service.readUserSecurity(intent), Code.TERMINAL_RECEIPT_REQUIRED);
        assertThat(count(source, "platform_access_operation_outcomes")).isEqualTo(1);
        Receipt receipt = success(service.readUserSecurityReceipt(intent, reason));
        assertThat(receipt.kind()).isEqualTo(OutcomeKind.READ_ADMITTED);
        assertThat(receipt.intent()).isEqualTo(intent);
        assertThat(Arrays.stream(Receipt.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("accountStatus", "sessionGeneration", "assurance");
        assertThat(auditCount(source, "READ_ADMITTED")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope", "missingTarget", "reason", "actorInactive", "grantSuspended",
            "grantRevoked", "wrongGeneration", "wrongSession", "wrongRevision", "unverifiedMfa"})
    void currentAuthorityDenialsHappenBeforeAnyOperationHistoryLookup(String cause) throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, false);
        Intent intent = f.readIntent(UUID.randomUUID());
        if (cause.equals("scope") || cause.equals("missingTarget")) {
            UUID target = cause.equals("scope") ? tx(source, PlatformAccessTestSupport::user) : UUID.randomUUID();
            intent = new Intent(f.actor(), intent.operationId(), Kind.USER_SECURITY_READ, target, null,
                    ReadReason.USER_REQUESTED_SUPPORT, null);
        } else if (cause.equals("reason")) {
            intent = new Intent(f.actor(), intent.operationId(), Kind.USER_SECURITY_READ, f.target(), null,
                    ReadReason.SECURITY_REVIEW, null);
        } else if (cause.equals("actorInactive")) {
            status(source, f.actor(), UserStatus.SUSPENDED);
        } else if (cause.equals("grantSuspended") || cause.equals("grantRevoked")) {
            moveGrant(source, f.actorGrant(), f.actor(), cause.equals("grantSuspended") ? State.SUSPENDED : State.REVOKED);
        } else {
            var old = f.facts().get();
            Assurance a = old.assurance();
            Stamp s = a.stamp();
            Stamp changed = new Stamp(s.userId(), cause.equals("wrongGeneration") ? UUID.randomUUID()
                    : s.accountEligibilityGeneration(), cause.equals("wrongSession") ? UUID.randomUUID()
                    : s.sessionGeneration(), s.grantId(), cause.equals("wrongRevision") ? 2 : s.grantRevision());
            f.facts().set(new TrustedPlatformAccessFacts.Snapshot(old.userId(), old.sessionGeneration(),
                    new Assurance(cause.equals("unverifiedMfa") ? Verification.UNVERIFIED : a.verification(),
                            changed, a.mfaAt(), a.boundAt(), a.lastActivityAt(), a.stepUp())));
        }
        HistoryObserver history = new HistoryObserver(source);
        var service = f.service(history);
        assertThat(service.registerUserSecurityRead(intent)).isInstanceOf(Denied.class);
        assertThat(service.readUserSecurity(intent)).isInstanceOf(Denied.class);
        assertThat(service.readUserSecurityReceipt(intent, intent.readReason())).isInstanceOf(Denied.class);
        assertThat(history.lookups).hasValue(0);
        assertThat(count(source, "platform_access_operation_intents")).isZero();
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
        assertThat(auditCount(source, "ACCESS_DENIED")).isEqualTo(3);
    }

    @Test
    void suspendReactivateInvalidatesSameSessionGrantStampWithoutChangingTheGrant() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        Intent intent = f.readIntent(UUID.randomUUID());
        success(f.service().registerUserSecurityRead(intent));
        Map<String, List<String>> grants;
        try (Connection c = source.getConnection()) {
            grants = rows(c, List.of("platform_access_grants", "platform_access_grant_targets"), false);
        }
        status(source, f.actor(), UserStatus.SUSPENDED);
        status(source, f.actor(), UserStatus.ACTIVE);
        assertThat(f.service().readUserSecurity(intent)).isInstanceOf(Denied.class);
        try (Connection c = source.getConnection()) {
            assertThat(scalar(c, "SELECT account_eligibility_generation FROM users WHERE id=?", Long.class, f.actor()))
                    .isEqualTo(3L);
            assertThat(rows(c, List.of("platform_access_grants", "platform_access_grant_targets"), false)).isEqualTo(grants);
        }
        UUID session = f.facts().get().sessionGeneration();
        f.refreshFacts();
        assertThat(f.facts().get().sessionGeneration()).isEqualTo(session);
        success(f.service().readUserSecurity(intent));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactDuplicateRegistrationAcknowledgesOnlyAfterFreshReceiptAudit(boolean revoke) throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        Intent intent = revoke ? f.revokeIntent(UUID.randomUUID()) : f.readIntent(UUID.randomUUID());
        var service = f.service();
        Result<RegistrationAck> first = revoke ? service.registerSupportRevoke(intent) : service.registerUserSecurityRead(intent);
        Result<RegistrationAck> second = revoke ? service.registerSupportRevoke(intent) : service.registerUserSecurityRead(intent);
        assertThat(success(second)).isEqualTo(success(first));
        assertThat(Arrays.stream(RegistrationAck.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("intent", "registeredAt");
        assertThat(count(source, "platform_access_operation_intents")).isEqualTo(1);
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
        assertThat(auditCount(source, "INTENT_RECORDED")).isEqualTo(1);
        assertThat(auditCount(source, "READ_ADMITTED")).isEqualTo(1);
        try (Connection c = source.getConnection()) {
            assertThat(scalar(c, "SELECT projection FROM platform_access_audit_events WHERE event_kind='READ_ADMITTED'",
                    String.class)).isEqualTo("OPERATION_RECEIPT");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"target", "reason", "kind"})
    void ownRegistrationCollisionDoesNotExtendToExecutionOrReceipt(String changedField) throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        Intent original = f.readIntent(UUID.randomUUID());
        var service = f.service();
        success(service.registerUserSecurityRead(original));
        success(service.readUserSecurity(original));
        Intent changed = switch (changedField) {
            case "target" -> new Intent(f.actor(), original.operationId(), Kind.USER_SECURITY_READ,
                    tx(source, PlatformAccessTestSupport::user), null, ReadReason.USER_REQUESTED_SUPPORT, null);
            case "reason" -> new Intent(f.actor(), original.operationId(), Kind.USER_SECURITY_READ,
                    f.target(), null, ReadReason.SECURITY_REVIEW, null);
            default -> f.revokeIntent(original.operationId());
        };
        if (changed.kind() == Kind.SUPPORT_REVOKE) {
            failure(service.revokeSupportGrant(changed), Code.NO_EXECUTABLE_INTENT);
            failure(service.readRevokeReceipt(changed), Code.NO_DISCLOSABLE_RECEIPT);
            failure(service.registerSupportRevoke(changed), Code.ID_UNAVAILABLE);
        } else {
            failure(service.readUserSecurity(changed), Code.NO_EXECUTABLE_INTENT);
            failure(service.readUserSecurityReceipt(changed, changed.readReason()), Code.NO_DISCLOSABLE_RECEIPT);
            failure(service.registerUserSecurityRead(changed), Code.ID_UNAVAILABLE);
        }
        assertThat(count(source, "platform_access_operation_intents")).isEqualTo(1);
        assertThat(count(source, "platform_access_operation_outcomes")).isEqualTo(1);
        assertThat(auditCount(source, "INTENT_RECORDED")).isEqualTo(1);
    }

    @Test
    void changedExpectedRevisionCollidesOnlyAfterCurrentTargetAuthorization() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        Intent original = f.revokeIntent(UUID.randomUUID());
        success(f.service().registerSupportRevoke(original));
        moveGrant(source, f.targetGrant(), f.actor(), State.SUSPENDED);
        Intent changed = new Intent(f.actor(), original.operationId(), Kind.SUPPORT_REVOKE,
                f.target(), f.targetGrant(), null, 2L);
        failure(f.service().revokeSupportGrant(changed), Code.NO_EXECUTABLE_INTENT);
        failure(f.service().readRevokeReceipt(changed), Code.NO_DISCLOSABLE_RECEIPT);
        failure(f.service().registerSupportRevoke(changed), Code.ID_UNAVAILABLE);
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,ACTIVE,false", "SUSPENDED,SUSPENDED,false", "ACTIVE,DEACTIVATED,true"})
    void exactOwnerRevokeIncludesSuspendedExpiredAndInactiveRecipient(State targetState, UserStatus userStatus,
                                                                   boolean expired) throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        UUID targetGrant = f.targetGrant();
        if (expired) {
            moveGrant(source, targetGrant, f.actor(), State.REVOKED);
            targetGrant = addGrant(source, f.target(), f.actor(), false, List.of(f.actor()), true);
        }
        if (targetState == State.SUSPENDED) { moveGrant(source, targetGrant, f.actor(), State.SUSPENDED); }
        status(source, f.target(), userStatus);
        long revision = targetState == State.SUSPENDED ? 2 : 1;
        Intent intent = new Intent(f.actor(), UUID.randomUUID(), Kind.SUPPORT_REVOKE,
                f.target(), targetGrant, null, revision);
        var service = f.service();
        success(service.registerSupportRevoke(intent));
        Receipt result = success(service.revokeSupportGrant(intent));
        assertThat(result.beforeState()).isEqualTo(targetState);
        assertThat(result.beforeRevision()).isEqualTo(revision);
        assertThat(result.afterState()).isEqualTo(State.REVOKED);
        assertThat(result.afterRevision()).isEqualTo(revision + 1);
        assertThat(result.kind()).isEqualTo(OutcomeKind.REVOKED);
        assertThat(service.revokeSupportGrant(intent)).isInstanceOf(Denied.class);
        assertThat(success(service.readRevokeReceipt(intent))).isEqualTo(result);
        assertThat(count(source, "platform_access_operation_outcomes")).isEqualTo(1);
        assertThat(auditCount(source, "GRANT_REVOKED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"self", "owner", "staleRevision", "terminal", "missingStepUp", "supportActor"})
    void revokeDenialDoesNotMutateTargetOrCreateSuccessHistory(String deniedCase) throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, !deniedCase.equals("supportActor"));
        Intent intent = f.revokeIntent(UUID.randomUUID());
        if (deniedCase.equals("self")) {
            intent = new Intent(f.actor(), intent.operationId(), Kind.SUPPORT_REVOKE, f.actor(), f.actorGrant(), null, 1L);
        } else if (deniedCase.equals("owner")) {
            UUID other = tx(source, PlatformAccessTestSupport::user);
            UUID otherGrant = addGrant(source, other, f.actor(), true, List.of(), false);
            intent = new Intent(f.actor(), intent.operationId(), Kind.SUPPORT_REVOKE, other, otherGrant, null, 1L);
        } else if (deniedCase.equals("staleRevision")) {
            intent = new Intent(f.actor(), intent.operationId(), Kind.SUPPORT_REVOKE, f.target(), f.targetGrant(), null, 2L);
        } else if (deniedCase.equals("terminal")) {
            moveGrant(source, f.targetGrant(), f.actor(), State.REVOKED);
        } else if (deniedCase.equals("missingStepUp")) {
            var old = f.facts().get();
            Assurance a = old.assurance();
            f.facts().set(new TrustedPlatformAccessFacts.Snapshot(old.userId(), old.sessionGeneration(),
                    new Assurance(a.verification(), a.stamp(), a.mfaAt(), a.boundAt(), a.lastActivityAt(), null)));
        }
        Map<String, List<String>> grants;
        try (Connection c = source.getConnection()) { grants = rows(c, List.of("platform_access_grants"), false); }
        assertThat(f.service().registerSupportRevoke(intent)).isInstanceOf(Denied.class);
        assertThat(f.service().revokeSupportGrant(intent)).isInstanceOf(Denied.class);
        try (Connection c = source.getConnection()) { assertThat(rows(c, List.of("platform_access_grants"), false)).isEqualTo(grants); }
        assertThat(count(source, "platform_access_operation_intents")).isZero();
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
        assertThat(auditCount(source, "GRANT_REVOKED")).isZero();
    }

    @Test
    void checkedRevisionOverflowIsDeniedBeforeAnyIntentOrMutation() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        // Reaching MAX naturally is infeasible. Seed MAX-1 only in this owned
        // fixture, restore the exact guard before any tested service operation,
        // then reach MAX through one ordinary enabled lifecycle transition.
        tx(source, c -> {
            execute(c, "ALTER TABLE platform_access_grants DISABLE TRIGGER platform_access_grants_enforce_lifecycle");
            try {
                execute(c, "UPDATE platform_access_grants SET revision=?,last_changed_by_user_id=?,"
                        + "last_changed_at=clock_timestamp() WHERE id=?", Long.MAX_VALUE - 1, f.actor(), f.targetGrant());
            } finally {
                execute(c, "ALTER TABLE platform_access_grants ENABLE TRIGGER platform_access_grants_enforce_lifecycle");
            }
            return null;
        });
        moveGrant(source, f.targetGrant(), f.actor(), State.SUSPENDED);
        Intent intent = new Intent(f.actor(), UUID.randomUUID(), Kind.SUPPORT_REVOKE,
                f.target(), f.targetGrant(), null, Long.MAX_VALUE);
        Map<String, List<String>> before;
        try (Connection c = source.getConnection()) {
            assertThat(scalar(c, "SELECT tgenabled::text FROM pg_trigger "
                    + "WHERE tgname='platform_access_grants_enforce_lifecycle'", String.class)).isEqualTo("O");
            assertThat(scalar(c, "SELECT revision FROM platform_access_grants WHERE id=?", Long.class, f.targetGrant()))
                    .isEqualTo(Long.MAX_VALUE);
            before = rows(c, List.of("platform_access_grants"), false);
        }
        assertThat(f.service().registerSupportRevoke(intent)).isInstanceOf(Denied.class);
        assertThat(f.service().revokeSupportGrant(intent)).isInstanceOf(Denied.class);
        try (Connection c = source.getConnection()) {
            assertThat(rows(c, List.of("platform_access_grants"), false)).isEqualTo(before);
            assertThat(strings(c, "SELECT reason FROM platform_access_audit_events ORDER BY event_at"))
                    .containsExactly("REVISION_OVERFLOW", "REVISION_OVERFLOW");
        }
        assertThat(count(source, "platform_access_operation_intents")).isZero();
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
    }

    @Test
    void semanticReceiptAbsencesHaveOneClosedShapeWithoutStoredHistory() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        var service = f.service();
        Intent pending = f.readIntent(UUID.randomUUID());
        success(service.registerUserSecurityRead(pending));
        UUID other = tx(source, PlatformAccessTestSupport::user);
        List<Intent> cases = List.of(f.readIntent(UUID.randomUUID()), pending,
                new Intent(f.actor(), pending.operationId(), Kind.USER_SECURITY_READ, f.target(), null,
                        ReadReason.SECURITY_REVIEW, null),
                new Intent(f.actor(), pending.operationId(), Kind.USER_SECURITY_READ, other, null,
                        ReadReason.USER_REQUESTED_SUPPORT, null),
                new Intent(other, pending.operationId(), Kind.USER_SECURITY_READ, f.target(), null,
                        ReadReason.USER_REQUESTED_SUPPORT, null),
                new Intent(UUID.randomUUID(), pending.operationId(), Kind.USER_SECURITY_READ, f.target(), null,
                        ReadReason.USER_REQUESTED_SUPPORT, null));
        for (Intent intent : cases) {
            failure(service.readUserSecurityReceipt(intent, intent.readReason()), Code.NO_DISCLOSABLE_RECEIPT);
        }
        assertThat(count(source, "platform_access_operation_outcomes")).isZero();
        failure(service.readUserSecurity(f.readIntent(UUID.randomUUID())), Code.NO_EXECUTABLE_INTENT);
        assertThat(count(source, "platform_access_operation_intents")).isEqualTo(1);
    }

    @Test
    void foreignReadNeverLooksUpHistoryButCurrentOtherOwnerCanReadExactRevokeReceipt() throws Exception {
        DataSource source = fresh();
        WorkflowFixture first = workflow(source, true);
        Intent read = first.readIntent(UUID.randomUUID());
        Intent revoke = first.revokeIntent(UUID.randomUUID());
        success(first.service().registerUserSecurityRead(read));
        success(first.service().readUserSecurity(read));
        success(first.service().registerSupportRevoke(revoke));
        Receipt original = success(first.service().revokeSupportGrant(revoke));
        WorkflowFixture second = workflow(source, true);
        status(source, first.actor(), UserStatus.DEACTIVATED);
        status(source, first.target(), UserStatus.SUSPENDED);
        HistoryObserver history = new HistoryObserver(source);
        var otherService = second.service(history);
        failure(otherService.readUserSecurityReceipt(read, ReadReason.SECURITY_REVIEW), Code.NO_DISCLOSABLE_RECEIPT);
        assertThat(otherService.readUserSecurity(read)).isInstanceOf(Denied.class);
        assertThat(otherService.registerUserSecurityRead(read)).isInstanceOf(Denied.class);
        assertThat(otherService.revokeSupportGrant(revoke)).isInstanceOf(Denied.class);
        assertThat(history.lookups).hasValue(0);
        assertThat(success(otherService.readRevokeReceipt(revoke))).isEqualTo(original);
        assertThat(history.lookups.get()).isGreaterThan(0);
        Intent missingInitiator = new Intent(UUID.randomUUID(), revoke.operationId(), Kind.SUPPORT_REVOKE,
                revoke.targetUserId(), revoke.targetGrantId(), null, revoke.expectedRevision());
        failure(otherService.readRevokeReceipt(missingInitiator), Code.NO_DISCLOSABLE_RECEIPT);
        assertThat(count(source, "platform_access_operation_outcomes")).isEqualTo(2);
        assertThat(auditCount(source, "GRANT_REVOKED")).isEqualTo(1);
    }

    @Test
    void readReceiptCurrentPurposeDoesNotRewriteOriginalIntentOrStoreStatus() throws Exception {
        DataSource source = fresh();
        WorkflowFixture f = workflow(source, true);
        Intent intent = f.readIntent(UUID.randomUUID());
        success(f.service().registerUserSecurityRead(intent));
        success(f.service().readUserSecurity(intent));
        status(source, f.target(), UserStatus.SUSPENDED);
        Receipt receipt = success(f.service().readUserSecurityReceipt(intent, ReadReason.SECURITY_REVIEW));
        assertThat(receipt.intent().readReason()).isEqualTo(ReadReason.USER_REQUESTED_SUPPORT);
        try (Connection c = source.getConnection()) {
            assertThat(scalar(c, "SELECT reason FROM platform_access_audit_events "
                    + "WHERE projection='OPERATION_RECEIPT'", String.class)).isEqualTo("SECURITY_REVIEW");
            String outcome = scalar(c, "SELECT to_jsonb(o)::text FROM platform_access_operation_outcomes o", String.class);
            assertThat(outcome).doesNotContain("SUSPENDED", "ACTIVE", "accountStatus", "account_status");
        }
    }

    @ParameterizedTest(name = "Production timestamp exact finite boundary: {0}")
    @MethodSource("acceptedProductionAuditTimes")
    void productionAuditTimestampRoundTripsWithoutCoercion(
            String label, Instant expected, String independentSqlLiteral) throws Exception {
        DataSource source = database(POSTGRES);
        flyway(source, "12").migrate();
        UUID eventId = UUID.randomUUID();
        var owner = new OwnedPlatformAccessTransactions(source);
        var repository = new JdbcPlatformAccessRepository();

        var phase = owner.primary(Operation.PLATFORM_ATTEMPT_AUDIT, (connection, deadline) -> {
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            repository.session(connection, deadline).writeAudit(timestampAudit(eventId, expected));
            // A different physical observer must not see the real write before owned commit.
            try (Connection observer = source.getConnection()) {
                assertThat(scalar(observer, "SELECT pg_backend_pid()", Integer.class))
                        .isNotEqualTo(scalar(connection, "SELECT pg_backend_pid()", Integer.class));
                assertThat(scalar(observer, "SELECT count(*) FROM platform_access_audit_events WHERE id=?",
                        Long.class, eventId)).isZero();
            }
            return Boolean.TRUE;
        });

        assertThat(phase.succeeded()).as(label).isTrue();
        assertThat(phase.completion()).isEqualTo(Completion.COMMITTED);
        assertThat(phase.failure()).isNull();
        assertThat(phase.cleanupComplete()).isTrue();
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_audit_events WHERE id=?",
                    Long.class, eventId)).isOne();
            assertThat(scalar(observer, "SELECT isfinite(event_at) FROM platform_access_audit_events WHERE id=?",
                    Boolean.class, eventId)).isTrue();
            assertThat(scalar(observer,
                    "SELECT event_at = ?::timestamptz FROM platform_access_audit_events WHERE id=?",
                    Boolean.class, independentSqlLiteral, eventId)).isTrue();
            assertThat(scalar(observer, "SELECT event_at FROM platform_access_audit_events WHERE id=?",
                    OffsetDateTime.class, eventId).toInstant()).isEqualTo(expected);
        }
    }

    static Stream<Arguments> acceptedProductionAuditTimes() {
        return Stream.of(
                Arguments.of("minimum finite PostgreSQL time",
                        Instant.parse("-4713-11-24T00:00:00Z"), "4714-11-24 00:00:00+00 BC"),
                Arguments.of("one microsecond above minimum",
                        Instant.parse("-4713-11-24T00:00:00.000001Z"), "4714-11-24 00:00:00.000001+00 BC"),
                Arguments.of("astronomical year zero is 1 BC",
                        Instant.parse("0000-01-01T00:00:00.123456Z"), "0001-01-01 00:00:00.123456+00 BC"),
                Arguments.of("first AD year",
                        Instant.parse("0001-01-01T00:00:00Z"), "0001-01-01 00:00:00+00"),
                Arguments.of("ordinary exact fractional microseconds",
                        Instant.parse("2026-10-05T12:34:56.123456Z"), "2026-10-05 12:34:56.123456+00"),
                Arguments.of("last finite PostgreSQL microsecond",
                        Instant.parse("+294276-12-31T23:59:59.999999Z"), "294276-12-31 23:59:59.999999+00"));
    }

    @ParameterizedTest(name = "Production timestamp rejected before coercion: {0}")
    @MethodSource("rejectedProductionAuditTimes")
    void productionAuditTimestampRejectsInvalidInputsWithoutAnyStoredRow(
            String label, Instant invalid) throws Exception {
        DataSource source = database(POSTGRES);
        flyway(source, "12").migrate();
        UUID eventId = UUID.randomUUID();
        var owner = new OwnedPlatformAccessTransactions(source);
        var repository = new JdbcPlatformAccessRepository();

        var phase = owner.primary(Operation.PLATFORM_ATTEMPT_AUDIT, (connection, deadline) -> {
            repository.session(connection, deadline).writeAudit(timestampAudit(eventId, invalid));
            return Boolean.TRUE;
        });

        assertThat(phase.succeeded()).as(label).isFalse();
        assertThat(phase.completion()).isEqualTo(Completion.ROLLED_BACK);
        assertThat(phase.cleanupComplete()).isTrue();
        // The exact production validation failure is required, not any DB failure.
        assertThat(phase.failure()).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Persisted timestamp requires finite exact microseconds.");
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_audit_events WHERE id=?",
                    Long.class, eventId)).isZero();
        }
    }

    static Stream<Arguments> rejectedProductionAuditTimes() {
        return Stream.of(
                Arguments.of("missing required timestamp", (Instant) null),
                Arguments.of("one nanosecond", Instant.parse("2026-10-05T12:34:56.000000001Z")),
                Arguments.of("submicrosecond fraction must not round",
                        Instant.parse("2026-10-05T12:34:56.123456700Z")),
                Arguments.of("last valid microsecond plus one nanosecond must not round",
                        Instant.parse("+294276-12-31T23:59:59.999999001Z")),
                Arguments.of("one microsecond below minimum",
                        Instant.parse("-4713-11-23T23:59:59.999999Z")),
                Arguments.of("exclusive upper endpoint", Instant.parse("+294277-01-01T00:00:00Z")),
                Arguments.of("Java minimum outside PG", Instant.MIN),
                Arguments.of("Java maximum outside PG", Instant.MAX));
    }

    private static AuditEvent timestampAudit(UUID id, Instant at) {
        return new AuditEvent(
                id, AuditKind.ACCESS_DENIED, at, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, // no verified actor or grant
                UUID.randomUUID(), null, TargetVerification.SUPPLIED,
                null, null, null, null, // no verified target or operation identity
                Kind.USER_SECURITY_READ, PlatformPermission.USER_SECURITY_READ,
                null, AuditReason.INVALID_INPUT, AuditOutcome.DENIED,
                null, null, null, null, null);
    }

    private static Result<?> callRevokeEntry(PlatformAccessService service, RevokeEntry entry, Intent intent) {
        return switch (entry) {
            case REGISTER -> service.registerSupportRevoke(intent);
            case EXECUTE -> service.revokeSupportGrant(intent);
            case RECEIPT -> service.readRevokeReceipt(intent);
        };
    }

    private static Map<String, List<String>> discoveryBusinessState(DataSource source) throws Exception {
        try (Connection observer = source.getConnection()) {
            return rows(observer, List.of("users", "platform_access_grants", "platform_access_grant_targets",
                    "platform_access_operation_intents", "platform_access_operation_outcomes"), false);
        }
    }

    private static long suppliedOnlyDenialCount(DataSource source, Intent supplied, RevokeEntry entry)
            throws Exception {
        try (Connection observer = source.getConnection()) {
            return scalar(observer, """
                    SELECT count(*) FROM platform_access_audit_events
                    WHERE event_kind='ACCESS_DENIED' AND outcome='DENIED'
                      AND action='SUPPORT_REVOKE' AND permission=?
                      AND supplied_target_user_id=? AND supplied_target_grant_id=?
                      AND target_verification='SUPPLIED' AND reason IS NOT NULL
                      AND target_user_id IS NULL AND target_grant_id IS NULL
                      AND initiator_user_id IS NULL AND operation_id IS NULL
                      AND projection IS NULL AND linked_event_id IS NULL
                      AND before_state IS NULL AND before_revision IS NULL
                      AND after_state IS NULL AND after_revision IS NULL
                    """, Long.class,
                    entry == RevokeEntry.RECEIPT ? "STAFF_GRANTS_READ" : "STAFF_GRANT_SUSPEND_REVOKE",
                    supplied.targetUserId(), supplied.targetGrantId());
        }
    }


    private static void status(DataSource source, UUID id, UserStatus status) throws Exception {
        try (Connection c = source.getConnection()) {
            if (!scalar(c, "SELECT status FROM users WHERE id=?", String.class, id).equals(status.name())) {
                assertThat(execute(c, "UPDATE users SET status=? WHERE id=?", status.name(), id)).isEqualTo(1);
            }
        }
    }

    private static void moveGrant(DataSource source, UUID grant, UUID actor, State next) throws Exception {
        tx(source, c -> {
            Instant at = scalar(c, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            String revoke = next == State.REVOKED ? ",revoked_by_user_id=?,revoked_at=?" : "";
            if (next == State.REVOKED) {
                assertThat(execute(c, "UPDATE platform_access_grants SET state=?,revision=revision+1,"
                        + "last_changed_by_user_id=?,last_changed_at=?" + revoke + " WHERE id=?",
                        next.name(), actor, at, actor, at, grant)).isEqualTo(1);
            } else {
                assertThat(execute(c, "UPDATE platform_access_grants SET state=?,revision=revision+1,"
                        + "last_changed_by_user_id=?,last_changed_at=? WHERE id=?", next.name(), actor, at, grant)).isEqualTo(1);
            }
            return null;
        });
    }

    private static UUID addGrant(DataSource source, UUID recipient, UUID issuer, boolean owner,
                                 List<UUID> targets, boolean expired) throws Exception {
        return tx(source, c -> {
            UUID id = UUID.randomUUID();
            Instant now = scalar(c, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            Instant start = now.minusSeconds(expired ? 3600 : 60);
            execute(c, """
                    INSERT INTO platform_access_grants(id,recipient_user_id,role,catalog_version,bundle_version,
                        starts_at,validity_kind,expires_at,scope_kind,scope_target_count,issued_by_user_id,issued_at)
                    VALUES (?,?,?,1,1,?,?,?,?,?,?,?)
                    """, id, recipient, owner ? "PLATFORM_OWNER" : "SUPPORT_READ", start,
                    owner ? "UNBOUNDED" : "BOUNDED", owner ? null : now.plusSeconds(expired ? -60 : 3600),
                    owner ? "PLATFORM_SECURITY_METADATA" : "EXACT_USERS", targets.size(), issuer, start);
            for (UUID target : targets) { execute(c, "INSERT INTO platform_access_grant_targets VALUES (?,?)", id, target); }
            return id;
        });
    }

    private static long count(DataSource source, String table) throws Exception {
        try (Connection c = source.getConnection()) { return scalar(c, "SELECT count(*) FROM " + table, Long.class); }
    }

    private static long auditCount(DataSource source, String kind) throws Exception {
        try (Connection c = source.getConnection()) {
            return scalar(c, "SELECT count(*) FROM platform_access_audit_events WHERE event_kind=?", Long.class, kind);
        }
    }

    private static <T> T success(Result<T> result) {
        assertThat(result).isInstanceOf(Success.class);
        return ((Success<T>) result).payload();
    }

    private static void failure(Result<?> result, Code code) {
        assertThat(result).isEqualTo(new Failure<>(code));
        assertThat(((Failure<?>) result).message()).isEqualTo(code.message());
        assertThat(Failure.class.getRecordComponents()).hasSize(1);
    }

    /** Observes prepared history reads without changing SQL, values, results or transaction outcome. */
    private static final class HistoryObserver extends AbstractDataSource {
        private final DataSource source;
        private final AtomicInteger lookups = new AtomicInteger();
        private HistoryObserver(DataSource source) { this.source = source; }
        @Override public Connection getConnection() throws java.sql.SQLException {
            Connection real = source.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && sql.startsWith("SELECT") && (sql.contains("platform_access_operation_intents")
                                    || sql.contains("platform_access_operation_outcomes"))) { lookups.incrementAndGet(); }
                        try { return method.invoke(real, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        @Override public Connection getConnection(String user, String password) throws java.sql.SQLException {
            return getConnection();
        }
    }
}
