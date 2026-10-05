package com.creastrix.platform.platformaccess;

import com.creastrix.platform.platformaccess.PlatformAccessTestSupport.WorkflowFixture;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Code;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Denied;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Failure;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Intent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Result;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Success;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.UserSecurity;
import com.creastrix.platform.platformaccess.application.PlatformAccessService;
import com.creastrix.platform.platformaccess.application.TrustedPlatformAccessFacts.Snapshot;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Assurance;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.ReadReason;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.StepUp;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Verification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.creastrix.platform.platformaccess.PlatformAccessTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL contention through the unwired service; every raw writer is synthetic setup. */
@Testcontainers
@Timeout(40)
class PlatformAccessConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static DataSource source;

    @BeforeAll
    static void migrate() throws SQLException {
        source = database(POSTGRES);
        flyway(source, "latest").migrate();
    }

    enum Operation { READ, REVOKE }
    enum Conflict {
        READ_STATUS(Operation.READ, Writer.STATUS),
        REVOKE_STATUS(Operation.REVOKE, Writer.STATUS),
        READ_ACTOR_GRANT(Operation.READ, Writer.ACTOR_GRANT),
        REVOKE_ACTOR_GRANT(Operation.REVOKE, Writer.ACTOR_GRANT),
        REVOKE_TARGET_GRANT(Operation.REVOKE, Writer.TARGET_GRANT);
        final Operation operation;
        final Writer writer;
        Conflict(Operation operation, Writer writer) { this.operation = operation; this.writer = writer; }
    }
    enum Writer { STATUS, ACTOR_GRANT, TARGET_GRANT }
    enum ExpiryWait {
        USER_GRANT_EXPIRY, GRANT_MFA_EXPIRY, INTENT_IDLE_EXPIRY,
        UNIQUE_STEP_UP_EXPIRY, UNIQUE_STEP_UP_EXPIRY_BLOCKER_ROLLBACK
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void exactConcurrentExecutionCommitsOnlyOneOutcome(Operation operation) throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent intent = intent(fixture, operation, UUID.randomUUID());
        register(fixture.service(), operation, intent);
        Probe first = new Probe(source, sql -> sql.startsWith("INSERT INTO platform_access_operation_outcomes"), false);
        Probe second = new Probe(source);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Result<?>> admitted = workers.submit(() -> executeOperation(fixture.service(first), operation, intent));
            first.awaitPause();
            Future<Result<?>> repeated = workers.submit(() -> executeOperation(fixture.service(second), operation, intent));
            int waitingPid = second.nextPid();
            awaitBlocked(source, waitingPid, first.pausedPid.get());
            assertUncommitted(first, intent);
            first.release();
            assertThat(admitted.get(8, TimeUnit.SECONDS)).isInstanceOf(Success.class);
            Result<?> loser = repeated.get(8, TimeUnit.SECONDS);
            if (operation == Operation.READ) {
                assertThat(loser).isEqualTo(new Failure<>(Code.TERMINAL_RECEIPT_REQUIRED));
            } else {
                assertThat(loser).isInstanceOf(Denied.class);
            }
            assertSuccessCounts(intent, operation);
        } finally {
            first.release();
            finish(workers);
        }
    }

    @Test
    void differentRegisteredIdsWithSameExpectedRevisionCannotBothRevoke() throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent firstIntent = fixture.revokeIntent(UUID.randomUUID());
        Intent secondIntent = fixture.revokeIntent(UUID.randomUUID());
        register(fixture.service(), Operation.REVOKE, firstIntent);
        register(fixture.service(), Operation.REVOKE, secondIntent);
        Probe first = new Probe(source, sql -> sql.startsWith("INSERT INTO platform_access_operation_outcomes"), false);
        Probe second = new Probe(source);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Result<?>> winning = workers.submit(() -> fixture.service(first).revokeSupportGrant(firstIntent));
            first.awaitPause();
            Future<Result<?>> losing = workers.submit(() -> fixture.service(second).revokeSupportGrant(secondIntent));
            awaitBlocked(source, second.nextPid(), first.pausedPid.get());
            first.release();
            assertThat(winning.get(8, TimeUnit.SECONDS)).isInstanceOf(Success.class);
            assertThat(losing.get(8, TimeUnit.SECONDS)).isInstanceOf(Denied.class);
            assertSuccessCounts(firstIntent, Operation.REVOKE);
            assertThat(outcomes(secondIntent)).isZero();
        } finally {
            first.release();
            finish(workers);
        }
    }

    @ParameterizedTest
    @EnumSource(Conflict.class)
    void committedConflictingWriterPreventsLaterAdmission(Conflict conflict) throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent intent = intent(fixture, conflict.operation, UUID.randomUUID());
        register(fixture.service(), conflict.operation, intent);
        Probe observed = new Probe(source);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection blocker = source.getConnection()) {
            begin(blocker);
            mutate(blocker, fixture, conflict.writer);
            Future<Result<?>> waiting = workers.submit(() -> executeOperation(fixture.service(observed), conflict.operation, intent));
            awaitBlocked(source, observed.nextPid(), pid(blocker));
            blocker.commit();
            Result<?> result = waiting.get(8, TimeUnit.SECONDS);
            if (conflict.writer == Writer.ACTOR_GRANT) {
                // The immutable discovery slot changed while the grant lock was queued.
                assertThat(result).isEqualTo(new Failure<>(Code.DATABASE_UNAVAILABLE));
            } else {
                assertThat(result).isInstanceOf(Denied.class);
            }
            assertThat(outcomes(intent)).isZero();
            assertThat(successEvents(intent)).isZero();
        } finally {
            finish(workers);
        }
    }

    @ParameterizedTest
    @EnumSource(Conflict.class)
    void admittedOperationCommitsBeforeWaitingConflictingWriter(Conflict conflict) throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent intent = intent(fixture, conflict.operation, UUID.randomUUID());
        register(fixture.service(), conflict.operation, intent);
        Probe observed = new Probe(source, sql -> sql.startsWith("INSERT INTO platform_access_operation_outcomes"), false);
        BlockingQueue<Integer> writerPid = new LinkedBlockingQueue<>();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Result<?>> admitted = workers.submit(() -> executeOperation(fixture.service(observed), conflict.operation, intent));
            observed.awaitPause();
            Future<Integer> changed = workers.submit(() -> tx(source, connection -> {
                writerPid.add(pid(connection));
                lockWriterTarget(connection, fixture, conflict.writer);
                return mutate(connection, fixture, conflict.writer);
            }));
            Integer waiter = writerPid.poll(5, TimeUnit.SECONDS);
            assertThat(waiter).isNotNull();
            awaitBlocked(source, waiter, observed.pausedPid.get());
            assertUncommitted(observed, intent);
            observed.release();
            assertThat(admitted.get(8, TimeUnit.SECONDS)).isInstanceOf(Success.class);
            assertThat(changed.get(8, TimeUnit.SECONDS))
                    .isEqualTo(conflict.writer == Writer.TARGET_GRANT ? 0 : 1);
            assertSuccessCounts(intent, conflict.operation);
        } finally {
            observed.release();
            finish(workers);
        }
    }

    @ParameterizedTest
    @EnumSource(ExpiryWait.class)
    void freshDatabaseTimeAfterActualWaitRejectsExpiredAuthority(ExpiryWait scenario) throws Exception {
        WorkflowFixture fixture = scenario == ExpiryWait.USER_GRANT_EXPIRY
                ? shortOwnerFixture() : workflow(source, true);
        boolean uniqueWait = scenario == ExpiryWait.UNIQUE_STEP_UP_EXPIRY
                || scenario == ExpiryWait.UNIQUE_STEP_UP_EXPIRY_BLOCKER_ROLLBACK;
        Operation operation = uniqueWait ? Operation.REVOKE : Operation.READ;
        Intent intent = intent(fixture, operation, UUID.randomUUID());
        if (!uniqueWait) { register(fixture.service(), operation, intent); }
        Instant expires = scenario == ExpiryWait.USER_GRANT_EXPIRY
                ? expiry(fixture.actorGrant()) : clock().plusMillis(1400);
        if (scenario != ExpiryWait.USER_GRANT_EXPIRY) { expiringFacts(fixture, scenario, expires); }
        Instant initialActivity = fixture.facts().get().assurance().lastActivityAt();
        Probe observed = new Probe(source);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection blocker = source.getConnection()) {
            begin(blocker);
            switch (scenario) {
                case USER_GRANT_EXPIRY -> scalar(blocker, "SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", UUID.class, fixture.actor());
                case GRANT_MFA_EXPIRY -> scalar(blocker, "SELECT id FROM platform_access_grants WHERE id=? FOR NO KEY UPDATE", UUID.class, fixture.actorGrant());
                case INTENT_IDLE_EXPIRY -> lockIntent(blocker, intent);
                case UNIQUE_STEP_UP_EXPIRY, UNIQUE_STEP_UP_EXPIRY_BLOCKER_ROLLBACK -> insertSyntheticIntent(blocker, fixture, intent);
            }
            Future<Result<?>> waiting = workers.submit(() -> uniqueWait
                    ? fixture.service(observed).registerSupportRevoke(intent)
                    : executeOperation(fixture.service(observed), operation, intent));
            awaitBlocked(source, observed.nextPid(), pid(blocker));
            if (scenario == ExpiryWait.INTENT_IDLE_EXPIRY || uniqueWait) {
                assertThat(observed.clockQueries.get()).as("prelookup admission used an actual DB clock").isPositive();
            }
            awaitDatabaseTime(expires);
            if (scenario == ExpiryWait.UNIQUE_STEP_UP_EXPIRY_BLOCKER_ROLLBACK) { blocker.rollback(); }
            else { blocker.commit(); }
            assertThat(waiting.get(8, TimeUnit.SECONDS)).isInstanceOf(Denied.class);
            assertThat(observed.lastClock.get()).isAfterOrEqualTo(expires);
            if (scenario == ExpiryWait.INTENT_IDLE_EXPIRY || uniqueWait) {
                assertThat(observed.clockQueries.get()).as("another real primary clock read follows the wait")
                        .isGreaterThanOrEqualTo(2);
            }
            assertThat(outcomes(intent)).isZero();
            assertThat(successEvents(intent)).isZero();
            assertThat(fixture.facts().get().assurance().lastActivityAt())
                    .isEqualTo(initialActivity);
            if (uniqueWait) {
                boolean provisionalInsert = scenario == ExpiryWait.UNIQUE_STEP_UP_EXPIRY_BLOCKER_ROLLBACK;
                assertThat(observed.insertAffectedRows.get()).isEqualTo(provisionalInsert ? 1 : 0);
                assertThat(observed.insertTransaction.get()).isNotBlank();
                try (Connection observer = source.getConnection()) {
                    assertThat(scalar(observer, "SELECT count(*) FROM platform_access_operation_intents WHERE initiator_user_id=? AND operation_id=?",
                            Long.class, intent.initiatorUserId(), intent.operationId())).isEqualTo(provisionalInsert ? 0L : 1L);
                    assertThat(scalar(observer, "SELECT count(*) FROM platform_access_audit_events WHERE initiator_user_id=? AND operation_id=? AND event_kind='INTENT_RECORDED'",
                            Long.class, intent.initiatorUserId(), intent.operationId())).isEqualTo(provisionalInsert ? 0L : 1L);
                }
                System.out.printf("PLATFORM_ACCESS_UNIQUE_WAIT pid=%d transaction=%s affected_rows=%d blocker_rollback=%s retained_intents=%d%n",
                        observed.primaryPid.get(), observed.insertTransaction.get(), observed.insertAffectedRows.get(),
                        provisionalInsert, provisionalInsert ? 0 : 1);
            }
        } finally {
            finish(workers);
        }
    }

    @Test
    void lockedReceiptTimesOutTechnicallyInsteadOfInventingMissingHistory() throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), Operation.READ, intent);
        assertThat(fixture.service().readUserSecurity(intent)).isInstanceOf(Success.class);
        Probe observed = new Probe(source);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection blocker = source.getConnection()) {
            begin(blocker);
            lockIntent(blocker, intent);
            Future<Result<?>> waiting = workers.submit(() -> fixture.service(observed)
                    .readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT));
            awaitBlocked(source, observed.nextPid(), pid(blocker));
            Result<?> result = waiting.get(8, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new Failure<>(Code.DATABASE_UNAVAILABLE));
            assertThat(result).isNotEqualTo(new Failure<>(Code.NO_DISCLOSABLE_RECEIPT));
            blocker.rollback();
            assertThat(fixture.service().readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT))
                    .isInstanceOf(Success.class);
            assertThat(outcomes(intent)).isEqualTo(1);
        } finally {
            finish(workers);
        }
    }

    @Test
    void invisibleRegistrationAndCommittedPendingIntentNeverImplyTerminalRollback() throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent intent = fixture.revokeIntent(UUID.randomUUID());
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection original = source.getConnection(); Connection observer = source.getConnection()) {
            begin(original);
            // Privileged fixture records real SQL rows, not an authority/result mock.
            // It deliberately stops before commit while independently authorized receipts run.
            insertSyntheticIntent(original, fixture, intent);
            int originalPid = pid(original);
            String originalTransaction = scalar(original, "SELECT pg_current_xact_id()::text", String.class);
            assertThat(scalar(original, "SELECT count(*) FROM platform_access_operation_intents WHERE initiator_user_id=? AND operation_id=?",
                    Long.class, intent.initiatorUserId(), intent.operationId())).isOne();
            assertThat(scalar(original, "SELECT count(*) FROM platform_access_audit_events WHERE initiator_user_id=? AND operation_id=? AND event_kind='INTENT_RECORDED'",
                    Long.class, intent.initiatorUserId(), intent.operationId())).isOne();
            assertThat(scalar(observer, "SELECT pg_current_xact_id()::text", String.class)).isNotEqualTo(originalTransaction);
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_operation_intents WHERE initiator_user_id=? AND operation_id=?",
                    Long.class, intent.initiatorUserId(), intent.operationId())).isZero();
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_audit_events WHERE initiator_user_id=? AND operation_id=? AND event_kind='INTENT_RECORDED'",
                    Long.class, intent.initiatorUserId(), intent.operationId())).isZero();
            Future<Result<?>> invisibleReceipt = workers.submit(() -> fixture.service().readRevokeReceipt(intent));
            assertThat(invisibleReceipt.get(8, TimeUnit.SECONDS))
                    .isEqualTo(new Failure<>(Code.NO_DISCLOSABLE_RECEIPT));
            assertThat(scalar(observer, "SELECT backend_xid::text FROM pg_stat_activity WHERE pid=?", String.class, originalPid))
                    .isEqualTo(originalTransaction);
            assertThat(outcomes(intent)).isZero();
            System.out.printf("PLATFORM_ACCESS_INVISIBLE_REGISTRATION pid=%d transaction=%s visible_intents=0 visible_registration_audits=0 receipt=NO_DISCLOSABLE_RECEIPT%n",
                    originalPid, originalTransaction);

            original.commit();
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_operation_intents WHERE initiator_user_id=? AND operation_id=?",
                    Long.class, intent.initiatorUserId(), intent.operationId())).isOne();
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_audit_events WHERE initiator_user_id=? AND operation_id=? AND event_kind='INTENT_RECORDED'",
                    Long.class, intent.initiatorUserId(), intent.operationId())).isOne();
            Future<Result<?>> pendingReceipt = workers.submit(() -> fixture.service().readRevokeReceipt(intent));
            assertThat(pendingReceipt.get(8, TimeUnit.SECONDS))
                    .isEqualTo(new Failure<>(Code.NO_DISCLOSABLE_RECEIPT));
            assertThat(outcomes(intent)).isZero();

            // No fresh identity or automatic retry: the caller explicitly executes the exact intent.
            assertThat(fixture.service().revokeSupportGrant(intent)).isInstanceOf(Success.class);
            assertSuccessCounts(intent, Operation.REVOKE);
            assertThat(fixture.service().readRevokeReceipt(intent)).isInstanceOf(Success.class);
        } finally {
            finish(workers);
        }
    }

    @Test
    void receiptWithoutOutcomeDoesNotProveRollbackWhileOriginalHasNotReachedItsLocks() throws Exception {
        WorkflowFixture fixture = workflow(source, true);
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), Operation.READ, intent);
        Probe paused = new Probe(source, sql -> sql.startsWith("SELECT id FROM platform_access_grants WHERE recipient_user_id"), true);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Result<UserSecurity>> original = workers.submit(() -> fixture.service(paused).readUserSecurity(intent));
            paused.awaitPause();
            assertThat(original.isDone()).isFalse();
            assertThat(fixture.service().readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT))
                    .isEqualTo(new Failure<>(Code.NO_DISCLOSABLE_RECEIPT));
            assertThat(outcomes(intent)).isZero();
            paused.release();
            assertThat(original.get(8, TimeUnit.SECONDS)).isInstanceOf(Success.class);
            assertThat(fixture.service().readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT))
                    .isInstanceOf(Success.class);
            assertThat(outcomes(intent)).isEqualTo(1);
        } finally {
            paused.release();
            finish(workers);
        }
    }

    private static Intent intent(WorkflowFixture fixture, Operation operation, UUID id) {
        return operation == Operation.READ ? fixture.readIntent(id) : fixture.revokeIntent(id);
    }

    private static void register(PlatformAccessService service, Operation operation, Intent intent) {
        assertThat(operation == Operation.READ ? service.registerUserSecurityRead(intent) : service.registerSupportRevoke(intent))
                .isInstanceOf(Success.class);
    }

    private static Result<?> executeOperation(PlatformAccessService service, Operation operation, Intent intent) {
        return operation == Operation.READ ? service.readUserSecurity(intent) : service.revokeSupportGrant(intent);
    }

    private static void begin(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
    }

    private static void lockIntent(Connection connection, Intent intent) throws SQLException {
        scalar(connection, "SELECT operation_id FROM platform_access_operation_intents WHERE initiator_user_id=? AND operation_id=? FOR NO KEY UPDATE",
                UUID.class, intent.initiatorUserId(), intent.operationId());
    }

    private static void lockWriterTarget(Connection connection, WorkflowFixture fixture, Writer writer) throws SQLException {
        if (writer == Writer.STATUS) {
            scalar(connection, "SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", UUID.class, fixture.actor());
        } else {
            scalar(connection, "SELECT id FROM platform_access_grants WHERE id=? FOR NO KEY UPDATE", UUID.class,
                    writer == Writer.ACTOR_GRANT ? fixture.actorGrant() : fixture.targetGrant());
        }
    }

    private static int mutate(Connection connection, WorkflowFixture fixture, Writer writer) throws SQLException {
        if (writer == Writer.STATUS) { return execute(connection, "UPDATE users SET status='SUSPENDED' WHERE id=?", fixture.actor()); }
        Instant at = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        return execute(connection, """
                UPDATE platform_access_grants SET state='REVOKED',revision=revision+1,
                    last_changed_by_user_id=?,last_changed_at=?,revoked_by_user_id=?,revoked_at=?
                WHERE id=? AND state IN ('ACTIVE','SUSPENDED')
                """, fixture.actor(), at, fixture.actor(), at,
                writer == Writer.ACTOR_GRANT ? fixture.actorGrant() : fixture.targetGrant());
    }

    private static void assertUncommitted(Probe probe, Intent intent) throws SQLException {
        assertThat(probe.physicalTransaction.get()).isNotBlank();
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT pg_current_xact_id()::text", String.class))
                    .isNotEqualTo(probe.physicalTransaction.get());
            assertThat(scalar(observer, "SELECT backend_xid IS NOT NULL FROM pg_stat_activity WHERE pid=?",
                    Boolean.class, probe.pausedPid.get())).isTrue();
            assertThat(outcomes(intent)).isZero();
        }
        System.out.printf("PLATFORM_ACCESS_PHYSICAL_PENDING pid=%d transaction=%s visible_outcomes=0%n",
                probe.pausedPid.get(), probe.physicalTransaction.get());
    }

    private static long outcomes(Intent intent) throws SQLException {
        try (Connection connection = source.getConnection()) {
            return scalar(connection, "SELECT count(*) FROM platform_access_operation_outcomes WHERE initiator_user_id=? AND operation_id=?",
                    Long.class, intent.initiatorUserId(), intent.operationId());
        }
    }

    private static long successEvents(Intent intent) throws SQLException {
        try (Connection connection = source.getConnection()) {
            return scalar(connection, "SELECT count(*) FROM platform_access_audit_events WHERE initiator_user_id=? AND operation_id=? AND event_kind IN ('READ_ADMITTED','GRANT_REVOKED')",
                    Long.class, intent.initiatorUserId(), intent.operationId());
        }
    }

    private static void assertSuccessCounts(Intent intent, Operation operation) throws SQLException {
        assertThat(outcomes(intent)).isEqualTo(1);
        assertThat(successEvents(intent)).isEqualTo(1);
        if (operation == Operation.REVOKE) {
            try (Connection connection = source.getConnection()) {
                assertThat(scalar(connection, "SELECT revision FROM platform_access_grants WHERE id=?", Long.class, intent.targetGrantId())).isEqualTo(2);
                assertThat(scalar(connection, "SELECT state FROM platform_access_grants WHERE id=?", String.class, intent.targetGrantId())).isEqualTo("REVOKED");
            }
        }
    }

    private static Instant clock() throws SQLException {
        try (Connection connection = source.getConnection()) {
            return scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        }
    }

    private static Instant expiry(UUID grant) throws SQLException {
        try (Connection connection = source.getConnection()) {
            return scalar(connection, "SELECT expires_at FROM platform_access_grants WHERE id=?", OffsetDateTime.class, grant).toInstant();
        }
    }

    private static void awaitDatabaseTime(Instant threshold) throws SQLException {
        try (Connection observer = source.getConnection()) {
            await(() -> {
                try { return !scalar(observer, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant().isBefore(threshold); }
                catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
        }
    }

    private static void expiringFacts(WorkflowFixture fixture, ExpiryWait scenario, Instant expires) throws SQLException {
        Instant now = clock();
        Snapshot prior = fixture.facts().get();
        Instant mfa = scenario == ExpiryWait.GRANT_MFA_EXPIRY ? expires.minusSeconds(600) : now.minusSeconds(400);
        Instant activity = scenario == ExpiryWait.INTENT_IDLE_EXPIRY ? expires.minusSeconds(300) : now;
        Instant bound = mfa;
        Instant step = scenario == ExpiryWait.UNIQUE_STEP_UP_EXPIRY
                || scenario == ExpiryWait.UNIQUE_STEP_UP_EXPIRY_BLOCKER_ROLLBACK ? expires.minusSeconds(300) : now;
        Assurance assurance = new Assurance(Verification.VERIFIED_MFA, prior.assurance().stamp(), mfa, bound, activity,
                new StepUp(Verification.VERIFIED_STEP_UP, prior.assurance().stamp(), step));
        fixture.facts().set(new Snapshot(prior.userId(), prior.sessionGeneration(), assurance));
    }

    private static WorkflowFixture shortOwnerFixture() throws Exception {
        WorkflowFixture fixture = tx(source, connection -> {
            UUID actor = user(connection);
            UUID target = user(connection);
            Instant now = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            Instant issued = now.minusSeconds(60);
            UUID grant = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO platform_access_grants(id,recipient_user_id,role,catalog_version,bundle_version,
                        starts_at,validity_kind,expires_at,scope_kind,scope_target_count,state,revision,issued_by_user_id,issued_at)
                    VALUES (?,?,'PLATFORM_OWNER',1,1,?,'BOUNDED',?,'PLATFORM_SECURITY_METADATA',0,'ACTIVE',1,?,?)
                    """, grant, actor, issued, now.plusMillis(1800), actor, issued);
            return new WorkflowFixture(source, actor, target, grant, null, issued, new AtomicReference<>());
        });
        fixture.refreshFacts();
        return fixture;
    }

    private static void insertSyntheticIntent(Connection connection, WorkflowFixture fixture, Intent intent) throws SQLException {
        // This raw fixture creates a real uncommitted PK conflict, not an admitted service result.
        UUID correlation = UUID.randomUUID();
        Instant registeredAt = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        execute(connection, """
                INSERT INTO platform_access_operation_intents(initiator_user_id,operation_id,intent_version,kind,
                    target_user_id,target_grant_id,read_reason,expected_revision,registered_at,correlation_id,actor_grant_id,actor_grant_revision)
                VALUES (?,?,1,'SUPPORT_REVOKE',?,?,NULL,1,?,?,?,1)
                """, intent.initiatorUserId(), intent.operationId(), intent.targetUserId(), intent.targetGrantId(),
                registeredAt, correlation, fixture.actorGrant());
        execute(connection, """
                INSERT INTO platform_access_audit_events(id,event_version,event_kind,event_at,correlation_id,attempt_id,
                    actor_user_id,actor_grant_id,actor_grant_revision,actor_role,catalog_version,bundle_version,
                    supplied_target_user_id,supplied_target_grant_id,target_verification,target_user_id,target_grant_id,
                    initiator_user_id,operation_id,action,permission,projection,reason,outcome)
                VALUES (?,1,'INTENT_RECORDED',?,?,?, ?,?,1,'PLATFORM_OWNER',1,1,
                    ?,?,'VERIFIED',?,?, ?,?,'SUPPORT_REVOKE','STAFF_GRANT_SUSPEND_REVOKE',NULL,NULL,'REGISTERED')
                """, UUID.randomUUID(), registeredAt, correlation, UUID.randomUUID(), fixture.actor(), fixture.actorGrant(),
                intent.targetUserId(), intent.targetGrantId(), intent.targetUserId(), intent.targetGrantId(),
                intent.initiatorUserId(), intent.operationId());
    }

    private static void finish(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).as("owned test workers ended").isTrue();
    }

    /** Pauses around actual JDBC execution without changing SQL, rows, clock, or commit. */
    private static final class Probe extends AbstractDataSource {
        private final DataSource delegate;
        private final Predicate<String> pauseSql;
        private final boolean before;
        private final AtomicBoolean paused = new AtomicBoolean();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final BlockingQueue<Integer> pids = new LinkedBlockingQueue<>();
        private final AtomicInteger primaryPid = new AtomicInteger();
        private final AtomicInteger pausedPid = new AtomicInteger();
        private final AtomicReference<String> physicalTransaction = new AtomicReference<>();
        private final AtomicInteger clockQueries = new AtomicInteger();
        private final AtomicReference<Instant> lastClock = new AtomicReference<>();
        private final AtomicInteger insertAffectedRows = new AtomicInteger(-1);
        private final AtomicReference<String> insertTransaction = new AtomicReference<>();

        private Probe(DataSource delegate) { this(delegate, sql -> false, false); }
        private Probe(DataSource delegate, Predicate<String> pauseSql, boolean before) {
            this.delegate = delegate;
            this.pauseSql = pauseSql;
            this.before = before;
        }

        @Override public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            int backendPid = pid(real);
            primaryPid.compareAndSet(0, backendPid);
            pids.add(backendPid);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object returned = invoke(method, real, args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && returned instanceof PreparedStatement statement) {
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        boolean executes = statementMethod.getName().startsWith("execute");
                                        if (executes && before) { pause(real, backendPid, sql); }
                                        Object result = invoke(statementMethod, statement, statementArgs);
                                        if (executes && backendPid == primaryPid.get()
                                                && sql.startsWith("INSERT INTO platform_access_operation_intents") && result instanceof Integer affected) {
                                            insertAffectedRows.set(affected);
                                            insertTransaction.set(scalar(real, "SELECT pg_current_xact_id()::text", String.class));
                                            assertThat(real.getAutoCommit()).isFalse();
                                        }
                                        if (executes && backendPid == primaryPid.get()
                                                && sql.equals("SELECT clock_timestamp()") && result instanceof ResultSet rows) {
                                            clockQueries.incrementAndGet();
                                            // Record only the exact clock returned to the production mapper.
                                            // Do not advance, consume, or substitute its row/value.
                                            result = Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                                                    (rowsProxy, rowsMethod, rowsArgs) -> {
                                                        Object value = invoke(rowsMethod, rows, rowsArgs);
                                                        if (rowsMethod.getName().equals("getObject") && value instanceof OffsetDateTime clock) {
                                                            lastClock.set(clock.toInstant());
                                                        }
                                                        return value;
                                                    });
                                        }
                                        if (executes && !before) { pause(real, backendPid, sql); }
                                        return result;
                                    });
                        }
                        return returned;
                    });
        }

        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }

        private void pause(Connection real, int backendPid, String sql) throws Exception {
            if (pauseSql.test(sql) && paused.compareAndSet(false, true)) {
                pausedPid.set(backendPid);
                physicalTransaction.set(scalar(real, "SELECT pg_current_xact_id()::text", String.class));
                assertThat(real.getAutoCommit()).isFalse();
                assertThat(real.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                reached.countDown();
                assertThat(released.await(7, TimeUnit.SECONDS)).as("bounded JDBC observation release").isTrue();
            }
        }

        private int nextPid() throws InterruptedException {
            Integer value = pids.poll(5, TimeUnit.SECONDS);
            assertThat(value).isNotNull();
            return value;
        }

        private void awaitPause() throws InterruptedException { assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue(); }
        private void release() { released.countDown(); }

        private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
        }
    }
}
