package com.creastrix.platform.platformaccess;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creastrix.platform.observability.Diagnostics;
import com.creastrix.platform.observability.TransactionDiagnostics.Operation;
import com.creastrix.platform.platformaccess.application.OwnedPlatformAccessTransactions;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Code;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Denied;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Failure;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Intent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Receipt;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Result;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Success;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.UserSecurity;
import com.creastrix.platform.platformaccess.application.PlatformAccessService;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.ReadReason;
import com.creastrix.platform.platformaccess.persistence.JdbcPlatformAccessRepository;
import com.creastrix.platform.user.domain.UserStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.creastrix.platform.platformaccess.PlatformAccessTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL commit/rollback evidence through the unwired service. Observer
 * proxies delegate SQL and real transaction calls; they never synthesize rows,
 * replace service decisions or manually commit a migration. Every database and
 * pool belongs to this test's newly owned container and synthetic fixtures.
 */
@Testcontainers
@Timeout(120)
class PlatformAccessCommitOutcomeIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String PRIVATE_VALUE = "synthetic-private-" + UUID.randomUUID();

    @ParameterizedTest(name = "Physical atomicity revoke={0}")
    @ValueSource(booleans = {false, true})
    void mutationOrReadAuditAndOutcomeShareOneTransactionAndAreInvisibleUntilCommit(boolean revoke) throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = revoke ? fixture.revokeIntent(UUID.randomUUID()) : fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, revoke);
        AtomicInteger checked = new AtomicInteger();
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void beforeCommit(ObservedSession session) throws Exception {
                assertThat(session.writes).contains(Stage.AUDIT, Stage.OUTCOME);
                assertThat(session.writes.contains(Stage.MUTATION)).isEqualTo(revoke);
                assertThat(session.transactionIds).containsOnly(session.transactionId);
                assertThat(session.autoCommitValues).containsExactly(false);
                assertThat(session.connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                try (Connection observer = fixture.source().getConnection()) {
                    assertThat(pid(observer)).isNotEqualTo(session.pid);
                    assertThat(outcomes(observer, intent)).isZero();
                    assertThat(successAudits(observer, intent)).isZero();
                    assertThat(grantState(observer, fixture)).isEqualTo("ACTIVE:1");
                }
                checked.incrementAndGet();
            }
        });

        Result<?> result = execute(fixture.service(trace), intent, revoke);

        assertThat(result).isInstanceOf(Success.class);
        assertThat(checked.get()).isOne();
        trace.assertClosed(1);
        ObservedSession observed = trace.sessions.getFirst();
        assertThat(observed.commits.get()).isOne();
        assertThat(observed.rollbacks.get()).isZero();
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(outcomes(observer, intent)).isOne();
            assertThat(successAudits(observer, intent)).isOne();
            assertThat(grantState(observer, fixture)).isEqualTo(revoke ? "REVOKED:2" : "ACTIVE:1");
            assertThat(strings(observer, "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        }
        System.out.printf("PLATFORM_ACCESS_ATOMICITY operation=%s pid=%d xid=%s committed=true%n",
                revoke ? "REVOKE" : "READ", observed.pid, observed.transactionId);
    }

    @ParameterizedTest(name = "Rollback revoke={0}, after real {1}")
    @CsvSource({"true,MUTATION", "true,AUDIT", "true,OUTCOME", "true,PRECOMMIT_WORK",
            "false,AUDIT", "false,OUTCOME", "false,PRECOMMIT_WORK"})
    void faultsAfterRealSqlRollBackSuccessRowsAndRecordOnlySeparateConfirmedFailure(boolean revoke, Stage fault)
            throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = revoke ? fixture.revokeIntent(UUID.randomUUID()) : fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, revoke);
        AtomicBoolean injected = new AtomicBoolean();
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterStatement(ObservedSession session, Stage stage) throws Exception {
                if (session.index == 1 && stage == fault && injected.compareAndSet(false, true)) {
                    throw new SQLException(PRIVATE_VALUE, "XX000");
                }
            }
        });

        Result<?> result = execute(fixture.service(trace), intent, revoke);

        assertThat(injected.get()).as("Real selected SQL boundary reached").isTrue();
        assertThat(result).isInstanceOf(Failure.class);
        assertThat(((Failure<?>) result).code()).isIn(Code.DATABASE_UNAVAILABLE, Code.AUDIT_UNAVAILABLE);
        trace.assertClosed(2);
        assertThat(trace.sessions.get(0).commits.get()).isZero();
        assertThat(trace.sessions.get(0).rollbacks.get()).isOne();
        assertThat(trace.sessions.get(1).commits.get()).isOne();
        assertThat(trace.sessions.get(1).transactionId).isNotEqualTo(trace.sessions.get(0).transactionId);
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(outcomes(observer, intent)).isZero();
            assertThat(successAudits(observer, intent)).isZero();
            assertThat(grantState(observer, fixture)).isEqualTo("ACTIVE:1");
            assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isOne();
            assertThat(audits(observer, intent, "INTENT_RECORDED")).isOne();
        }
    }

    @ParameterizedTest(name = "Mandatory audit fault: {0}, revoke={1}")
    @CsvSource({"NEW,false", "NEW,true", "DUPLICATE,false", "DUPLICATE,true",
            "RECEIPT,false", "RECEIPT,true"})
    void mandatoryAuditFailureNeverAcknowledgesRegistrationOrDisclosesReceipt(String entry, boolean revoke)
            throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = revoke ? fixture.revokeIntent(UUID.randomUUID()) : fixture.readIntent(UUID.randomUUID());
        if (!entry.equals("NEW")) { register(fixture.service(), intent, revoke); }
        if (entry.equals("RECEIPT")) {
            assertThat(execute(fixture.service(), intent, revoke)).isInstanceOf(Success.class);
        }
        Map<String, List<String>> before;
        List<String> priorAudits;
        try (Connection observer = fixture.source().getConnection()) {
            before = durableRows(observer);
            priorAudits = nonFailureAuditRows(observer);
        }
        AtomicBoolean injected = new AtomicBoolean();
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterStatement(ObservedSession session, Stage stage) throws Exception {
                if (session.index == 1 && stage == Stage.AUDIT && injected.compareAndSet(false, true)) {
                    throw new SQLException(PRIVATE_VALUE, "XX000");
                }
            }
        });
        PlatformAccessService service = fixture.service(trace);

        Result<?> result = entry.equals("RECEIPT") ? receipt(service, intent, revoke)
                : revoke ? service.registerSupportRevoke(intent) : service.registerUserSecurityRead(intent);

        assertFailure(result, Code.AUDIT_UNAVAILABLE);
        assertThat(injected.get()).as("Required audit SQL executed before injected failure").isTrue();
        trace.assertClosed(2);
        assertThat(trace.sessions.getFirst().commits.get()).isZero();
        assertThat(trace.sessions.getFirst().rollbacks.get()).isOne();
        assertThat(trace.sessions.get(1).commits.get()).isOne();
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(durableRows(observer).equals(before)).as("All prior grants, intent and outcome rows retained").isTrue();
            assertThat(nonFailureAuditRows(observer).equals(priorAudits))
                    .as("Failed mandatory audit and provisional registration were rolled back").isTrue();
            assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isOne();
        }
    }

    @ParameterizedTest(name = "Receipt audit real commit then lost acknowledgement, revoke={0}")
    @ValueSource(booleans = {false, true})
    void receiptLostCommitAcknowledgementReturnsNoHistoryDespiteCommittedFreshReceiptAudit(boolean revoke)
            throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = revoke ? fixture.revokeIntent(UUID.randomUUID()) : fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, revoke);
        assertThat(execute(fixture.service(), intent, revoke)).isInstanceOf(Success.class);
        Map<String, List<String>> before;
        int earlierReads;
        try (Connection observer = fixture.source().getConnection()) {
            before = durableRows(observer);
            earlierReads = audits(observer, intent, "READ_ADMITTED");
        }
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterCommit(ObservedSession session) throws Exception {
                throw new SQLException(PRIVATE_VALUE, "08006");
            }
        });

        assertFailure(receipt(fixture.service(trace), intent, revoke), Code.COMMIT_UNKNOWN);

        trace.assertClosed(1);
        assertThat(trace.sessions.getFirst().commits.get()).isOne();
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(durableRows(observer).equals(before)).as("Receipt never mutates the retained operation").isTrue();
            assertThat(audits(observer, intent, "READ_ADMITTED")).isEqualTo(earlierReads + 1);
            assertThat(scalar(observer, """
                    SELECT count(*)::integer FROM platform_access_audit_events a
                    JOIN platform_access_operation_outcomes o ON o.audit_event_id=a.linked_event_id
                    WHERE a.initiator_user_id=? AND a.operation_id=? AND a.event_kind='READ_ADMITTED'
                      AND a.projection='OPERATION_RECEIPT'
                    """, Integer.class, intent.initiatorUserId(), intent.operationId())).isOne();
            assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isZero();
        }
    }

    @ParameterizedTest(name = "Real commit then lost acknowledgement: {0}")
    @ValueSource(strings = {"REGISTER", "READ", "REVOKE"})
    void lostCommitAcknowledgementReturnsUnknownAndReconcilesTheSameDurableIdentity(String operation) throws Exception {
        WorkflowFixture fixture = fixture();
        boolean revoke = operation.equals("REVOKE");
        Intent intent = revoke ? fixture.revokeIntent(UUID.randomUUID()) : fixture.readIntent(UUID.randomUUID());
        if (!operation.equals("REGISTER")) { register(fixture.service(), intent, revoke); }
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterCommit(ObservedSession session) throws Exception {
                throw new SQLException(PRIVATE_VALUE, "08006");
            }
        });
        PlatformAccessService observedService = fixture.service(trace);

        Result<?> result = operation.equals("REGISTER") ? observedService.registerUserSecurityRead(intent)
                : execute(observedService, intent, revoke);

        assertFailure(result, Code.COMMIT_UNKNOWN);
        trace.assertClosed(1);
        assertThat(trace.sessions.getFirst().commits.get()).isOne();
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(intents(observer, intent)).isOne();
            assertThat(outcomes(observer, intent)).isEqualTo(operation.equals("REGISTER") ? 0 : 1);
            assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isZero();
            assertThat(grantState(observer, fixture)).isEqualTo(revoke ? "REVOKED:2" : "ACTIVE:1");
        }
        PlatformAccessService healthy = fixture.service();
        Result<?> reconciled = operation.equals("REGISTER") ? healthy.registerUserSecurityRead(intent)
                : revoke ? healthy.readRevokeReceipt(intent)
                : healthy.readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT);
        assertThat(reconciled).isInstanceOf(Success.class);
        if (!operation.equals("REGISTER")) {
            assertThat(((Success<?>) reconciled).payload()).isInstanceOf(Receipt.class);
            assertThat(execute(healthy, intent, revoke)).isNotInstanceOf(Success.class);
        }
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(intents(observer, intent)).isOne();
            assertThat(outcomes(observer, intent)).isEqualTo(operation.equals("REGISTER") ? 0 : 1);
            assertThat(audits(observer, intent, "INTENT_RECORDED")).isOne();
            assertThat(grantState(observer, fixture)).isEqualTo(revoke ? "REVOKED:2" : "ACTIVE:1");
        }
    }

    @Test
    void commitCallFailureBeforePhysicalCommitRemainsUnknownRatherThanInventingRollbackProof() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, false);
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void beforeCommit(ObservedSession session) throws Exception {
                throw new SQLException(PRIVATE_VALUE, "08006");
            }
        });

        assertFailure(fixture.service(trace).readUserSecurity(intent), Code.COMMIT_UNKNOWN);

        trace.assertClosed(1);
        assertThat(trace.sessions.getFirst().commits.get()).isZero();
        assertThat(trace.sessions.getFirst().rollbacks.get()).isOne();
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(outcomes(observer, intent)).isZero();
            assertThat(successAudits(observer, intent)).isZero();
            assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isZero();
        }
        assertFailure(fixture.service().readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT),
                Code.NO_DISCLOSABLE_RECEIPT);
    }

    @ParameterizedTest(name = "A registration history committed={0}; current scope only B")
    @ValueSource(booleans = {false, true})
    void scopeReplacementPreservesBoundedDisclosureForRealLostAckAndRolledBackHistories(boolean committed)
            throws Exception {
        DataSource source = database(POSTGRES);
        flyway(source, "12").migrate();
        WorkflowFixture original = workflow(source, false);
        UUID operation = UUID.randomUUID();
        Intent a = original.readIntent(operation);
        AtomicBoolean injected = new AtomicBoolean();
        Trace trace = new Trace(source, new Plan() {
            @Override void afterStatement(ObservedSession session, Stage stage) throws Exception {
                if (!committed && session.index == 1 && stage == Stage.INTENT
                        && injected.compareAndSet(false, true)) {
                    throw new SQLException(PRIVATE_VALUE, "XX000");
                }
            }
            @Override void afterCommit(ObservedSession session) throws Exception {
                if (committed && session.index == 1 && injected.compareAndSet(false, true)) {
                    throw new SQLException(PRIVATE_VALUE, "08006");
                }
            }
        });

        assertFailure(original.service(trace).registerUserSecurityRead(a),
                committed ? Code.COMMIT_UNKNOWN : Code.DATABASE_UNAVAILABLE);
        assertThat(injected.get()).as("Real INSERT or real commit boundary was reached").isTrue();
        trace.assertClosed(committed ? 1 : 2);
        assertThat(trace.sessions.getFirst().commits.get()).isEqualTo(committed ? 1 : 0);
        try (Connection observer = source.getConnection()) {
            assertThat(intents(observer, a)).isEqualTo(committed ? 1 : 0);
            assertThat(audits(observer, a, "INTENT_RECORDED")).isEqualTo(committed ? 1 : 0);
            assertThat(outcomes(observer, a)).isZero();
        }

        // Privileged synthetic replacement, not a new CHANGE/issuance service.
        // Existing immutable A scope is retained on revoked history; B receives
        // a distinct new grant, and current assurance is explicitly reissued.
        WorkflowFixture replacement = tx(source, connection -> {
            UUID b = user(connection);
            Instant now = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            assertThat(PlatformAccessTestSupport.execute(connection, """
                    UPDATE platform_access_grants SET state='REVOKED',revision=revision+1,
                        last_changed_by_user_id=?,last_changed_at=?,revoked_by_user_id=?,revoked_at=?
                    WHERE id=? AND role='SUPPORT_READ'
                    """, original.actor(), now, original.actor(), now, original.actorGrant())).isOne();
            UUID grant = workflowGrant(connection, original.actor(), original.actor(), false, List.of(b), now);
            return new WorkflowFixture(source, original.actor(), b, grant, original.targetGrant(), now,
                    original.facts());
        });
        replacement.refreshFacts();
        PlatformAccessService current = replacement.service();
        Intent b = replacement.readIntent(operation);
        assertThat(current.registerUserSecurityRead(a)).isInstanceOf(Denied.class);
        assertFailure(current.readUserSecurity(b), Code.NO_EXECUTABLE_INTENT);
        assertFailure(current.readUserSecurityReceipt(b, ReadReason.USER_REQUESTED_SUPPORT), Code.NO_DISCLOSABLE_RECEIPT);

        Result<?> registration = current.registerUserSecurityRead(b);
        if (committed) {
            assertFailure(registration, Code.ID_UNAVAILABLE);
            assertFailure(current.readUserSecurity(b), Code.NO_EXECUTABLE_INTENT);
            assertFailure(current.readUserSecurityReceipt(b, ReadReason.USER_REQUESTED_SUPPORT), Code.NO_DISCLOSABLE_RECEIPT);
        } else {
            assertThat(registration).isInstanceOf(Success.class);
            assertThat(current.readUserSecurity(b))
                    .isEqualTo(new Success<>(new UserSecurity(replacement.target(), UserStatus.ACTIVE)));
            Result<Receipt> receipt = current.readUserSecurityReceipt(b, ReadReason.USER_REQUESTED_SUPPORT);
            assertThat(receipt).isInstanceOf(Success.class);
            assertThat(((Success<Receipt>) receipt).payload().intent()).isEqualTo(b);
        }
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT target_user_id FROM platform_access_operation_intents"
                    + " WHERE initiator_user_id=? AND operation_id=?", UUID.class, original.actor(), operation))
                    .isEqualTo(committed ? original.target() : replacement.target());
            assertThat(intents(observer, b)).isOne();
            assertThat(outcomes(observer, b)).isEqualTo(committed ? 0 : 1);
            assertThat(audits(observer, b, "INTENT_RECORDED")).isOne();
        }
    }

    @Test
    void sensitiveReadDoesNotReturnAfterPhysicalCommitUntilAcknowledgementAndCleanup() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, false);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch acknowledge = new CountDownLatch(1);
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterCommit(ObservedSession session) throws Exception {
                committed.countDown();
                assertThat(acknowledge.await(8, TimeUnit.SECONDS)).isTrue();
            }
        });
        var executor = Executors.newSingleThreadExecutor();
        try (LogCapture logs = new LogCapture(trace)) {
            var future = executor.submit(() -> fixture.service(trace).readUserSecurity(intent));
            try {
                assertThat(committed.await(8, TimeUnit.SECONDS)).isTrue();
                assertThat(future.isDone()).as("No service payload before commit acknowledgement").isFalse();
                assertThat(logs.messages()).isEmpty();
                try (Connection observer = fixture.source().getConnection()) {
                    assertThat(outcomes(observer, intent)).isOne();
                    assertThat(successAudits(observer, intent)).isOne();
                }
                acknowledge.countDown();
                Result<UserSecurity> result = future.get(8, TimeUnit.SECONDS);
                assertThat(result).isEqualTo(new Success<>(new UserSecurity(fixture.target(), UserStatus.ACTIVE)));
                trace.assertClosed(1);
                logs.assertMessages("event=DOMAIN_TRANSACTION"
                        + " operation=PLATFORM_USER_SECURITY_READ completion=COMMITTED");
                logs.assertSafe(fixture, intent);
            } finally { acknowledge.countDown(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void actualPrimaryBudgetIncludesWithheldCommitAcknowledgementAndReleasesNoReadPayload() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, false);
        CountDownLatch physicallyCommitted = new CountDownLatch(1);
        CountDownLatch acknowledgement = new CountDownLatch(1);
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterCommit(ObservedSession session) throws Exception {
                physicallyCommitted.countDown();
                // Real commit already completed. The production fifteen-second
                // budget must interrupt this finite observer ACK wait itself.
                assertThat(acknowledgement.await(25, TimeUnit.SECONDS)).isTrue();
            }
        });
        var executor = Executors.newSingleThreadExecutor();
        long start = System.nanoTime();
        try {
            var future = executor.submit(() -> fixture.service(trace).readUserSecurity(intent));
            try {
                assertThat(physicallyCommitted.await(8, TimeUnit.SECONDS)).isTrue();
                assertThat(future.isDone()).as("No early read result despite actual commit").isFalse();
                try (Connection observer = fixture.source().getConnection()) {
                    assertThat(pid(observer)).isNotEqualTo(trace.sessions.getFirst().pid);
                    assertThat(outcomes(observer, intent)).isOne();
                    assertThat(successAudits(observer, intent)).isOne();
                }
                assertFailure(future.get(20, TimeUnit.SECONDS), Code.COMMIT_UNKNOWN);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertThat(elapsed).as("Real 15 s primary deadline plus bounded cleanup, not a short test timeout")
                        .isBetween(14_000L, 18_500L);
                assertThat(acknowledgement.getCount()).as("Test never released the withheld commit ACK").isOne();
                trace.assertClosed(1);
                assertThat(trace.sessions.getFirst().commits.get()).isOne();
                try (Connection observer = fixture.source().getConnection()) {
                    assertThat(outcomes(observer, intent)).isOne();
                    assertThat(successAudits(observer, intent)).isOne();
                    assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isZero();
                }
                System.out.printf("PLATFORM_ACCESS_BUDGET phase=PRIMARY elapsed_ms=%d limit_ms=15000"
                        + " commit_ack_withheld=true payload_released=false%n", elapsed);
            } finally { acknowledgement.countDown(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void fallbackAuditHasItsOwnActualTwoSecondBudgetAndNoRecursiveFallbackAfterExactPidContention()
            throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.revokeIntent(UUID.randomUUID());
        register(fixture.service(), intent, true);
        CountDownLatch fallbackAudit = new CountDownLatch(1);
        AtomicInteger fallbackPid = new AtomicInteger();
        AtomicReference<Long> fallbackStarted = new AtomicReference<>();
        AtomicBoolean primaryClosedBeforeBlocking = new AtomicBoolean();
        try (Connection blocker = fixture.source().getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid = pid(blocker);
            Trace trace = new Trace(fixture.source(), new Plan() {
                @Override void afterBegin(ObservedSession session) {
                    if (session.index == 2) { fallbackStarted.set(System.nanoTime()); }
                }
                @Override void afterStatement(ObservedSession session, Stage stage) throws Exception {
                    if (session.index == 1 && stage == Stage.MUTATION) {
                        throw new SQLException(PRIVATE_VALUE, "XX000");
                    }
                }
                @Override void afterClose(ObservedSession session) throws Exception {
                    if (session.index == 1) {
                        assertThat(session.rollbacks.get()).isOne();
                        assertThat(session.connection.isClosed()).isTrue();
                        primaryClosedBeforeBlocking.set(true);
                        try (var statement = blocker.createStatement()) {
                            statement.setQueryTimeout(5);
                            statement.execute("LOCK TABLE platform_access_audit_events IN ACCESS EXCLUSIVE MODE");
                        }
                    }
                }
                @Override void beforeStatement(ObservedSession session, Stage stage) throws Exception {
                    if (session.index == 2 && stage == Stage.AUDIT) {
                        assertThat(primaryClosedBeforeBlocking.get()).isTrue();
                        int configured = scalar(session.connection, """
                                SELECT (extract(epoch FROM current_setting('transaction_timeout')::interval)*1000)::integer
                                """, Integer.class);
                        assertThat(configured).as("Fallback gets a separate nonrenewed cap of at most two seconds")
                                .isBetween(1, 2_000);
                        fallbackPid.set(session.pid);
                        fallbackAudit.countDown();
                    }
                }
            });
            var executor = Executors.newSingleThreadExecutor();
            try (LogCapture logs = new LogCapture(trace)) {
                var future = executor.submit(() -> fixture.service(trace).revokeSupportGrant(intent));
                try {
                    assertThat(fallbackAudit.await(8, TimeUnit.SECONDS)).isTrue();
                    assertThat(fallbackPid.get()).isNotEqualTo(blockerPid);
                    awaitBlocked(fixture.source(), fallbackPid.get(), blockerPid);
                    assertFailure(future.get(6, TimeUnit.SECONDS), Code.DATABASE_UNAVAILABLE);
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fallbackStarted.get());
                    assertThat(elapsed).as("Actual two-second fallback deadline plus bounded cleanup")
                            .isBetween(1_500L, 4_500L);
                    trace.assertClosed(2);
                    assertThat(trace.sessions.get(1).commits.get()).isZero();
                    assertThat(logs.writes.get()).as("Only primary and one bounded fallback phase").isEqualTo(2);
                    assertThat(logs.capture.list.getFirst().getFormattedMessage().equals(
                            "event=DOMAIN_TRANSACTION operation=PLATFORM_SUPPORT_REVOKE"
                                    + " completion=ROLLED_BACK reason=DATABASE_UNAVAILABLE")).isTrue();
                    assertThat(logs.capture.list.get(1).getFormattedMessage()
                            .startsWith("event=DOMAIN_TRANSACTION operation=PLATFORM_ATTEMPT_AUDIT completion="))
                            .isTrue();
                    logs.assertSafe(fixture, intent);
                    System.out.printf("PLATFORM_ACCESS_BUDGET phase=FALLBACK elapsed_ms=%d limit_ms=2000"
                            + " waiter=%d blocker=%d recursive_fallback=false%n", elapsed, fallbackPid.get(), blockerPid);
                } finally { blocker.rollback(); }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            try (Connection observer = fixture.source().getConnection()) {
                assertThat(grantState(observer, fixture)).isEqualTo("ACTIVE:1");
                assertThat(outcomes(observer, intent)).isZero();
                assertThat(successAudits(observer, intent)).isZero();
                assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isZero();
                assertThat(audits(observer, intent, "INTENT_RECORDED")).isOne();
            }
        }
    }

    @Test
    void primaryTechnicalAndFallbackAuditFailuresEmitSafeDistinctReasonsOnlyAfterEachLeaseCloses() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.revokeIntent(UUID.randomUUID());
        register(fixture.service(), intent, true);
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterStatement(ObservedSession session, Stage stage) throws Exception {
                if (session.index == 1 && stage == Stage.MUTATION) {
                    throw new SQLException(PRIVATE_VALUE, "XX000");
                }
                if (session.index == 2 && stage == Stage.AUDIT) {
                    throw new IllegalStateException(PRIVATE_VALUE);
                }
            }
        });
        try (LogCapture logs = new LogCapture(trace)) {
            Result<?> result = fixture.service(trace).revokeSupportGrant(intent);
            assertThat(result).isInstanceOf(Failure.class);
            assertThat(((Failure<?>) result).code()).isIn(Code.DATABASE_UNAVAILABLE, Code.AUDIT_UNAVAILABLE,
                    Code.UNEXPECTED_FAILURE);
            trace.assertClosed(2);
            logs.assertMessages(
                    "event=DOMAIN_TRANSACTION operation=PLATFORM_SUPPORT_REVOKE completion=ROLLED_BACK reason=DATABASE_UNAVAILABLE",
                    "event=DOMAIN_TRANSACTION operation=PLATFORM_ATTEMPT_AUDIT completion=ROLLED_BACK reason=UNEXPECTED_FAILURE");
            assertThat(logs.capture.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(logs.capture.list.get(1).getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            logs.assertSafe(fixture, intent);
        }
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(grantState(observer, fixture)).isEqualTo("ACTIVE:1");
            assertThat(outcomes(observer, intent)).isZero();
            assertThat(successAudits(observer, intent)).isZero();
            assertThat(attemptAudits(observer, intent, "OPERATION_FAILED")).isZero();
        }
    }

    @Test
    void brokenDiagnosticSinkCannotReplaceAcknowledgedReadOrPreventResourceCleanup() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, false);
        Trace trace = new Trace(fixture.source(), new Plan() { });
        try (LogCapture logs = new LogCapture(trace)) {
            logs.failSink = true;
            assertThat(fixture.service(trace).readUserSecurity(intent))
                    .isEqualTo(new Success<>(new UserSecurity(fixture.target(), UserStatus.ACTIVE)));
            trace.assertClosed(1);
            assertThat(logs.writes.get()).isOne();
        }
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(outcomes(observer, intent)).isOne();
            assertThat(successAudits(observer, intent)).isOne();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTUAL", "SYNCHRONIZATION", "BOUND_CONNECTION"})
    void ambientRejectionTouchesNoWorkerConnectionFactsFallbackObserverOrSink(String mode) throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        Intent revoke = fixture.revokeIntent(UUID.randomUUID());
        Trace trace = new Trace(fixture.source(), new Plan() { });
        AtomicInteger factsCalls = new AtomicInteger();
        PlatformAccessService service = new PlatformAccessService(new OwnedPlatformAccessTransactions(trace),
                new JdbcPlatformAccessRepository(), () -> {
                    factsCalls.incrementAndGet();
                    return fixture.facts().get();
                });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(trace)).isFalse();
        Object foreignHolder = new Object();
        try (LogCapture logs = new LogCapture(trace)) {
            try {
                switch (mode) {
                    case "ACTUAL" -> TransactionSynchronizationManager.setActualTransactionActive(true);
                    case "SYNCHRONIZATION" -> TransactionSynchronizationManager.initSynchronization();
                    default -> TransactionSynchronizationManager.bindResource(trace, foreignHolder);
                }
                assertFailure(service.registerUserSecurityRead(intent), Code.AMBIENT_TRANSACTION);
                assertFailure(service.registerSupportRevoke(revoke), Code.AMBIENT_TRANSACTION);
                assertFailure(service.readUserSecurity(intent), Code.AMBIENT_TRANSACTION);
                assertFailure(service.revokeSupportGrant(revoke), Code.AMBIENT_TRANSACTION);
                assertFailure(service.readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT),
                        Code.AMBIENT_TRANSACTION);
                assertFailure(service.readRevokeReceipt(revoke), Code.AMBIENT_TRANSACTION);
                assertThat(trace.acquisitions.get()).isZero();
                assertThat(factsCalls.get()).isZero();
                assertThat(logs.messages()).isEmpty();
                if (mode.equals("ACTUAL")) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                } else if (mode.equals("SYNCHRONIZATION")) {
                    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
                } else {
                    assertThat(TransactionSynchronizationManager.getResource(trace)).isSameAs(foreignHolder);
                }
            } finally {
                if (mode.equals("BOUND_CONNECTION")) { TransactionSynchronizationManager.unbindResource(trace); }
                else if (mode.equals("SYNCHRONIZATION")) { TransactionSynchronizationManager.clearSynchronization(); }
                else { TransactionSynchronizationManager.setActualTransactionActive(false); }
            }
        }
        try (Connection observer = fixture.source().getConnection()) {
            assertThat(intents(observer, intent)).isZero();
            assertThat(scalar(observer, "SELECT count(*)::integer FROM platform_access_audit_events", Integer.class)).isZero();
        }
    }

    @Test
    void exhaustedOwnedPoolFailsBoundedlyWithoutAbortingTheHeldConnectionAndRecovers() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, false);
        DriverManagerDataSource original = (DriverManagerDataSource) fixture.source();
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(original.getUrl());
        configuration.setUsername(original.getUsername());
        configuration.setPassword(original.getPassword());
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(300);
        configuration.setInitializationFailTimeout(-1);
        configuration.setPoolName("platform-access-owned-test");
        try (HikariDataSource pool = new HikariDataSource(configuration)) {
            try (Connection held = pool.getConnection()) {
                int heldPid = pid(held);
                long started = System.nanoTime();
                Result<?> result = fixture.service(pool).readUserSecurity(intent);
                assertThat(result).isInstanceOf(Failure.class);
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(5);
                assertThat(held.isValid(2)).isTrue();
                assertThat(pid(held)).isEqualTo(heldPid);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isOne();
                assertThat(outcomes(held, intent)).isZero();
            }
            assertThat(fixture.service(pool).readUserSecurity(intent))
                    .isEqualTo(new Success<>(new UserSecurity(fixture.target(), UserStatus.ACTIVE)));
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test
    void cancellationAfterPoolReturnNeverAbortsTheSamePhysicalConnectionOwnedByANewBorrower() throws Exception {
        WorkflowFixture fixture = fixture();
        Intent intent = fixture.readIntent(UUID.randomUUID());
        register(fixture.service(), intent, false);
        DriverManagerDataSource original = (DriverManagerDataSource) fixture.source();
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(original.getUrl());
        configuration.setUsername(original.getUsername());
        configuration.setPassword(original.getPassword());
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(500);
        configuration.setInitializationFailTimeout(-1);
        configuration.setPoolName("platform-access-returned-lease-test");
        CountDownLatch returnedToPool = new CountDownLatch(1);
        CountDownLatch releaseCloseAcknowledgement = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicReference<Result<UserSecurity>> result = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        try (HikariDataSource pool = new HikariDataSource(configuration)) {
            Trace trace = new Trace(pool, new Plan() {
                @Override void afterClose(ObservedSession session) throws Exception {
                    // The actual Hikari handle is closed before this observer barrier.
                    // Only acknowledgement is withheld; no SQL/commit is fabricated.
                    returnedToPool.countDown();
                    boolean interrupted = false;
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    try {
                        while (true) {
                            long remaining = end - System.nanoTime();
                            assertThat(remaining).as("Bounded observer close-ack barrier").isPositive();
                            try {
                                if (releaseCloseAcknowledgement.await(remaining, TimeUnit.NANOSECONDS)) { break; }
                                throw new AssertionError("Observer close-ack barrier timed out");
                            } catch (InterruptedException expectedCancellation) {
                                interrupted = true;
                            }
                        }
                    } finally {
                        if (interrupted) { Thread.currentThread().interrupt(); }
                    }
                }
            });
            Thread caller = Thread.ofPlatform().name("platform-access-cancellation-test-caller").unstarted(() -> {
                try { result.set(fixture.service(trace).readUserSecurity(intent)); }
                catch (Throwable failure) { unexpected.set(failure); }
                finally { callerFinished.countDown(); }
            });
            try (LogCapture logs = new LogCapture(trace)) {
                caller.start();
                try {
                    assertThat(returnedToPool.await(8, TimeUnit.SECONDS)).isTrue();
                    assertThat(callerFinished.getCount()).as("Service is still awaiting owned cleanup acknowledgement")
                            .isOne();
                    try (Connection nextBorrower = pool.getConnection()) {
                        int reusedPid = pid(nextBorrower);
                        assertThat(reusedPid).as("The same physical PostgreSQL connection was reborrowed")
                                .isEqualTo(trace.sessions.getFirst().pid);
                        nextBorrower.setAutoCommit(false);
                        caller.interrupt();
                        assertThat(callerFinished.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(unexpected.get() == null).as("No uncaught caller failure").isTrue();
                        assertFailure(result.get(), Code.COMMIT_UNKNOWN);
                        assertThat(trace.sessions.getFirst().aborts.get()).isZero();
                        assertThat(nextBorrower.isValid(2)).isTrue();
                        assertThat(pid(nextBorrower)).isEqualTo(reusedPid);
                        assertThat(outcomes(nextBorrower, intent)).isOne();
                        assertThat(successAudits(nextBorrower, intent)).isOne();
                        assertThat(attemptAudits(nextBorrower, intent, "OPERATION_FAILED")).isZero();
                        nextBorrower.commit();
                        releaseCloseAcknowledgement.countDown();
                        assertThat(logs.firstWrite.await(5, TimeUnit.SECONDS)).isTrue();
                        trace.assertClosed(1);
                        assertThat(trace.sessions.getFirst().aborts.get()).isZero();
                        assertThat(nextBorrower.isValid(2)).isTrue();
                        assertThat(pid(nextBorrower)).isEqualTo(reusedPid);
                        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isOne();
                        logs.assertMessages("event=DOMAIN_TRANSACTION operation=PLATFORM_USER_SECURITY_READ"
                                + " completion=COMMITTED reason=INTERRUPTED");
                        logs.assertSafe(fixture, intent);
                    }
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                } finally {
                    releaseCloseAcknowledgement.countDown();
                    caller.join(5_000);
                    assertThat(caller.isAlive()).isFalse();
                }
            }
        }
    }

    @Test
    void cleanupFailureKeepsOriginalFailureObjectWhileHigherDiagnosticSeverityWins() throws Exception {
        WorkflowFixture fixture = fixture();
        SQLException original = new SQLException(PRIVATE_VALUE, "XX000");
        Trace trace = new Trace(fixture.source(), new Plan() {
            @Override void afterClose(ObservedSession session) { throw new IllegalStateException(PRIVATE_VALUE); }
        });
        try (LogCapture logs = new LogCapture(trace)) {
            var phase = new OwnedPlatformAccessTransactions(trace).primary(Operation.PLATFORM_USER_SECURITY_READ,
                    (connection, deadline) -> { throw original; });
            assertThat(phase.failure() == original).as("Exact original failure retained internally").isTrue();
            assertThat(phase.value()).isNull();
            assertThat(phase.completion()).isEqualTo(Diagnostics.Completion.ROLLED_BACK);
            assertThat(phase.reason()).isEqualTo(Diagnostics.Reason.UNEXPECTED_FAILURE);
            assertThat(phase.cleanupComplete()).isTrue();
            trace.assertClosed(1);
            logs.assertMessages("event=DOMAIN_TRANSACTION operation=PLATFORM_USER_SECURITY_READ"
                    + " completion=ROLLED_BACK reason=UNEXPECTED_FAILURE");
            assertThat(logs.capture.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(logs.messages().stream().noneMatch(message -> message.contains(PRIVATE_VALUE))).isTrue();
        }
    }

    private static WorkflowFixture fixture() throws Exception {
        DataSource source = database(POSTGRES);
        flyway(source, "12").migrate();
        return workflow(source, true);
    }

    private static void register(PlatformAccessService service, Intent intent, boolean revoke) {
        assertThat(revoke ? service.registerSupportRevoke(intent) : service.registerUserSecurityRead(intent))
                .isInstanceOf(Success.class);
    }

    private static Result<?> execute(PlatformAccessService service, Intent intent, boolean revoke) {
        return revoke ? service.revokeSupportGrant(intent) : service.readUserSecurity(intent);
    }

    private static Result<Receipt> receipt(PlatformAccessService service, Intent intent, boolean revoke) {
        return revoke ? service.readRevokeReceipt(intent)
                : service.readUserSecurityReceipt(intent, ReadReason.USER_REQUESTED_SUPPORT);
    }

    private static Map<String, List<String>> durableRows(Connection connection) throws SQLException {
        return rows(connection, List.of("platform_access_grants", "platform_access_grant_targets",
                "platform_access_operation_intents", "platform_access_operation_outcomes"), false);
    }

    private static List<String> nonFailureAuditRows(Connection connection) throws SQLException {
        return strings(connection, "SELECT to_jsonb(a)::text FROM platform_access_audit_events a"
                + " WHERE event_kind <> 'OPERATION_FAILED' ORDER BY id");
    }

    private static void assertFailure(Result<?> result, Code code) {
        assertThat(result).isEqualTo(new Failure<>(code));
    }

    private static int intents(Connection connection, Intent intent) throws SQLException {
        return scalar(connection, "SELECT count(*)::integer FROM platform_access_operation_intents"
                + " WHERE initiator_user_id=? AND operation_id=?", Integer.class, intent.initiatorUserId(), intent.operationId());
    }

    private static int outcomes(Connection connection, Intent intent) throws SQLException {
        return scalar(connection, "SELECT count(*)::integer FROM platform_access_operation_outcomes"
                + " WHERE initiator_user_id=? AND operation_id=?", Integer.class, intent.initiatorUserId(), intent.operationId());
    }

    private static int successAudits(Connection connection, Intent intent) throws SQLException {
        return scalar(connection, "SELECT count(*)::integer FROM platform_access_audit_events"
                + " WHERE initiator_user_id=? AND operation_id=? AND event_kind IN ('READ_ADMITTED','GRANT_REVOKED')",
                Integer.class, intent.initiatorUserId(), intent.operationId());
    }

    private static int audits(Connection connection, Intent intent, String kind) throws SQLException {
        return scalar(connection, "SELECT count(*)::integer FROM platform_access_audit_events"
                + " WHERE initiator_user_id=? AND operation_id=? AND event_kind=?",
                Integer.class, intent.initiatorUserId(), intent.operationId(), kind);
    }

    private static int attemptAudits(Connection connection, Intent intent, String kind) throws SQLException {
        assertThat(scalar(connection, """
                SELECT count(*)::integer FROM platform_access_audit_events
                WHERE event_kind=? AND supplied_target_user_id=? AND supplied_target_grant_id IS NOT DISTINCT FROM ?
                  AND (initiator_user_id IS NOT NULL OR operation_id IS NOT NULL OR target_user_id IS NOT NULL
                    OR target_grant_id IS NOT NULL OR target_verification <> 'SUPPLIED')
                """, Integer.class, kind, intent.targetUserId(), intent.targetGrantId()))
                .as("Attempt audit retains supplied target only, never an operation-history link").isZero();
        return scalar(connection, """
                SELECT count(*)::integer FROM platform_access_audit_events
                WHERE event_kind=? AND supplied_target_user_id=? AND supplied_target_grant_id IS NOT DISTINCT FROM ?
                """, Integer.class, kind, intent.targetUserId(), intent.targetGrantId());
    }

    private static String grantState(Connection connection, WorkflowFixture fixture) throws SQLException {
        return scalar(connection, "SELECT state || ':' || revision FROM platform_access_grants WHERE id=?",
                String.class, fixture.targetGrant());
    }

    enum Stage { INTENT, MUTATION, AUDIT, OUTCOME, PRECOMMIT_WORK, OTHER }

    private abstract static class Plan {
        void afterBegin(ObservedSession session) throws Exception { }
        void beforeStatement(ObservedSession session, Stage stage) throws Exception { }
        void afterStatement(ObservedSession session, Stage stage) throws Exception { }
        void beforeCommit(ObservedSession session) throws Exception { }
        void afterCommit(ObservedSession session) throws Exception { }
        void afterClose(ObservedSession session) throws Exception { }
    }

    /** Observer only: every prepared execute, commit, rollback and close delegates first unless the named hook says otherwise. */
    private static final class Trace extends AbstractDataSource {
        private final DataSource delegate;
        private final Plan plan;
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final List<ObservedSession> sessions = new CopyOnWriteArrayList<>();

        private Trace(DataSource delegate, Plan plan) {
            this.delegate = delegate;
            this.plan = plan;
        }

        @Override public Connection getConnection() throws SQLException { return observe(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return observe(delegate.getConnection(user, password));
        }

        private Connection observe(Connection connection) throws SQLException {
            int index = acquisitions.incrementAndGet();
            assertThat(active.get()).as("Previous owned phase released its lease before fallback acquisition").isZero();
            ObservedSession session = new ObservedSession(connection, index);
            sessions.add(session);
            active.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("commit")) {
                            session.observeTransaction();
                            plan.beforeCommit(session);
                            Object result = invoke(method, connection, arguments);
                            session.commits.incrementAndGet();
                            plan.afterCommit(session);
                            return result;
                        }
                        if (name.equals("rollback") && (arguments == null || arguments.length == 0)) {
                            Object result = invoke(method, connection, arguments);
                            session.rollbacks.incrementAndGet();
                            return result;
                        }
                        if (name.equals("close")) {
                            Object result = invoke(method, connection, arguments);
                            if (session.closed.compareAndSet(false, true)) { active.decrementAndGet(); }
                            plan.afterClose(session);
                            return result;
                        }
                        if (name.equals("abort")) { session.aborts.incrementAndGet(); }
                        Object result = invoke(method, connection, arguments);
                        if (name.equals("setAutoCommit")) {
                            session.autoCommitValues.add((Boolean) arguments[0]);
                            if (Boolean.FALSE.equals(arguments[0])) {
                                session.pid = pid(connection);
                                session.transactionId = scalar(connection, "SELECT pg_current_xact_id()::text", String.class);
                                plan.afterBegin(session);
                            }
                        }
                        if (result instanceof PreparedStatement statement && name.equals("prepareStatement")) {
                            String sql = ((String) arguments[0]).replaceAll("\\s+", " ").trim()
                                    .toLowerCase(java.util.Locale.ROOT).replace("public.", "");
                            return observeStatement(statement, session, sql);
                        }
                        return result;
                    });
        }

        private PreparedStatement observeStatement(PreparedStatement statement, ObservedSession session, String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        boolean executing = method.getName().startsWith("execute");
                        Stage stage = !executing ? Stage.OTHER
                                : sql.startsWith("insert into platform_access_operation_intents") ? Stage.INTENT
                                : sql.startsWith("update platform_access_grants") ? Stage.MUTATION
                                : sql.startsWith("insert into platform_access_audit_events") ? Stage.AUDIT
                                : sql.startsWith("insert into platform_access_operation_outcomes") ? Stage.OUTCOME
                                : sql.contains("set_config('lock_timeout'") && session.writes.contains(Stage.OUTCOME)
                                        ? Stage.PRECOMMIT_WORK : Stage.OTHER;
                        if (stage != Stage.OTHER) { plan.beforeStatement(session, stage); }
                        Object result = invoke(method, statement, arguments);
                        if (executing) {
                            if (stage != Stage.OTHER) {
                                session.observeTransaction();
                                session.writes.add(stage);
                                plan.afterStatement(session, stage);
                            }
                        }
                        return result;
                    });
        }

        private void assertClosed(int count) throws SQLException {
            assertThat(acquisitions.get()).isEqualTo(count);
            assertThat(active.get()).isZero();
            for (ObservedSession session : sessions) {
                assertThat(session.closed.get()).isTrue();
                assertThat(session.connection.isClosed()).isTrue();
            }
        }
    }

    private static final class ObservedSession {
        private final Connection connection;
        private final int index;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<Boolean> autoCommitValues = new ArrayList<>();
        private final List<String> transactionIds = new ArrayList<>();
        private final List<Stage> writes = new ArrayList<>();
        private int pid;
        private String transactionId;

        private ObservedSession(Connection connection, int index) { this.connection = connection; this.index = index; }

        private void observeTransaction() throws SQLException {
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(pid(connection)).isEqualTo(pid);
            transactionIds.add(scalar(connection, "SELECT pg_current_xact_id()::text", String.class));
            assertThat(transactionIds).containsOnly(transactionId);
        }
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class LogCapture implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(Diagnostics.LOGGER_NAME);
        private final ch.qos.logback.classic.Level priorLevel = logger.getLevel();
        private final boolean priorAdditivity = logger.isAdditive();
        private final AtomicInteger writes = new AtomicInteger();
        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final AtomicBoolean observedLeasedOutput = new AtomicBoolean();
        private volatile boolean failSink;
        private final ListAppender<ILoggingEvent> capture;

        private LogCapture(Trace trace) {
            capture = new ListAppender<>() {
                { list = new CopyOnWriteArrayList<>(); }
                @Override protected void append(ILoggingEvent event) {
                    if (trace.active.get() != 0) { observedLeasedOutput.set(true); }
                    writes.incrementAndGet();
                    if (failSink) { throw new IllegalStateException(PRIVATE_VALUE); }
                    event.prepareForDeferredProcessing();
                    super.append(event);
                    firstWrite.countDown();
                }
            };
            capture.setContext(logger.getLoggerContext());
            capture.start();
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            logger.setAdditive(false);
            logger.addAppender(capture);
        }

        private List<String> messages() { return capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }

        private void assertMessages(String... expected) {
            assertThat(messages().equals(List.of(expected))).as("Exact closed safe diagnostic messages").isTrue();
        }

        private void assertSafe(WorkflowFixture fixture, Intent intent) {
            List<String> forbidden = List.of(PRIVATE_VALUE, fixture.actor().toString(), fixture.target().toString(),
                    fixture.actorGrant().toString(), fixture.targetGrant().toString(), intent.operationId().toString());
            assertThat(capture.list.stream().allMatch(event -> event.getThrowableProxy() == null)).isTrue();
            assertThat(capture.list.stream().allMatch(event -> event.getMDCPropertyMap().isEmpty())).isTrue();
            assertThat(messages().stream().noneMatch(message -> forbidden.stream().anyMatch(message::contains))).isTrue();
        }

        @Override public void close() {
            logger.detachAppender(capture);
            capture.stop();
            logger.setLevel(priorLevel);
            logger.setAdditive(priorAdditivity);
            assertThat(observedLeasedOutput.get()).as("No diagnostic output under an owned lease").isFalse();
        }
    }
}
