package com.creastrix.platform.user;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import com.creastrix.platform.user.application.AuthenticatedUserService;
import com.creastrix.platform.user.application.AuthenticatedUserService.AdmissionDeniedException;
import com.creastrix.platform.user.application.AuthenticatedUserService.InactiveUserException;
import com.creastrix.platform.user.application.AuthenticatedUserService.ResolutionUnavailableException;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.PairAlreadyBoundException;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.user.persistence.JdbcUserIdentityBindingRepository;
import com.creastrix.platform.user.persistence.JdbcUserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real JDBC statements, PostgreSQL constraints/transactions and owned worker lifecycle proofs. */
@Testcontainers
class UserAuthenticationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static HikariDataSource pool;
    private static ObservedDataSource observed;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate sql;
    private static AuthenticatedUserService auth;
    private static UserService users;
    private static UserIdentityBindingRepository bindings;
    private static TransactionTemplate transaction;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionWiring { }

    @BeforeAll
    static void start() {
        pool = newPool(null);
        Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                .executeInTransaction(true).group(false)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load().migrate();
        observed = new ObservedDataSource(pool);
        sql = new JdbcTemplate(pool); // independent observer: never altered by injection hooks
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionWiring.class);
        context.registerBean(DataSource.class, () -> observed);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(observed));
        context.registerBean(PlatformTransactionManager.class, () -> new JdbcTransactionManager(observed));
        context.registerBean(JdbcUserRepository.class);
        context.registerBean(UserService.class);
        context.registerBean(JdbcUserIdentityBindingRepository.class);
        context.registerBean(AuthenticatedUserService.class);
        context.refresh();
        auth = context.getBean(AuthenticatedUserService.class);
        users = context.getBean(UserService.class);
        bindings = context.getBean(UserIdentityBindingRepository.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void resetObserverAndProvePoolRecovery() throws Exception {
        observed.plan = new Plan();
        await(() -> pool.getHikariPoolMXBean().getActiveConnections() == 0
                && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0, 5);
        assertThat(sql.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void freshV1ThroughV11HasExactImmediateKeysAndRetainedBindings() {
        assertThat(sql.queryForObject("SHOW server_version", String.class)).startsWith("18.4");
        assertThat(sql.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        List<Map<String, Object>> keys = sql.queryForList("""
                SELECT conname, contype::text, condeferrable, confdeltype::text
                FROM pg_constraint WHERE conrelid='user_identity_bindings'::regclass
                    AND contype IN ('p', 'u', 'f', 'c') ORDER BY conname
                """);
        assertThat(keys).hasSize(4);
        assertThat(keys).anySatisfy(row -> {
            assertThat(row.get("conname")).isEqualTo("user_identity_bindings_pk");
            assertThat(row.get("contype")).isEqualTo("p");
            assertThat(row.get("condeferrable")).isEqualTo(false);
        }).anySatisfy(row -> {
            assertThat(row.get("conname")).isEqualTo("user_identity_bindings_user_unique");
            assertThat(row.get("condeferrable")).isEqualTo(false);
        }).anySatisfy(row -> {
            assertThat(row.get("conname")).isEqualTo("user_identity_bindings_user_fk");
            assertThat(row.get("confdeltype")).isEqualTo("r");
        });
        Identity identity = identity();
        User user = auth.resolve(identity, ignored -> true);
        assertThatThrownBy(() -> sql.update("UPDATE user_identity_bindings SET subject=subject WHERE user_id=?", user.id()))
                .hasStackTraceContaining("immutable and retained");
        assertThatThrownBy(() -> sql.update("DELETE FROM user_identity_bindings WHERE user_id=?", user.id()))
                .hasStackTraceContaining("immutable and retained");
        assertThatThrownBy(() -> sql.execute("TRUNCATE user_identity_bindings"))
                .hasStackTraceContaining("immutable and retained");
        assertThat(auth.resolve(identity, ignored -> true)).isEqualTo(user);
    }

    @Test
    void populatedV10UpgradePreservesEveryPriorRowAndDoesNotBackfillLegacyUsers() {
        // V9/V10 deliberately address public: isolate by an OWN database, never by search_path.
        String database = "auth_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(pool.getJdbcUrl()).isEqualTo(POSTGRES.getJdbcUrl());
        sql.execute("CREATE DATABASE \"" + database + "\"");
        try (HikariDataSource upgrade = newPool(database)) {
            Flyway.configure().dataSource(upgrade)
                    .executeInTransaction(true).group(false)
                    .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                    .target("10").load().migrate();
            JdbcTemplate upgradeSql = new JdbcTemplate(upgrade);
            assertThat(upgradeSql.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
            UUID legacy = UUID.randomUUID();
            populatePreviousDomains(upgradeSql, upgrade, legacy);
            Map<String, List<String>> before = allRows(upgradeSql, "public");
            assertThat(before).hasSize(9);
            assertThat(before.values()).allSatisfy(rows -> assertThat(rows).isNotEmpty());
            List<Map<String, Object>> history = upgradeSql.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            Flyway.configure().dataSource(upgrade)
                    .executeInTransaction(true).group(false)
                    .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load().migrate();
            Map<String, List<String>> after = allRows(upgradeSql, "public");
            before.forEach((table, rows) -> assertThat(after.get(table)).as(table).isEqualTo(rows));
            assertThat(upgradeSql.queryForList("SELECT * FROM flyway_schema_history WHERE version IS DISTINCT FROM '11' ORDER BY installed_rank"))
                    .isEqualTo(history);
            assertThat(upgradeSql.queryForObject("SELECT count(*) FROM user_identity_bindings", Integer.class)).isZero();
            assertThat(upgradeSql.queryForObject("SELECT status FROM users WHERE id=?", String.class, legacy)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void onePhysicalReadCommittedTransactionCreatesAllThreeRowsAndNoWorkspace() {
        Plan plan = observe(new Plan());
        Map<String, Long> before = counts();
        Identity identity = identity();
        User user = auth.resolve(identity, ignored -> true);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(plan.sessions).hasSize(1);
        Session session = plan.sessions.getFirst();
        assertThat(session.inserts).hasSize(3);
        assertThat(session.inserts.stream().map(Snapshot::pid).distinct()).hasSize(1);
        assertThat(session.inserts.stream().map(Snapshot::xid).distinct()).hasSize(1);
        assertThat(session.inserts).allSatisfy(snapshot -> assertThat(snapshot.isolation()).isEqualTo("read committed"));
        assertThat(session.events).containsSubsequence("insert:users", "insert:user_profiles", "insert:user_identity_bindings", "commit");
        assertDelta(before, 1);
        assertThat(auth.resolve(identity, ignored -> true)).isEqualTo(user);
        assertThat(auth.currentUser(identity, ignored -> true)).contains(user);
        assertDelta(before, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void failureAfterEachRealInsertRollsBackTheWholePhysicalTransaction(int afterInsert) {
        Map<String, Long> before = counts();
        Plan plan = observe(new Plan() {
            @Override void afterInsert(Session session) throws SQLException {
                if (session.inserts.size() == afterInsert) {
                    throw new SQLException("test failure after real INSERT", "XX000");
                }
            }
        });
        assertThatThrownBy(() -> auth.resolve(identity(), ignored -> true)).isInstanceOf(ResolutionUnavailableException.class);
        assertThat(plan.sessions.getFirst().inserts).hasSize(afterInsert);
        assertThat(plan.sessions.getFirst().events).contains("rollback").doesNotContain("commit");
        assertDelta(before, 0);
    }

    @Test
    void admissionAndAmbientTransactionsFailBeforeAnyBindingLookupOrCreate() {
        Plan plan = observe(new Plan());
        Map<String, Long> before = counts();
        assertThatThrownBy(() -> auth.resolve(identity(), ignored -> false)).isInstanceOf(AdmissionDeniedException.class);
        assertThat(plan.sessions).isEmpty();
        assertThatThrownBy(() -> transaction.execute(status -> auth.resolve(identity(), ignored -> true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(plan.sessions).hasSize(1); // outer transaction only; no worker acquisition
        assertThat(plan.sessions.getFirst().inserts).isEmpty();
        assertDelta(before, 0);
    }

    @Test
    void inactiveBindingsNeverReactivateOrCreateReplacementAndReadsNeverCreate() {
        Identity identity = identity();
        User user = auth.resolve(identity, ignored -> true);
        Map<String, Long> before = counts();
        users.changeStatus(user.id(), UserStatus.SUSPENDED);
        assertThatThrownBy(() -> auth.resolve(identity, ignored -> true)).isInstanceOf(InactiveUserException.class);
        assertThat(auth.currentUser(identity, ignored -> true).orElseThrow().status()).isEqualTo(UserStatus.SUSPENDED);
        users.changeStatus(user.id(), UserStatus.DEACTIVATED);
        assertThatThrownBy(() -> auth.resolve(identity, ignored -> true)).isInstanceOf(InactiveUserException.class);
        assertThatThrownBy(() -> auth.currentUser(identity, ignored -> false)).isInstanceOf(AdmissionDeniedException.class);
        assertThat(auth.currentUser(identity(), ignored -> true)).isEmpty();
        assertDelta(before, 0);
    }

    @Test
    void distinctExactSubjectsAndIssuersAreNeverMerged() {
        String subject = "subject-" + UUID.randomUUID();
        User a = auth.resolve(new Identity("https://issuer.example/", subject), ignored -> true);
        User b = auth.resolve(new Identity("https://issuer.example/", subject.toUpperCase()), ignored -> true);
        User c = auth.resolve(new Identity("https://second.example/", subject), ignored -> true);
        assertThat(List.of(a.id(), b.id(), c.id())).doesNotHaveDuplicates();
        // Email is deliberately not accepted by this API; HTTP tests cover same-email broker claims.
    }

    @Test
    void onlyExactPairConstraintProducesDuplicateSignalAndOtherKeysRemainFailures() {
        Identity existing = identity();
        User user = auth.resolve(existing, ignored -> true);
        User another = users.createUser();
        Throwable pair = assertThrows(PairAlreadyBoundException.class, () -> transaction.executeWithoutResult(status ->
                bindings.insert(existing, another.id())));
        assertMetadata(pair, "23505", "user_identity_bindings", "user_identity_bindings_pk");
        Throwable userKey = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status ->
                bindings.insert(identity(), user.id())));
        assertThat(userKey).isNotInstanceOf(PairAlreadyBoundException.class);
        assertMetadata(userKey, "23505", "user_identity_bindings", "user_identity_bindings_user_unique");
        Throwable foreignKey = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status ->
                bindings.insert(identity(), UUID.randomUUID())));
        assertThat(foreignKey).isNotInstanceOf(PairAlreadyBoundException.class);
        assertMetadata(foreignKey, "23503", "user_identity_bindings", "user_identity_bindings_user_fk");
    }

    @Test
    void unrelatedRealUniqueViolationDuringCreationIsNotRecoveredAsSuccess() {
        User legacy = users.createUser();
        Map<String, Long> before = counts();
        Plan plan = observe(new Plan() {
            @Override void beforeStatement(Session session, String statement) throws SQLException {
                if (statement.startsWith("INSERT INTO user_identity_bindings")) {
                    try (PreparedStatement duplicate = session.connection.prepareStatement("INSERT INTO user_profiles(user_id) VALUES (?)")) {
                        duplicate.setObject(1, legacy.id());
                        duplicate.executeUpdate(); // an actual unrelated PostgreSQL 23505
                    }
                }
            }
        });
        Throwable failure = assertThrows(ResolutionUnavailableException.class, () -> auth.resolve(identity(), ignored -> true));
        assertMetadata(failure, "23505", "user_profiles", "user_profiles_pk");
        assertThat(plan.sessions.getFirst().finds).isEqualTo(1);
        assertThat(plan.sessions.getFirst().events).contains("rollback").doesNotContain("commit");
        assertDelta(before, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousFirstLoginsHaveExactPidContentionAndCommitOrRollbackWinner(boolean rollbackWinner) throws Exception {
        race(rollbackWinner, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responseUnknownWhileFirstTransactionStillRunsCannotUseSelectAbsenceAsRollback(boolean rollbackWinner) throws Exception {
        race(rollbackWinner, false);
    }

    private void race(boolean rollbackWinner, boolean simultaneous) throws Exception {
        Identity identity = identity();
        Map<String, Long> before = counts();
        CyclicBarrier bothMissed = new CyclicBarrier(2);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Session> winner = new AtomicReference<>();
        Plan plan = observe(new Plan() {
            @Override void afterFind(Session session) throws SQLException {
                if (simultaneous && session.finds == 1) {
                    try { bothMissed.await(5, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new SQLException("test rendezvous failed", failure); }
                }
            }
            @Override void afterInsert(Session session) throws SQLException {
                if (session.inserts.size() == 3 && winner.compareAndSet(null, session)) {
                    inserted.countDown();
                    awaitLatch(release);
                    if (rollbackWinner) {
                        throw new SQLException("test winner rollback", "XX000");
                    }
                }
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> outcome(identity));
            Future<Object> second;
            if (simultaneous) {
                second = executor.submit(() -> outcome(identity));
            } else {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThrows(TimeoutException.class, () -> first.get(30, TimeUnit.MILLISECONDS));
                assertThat(sql.queryForObject("SELECT count(*) FROM user_identity_bindings WHERE issuer=? AND subject=?",
                        Integer.class, identity.issuer(), identity.subject())).isZero();
                second = executor.submit(() -> outcome(identity));
            }
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            await(() -> plan.sessions.size() == 2 && plan.sessions.stream().allMatch(s -> s.pid > 0), 3);
            Session blocked = plan.sessions.stream().filter(s -> s != winner.get()).findFirst().orElseThrow();
            int winnerPid = winner.get().pid;
            await(() -> Boolean.TRUE.equals(sql.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                    Boolean.class, winnerPid, blocked.pid)), 3);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            release.countDown();
            List<Object> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            List<User> successful = results.stream().filter(User.class::isInstance).map(User.class::cast).toList();
            assertThat(successful).hasSize(rollbackWinner ? 1 : 2);
            assertThat(successful.stream().map(User::id).distinct()).hasSize(1);
            if (rollbackWinner) {
                assertThat(results.stream().filter(ResolutionUnavailableException.class::isInstance)).hasSize(1);
                assertThat(winner.get().events).contains("rollback").doesNotContain("commit");
            } else {
                assertThat(blocked.events).containsSubsequence("pair-conflict", "rollback", "find", "commit");
                assertThat(blocked.findTransactions).hasSize(2);
                assertThat(blocked.findTransactions.get(0)).isNotEqualTo(blocked.findTransactions.get(1));
            }
            observe(new Plan());
            assertThat(auth.resolve(identity, ignored -> true).id()).isEqualTo(successful.getFirst().id());
            assertDelta(before, 1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void actualCommitThenLostAcknowledgementReturnsUnavailableAndNextResolutionFindsSameUuid() {
        Identity identity = identity();
        AtomicBoolean injected = new AtomicBoolean();
        Plan plan = observe(new Plan() {
            @Override void afterCommit(Session session) throws SQLException {
                if (injected.compareAndSet(false, true)) {
                    throw new SQLException("test lost acknowledgement AFTER real commit", "08006");
                }
            }
        });
        Map<String, Long> before = counts();
        assertThatThrownBy(() -> auth.resolve(identity, ignored -> true)).isInstanceOf(ResolutionUnavailableException.class);
        assertThat(injected).isTrue();
        assertThat(plan.sessions.getFirst().events).contains("commit");
        UUID committed = sql.queryForObject("SELECT user_id FROM user_identity_bindings WHERE issuer=? AND subject=?",
                UUID.class, identity.issuer(), identity.subject());
        assertThat(committed).isNotNull();
        assertDelta(before, 1);
        assertThat(auth.resolve(identity, ignored -> true).id()).isEqualTo(committed);
        assertDelta(before, 1);
    }

    @Test
    void connectionAcquisitionIsActuallyBoundedWithoutChangingHikariPolicy() throws Exception {
        long configuredTimeout = pool.getConnectionTimeout();
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(pool.getConnection());
            }
            long start = System.nanoTime();
            assertThatThrownBy(() -> auth.resolve(identity(), ignored -> true)).isInstanceOf(ResolutionUnavailableException.class);
            assertThat(elapsed(start)).isBetween(Duration.ofSeconds(13), Duration.ofSeconds(18));
            await(() -> pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0, 2);
            assertThat(pool.getConnectionTimeout()).isEqualTo(configuredTimeout);
        } finally {
            for (Connection connection : held) { connection.close(); }
        }
    }

    @Test
    void actualStatementTimeoutCancelsPostgresSleepAndRollsBack() {
        Map<String, Long> before = counts();
        observe(new Plan() {
            @Override void beforeStatement(Session session, String statement) throws SQLException {
                if (statement.contains("FROM user_identity_bindings b")) {
                    try (var sleep = session.connection.createStatement()) {
                        sleep.execute("SELECT pg_sleep(30)");
                    }
                }
            }
        });
        long start = System.nanoTime();
        Throwable failure = assertThrows(ResolutionUnavailableException.class, () -> auth.resolve(identity(), ignored -> true));
        assertThat(findPostgres(failure).getSQLState()).isEqualTo("57014");
        assertThat(elapsed(start)).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(14));
        assertDelta(before, 0);
    }

    @Test
    void actualLockTimeoutTargetsExactBlockedPidAndLeavesNoPartialRows() throws Exception {
        Map<String, Long> before = counts();
        Plan plan = observe(new Plan());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = pool.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (var statement = holder.createStatement()) {
                try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); holderPid = rows.getInt(1); }
                statement.execute("LOCK TABLE user_identity_bindings IN ACCESS EXCLUSIVE MODE");
            }
            try {
                long start = System.nanoTime();
                Future<Object> pending = executor.submit(() -> outcome(identity()));
                await(() -> !plan.sessions.isEmpty() && plan.sessions.getFirst().pid > 0, 3);
                int blockedPid = plan.sessions.getFirst().pid;
                await(() -> Boolean.TRUE.equals(sql.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                        Boolean.class, holderPid, blockedPid)), 3);
                Object outcome = pending.get(10, TimeUnit.SECONDS);
                assertThat(outcome).isInstanceOf(ResolutionUnavailableException.class);
                assertThat(findPostgres((Throwable) outcome).getSQLState()).isEqualTo("55P03");
                assertThat(elapsed(start)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(9));
            } finally { holder.rollback(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertDelta(before, 0);
    }

    @Test
    void overallDeadlineInterruptsOwnedWorkAndRollsBackAlreadyInsertedRows() throws Exception {
        Map<String, Long> before = counts();
        observe(new Plan() {
            @Override void afterInsert(Session session) throws SQLException {
                if (session.inserts.size() == 2) {
                    try { new CountDownLatch(1).await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("test wait interrupted by overall deadline", failure);
                    }
                }
            }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> auth.resolve(identity(), ignored -> true)).isInstanceOf(ResolutionUnavailableException.class);
        assertThat(elapsed(start)).isBetween(Duration.ofSeconds(13), Duration.ofSeconds(18));
        await(() -> pool.getHikariPoolMXBean().getActiveConnections() == 0, 5);
        assertDelta(before, 0);
    }

    private static HikariDataSource newPool(String database) {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(database == null ? POSTGRES.getJdbcUrl()
                : "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database);
        configuration.setUsername(POSTGRES.getUsername());
        configuration.setPassword(POSTGRES.getPassword());
        configuration.setMaximumPoolSize(4);
        configuration.setMinimumIdle(0);
        return new HikariDataSource(configuration);
    }

    private static void populatePreviousDomains(JdbcTemplate database, DataSource source, UUID owner) {
        UUID editor = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID personalWorkspace = UUID.randomUUID();
        UUID organizationWorkspace = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        TransactionTemplate writes = new TransactionTemplate(new JdbcTransactionManager(source));
        writes.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.executeWithoutResult(status -> {
            for (UUID user : List.of(owner, editor)) {
                database.update("INSERT INTO users(id) VALUES (?)", user);
                database.update("INSERT INTO user_profiles(user_id) VALUES (?)", user);
            }
            database.update("INSERT INTO organizations(id) VALUES (?)", organization);
            database.update("INSERT INTO organization_memberships VALUES (?, ?, 'OWNER', 'ACTIVE')", organization, owner);
            database.update("INSERT INTO workspaces VALUES (?, 'USER', ?, NULL)", personalWorkspace, owner);
            database.update("INSERT INTO workspace_memberships VALUES (?, ?, 'ADMIN', 'ACTIVE')", personalWorkspace, owner);
            database.update("INSERT INTO workspaces VALUES (?, 'ORGANIZATION', NULL, ?)", organizationWorkspace, organization);
            database.update("INSERT INTO workspace_memberships VALUES (?, ?, 'ADMIN', 'ACTIVE')", organizationWorkspace, owner);
            database.update("INSERT INTO workspace_memberships VALUES (?, ?, 'EDITOR', 'ACTIVE')", organizationWorkspace, editor);
            database.update("INSERT INTO workspace_membership_scopes VALUES (?, ?, 'READY_MADE_PRODUCTS')", organizationWorkspace, editor);
        });
        writes.executeWithoutResult(status -> {
            database.update("INSERT INTO ready_made_products VALUES (?, ?, ?, 'ACTIVE', 7)", product, organizationWorkspace, editor);
            database.update("INSERT INTO ready_made_product_manual_quantity_delta_commands "
                    + "(product_id, command_id, delta, state) VALUES (?, ?, 2, 'REGISTERED')", product, UUID.randomUUID());
        });
    }

    private static Map<String, List<String>> allRows(JdbcTemplate database, String schema) {
        Map<String, List<String>> rows = new java.util.LinkedHashMap<>();
        for (String table : database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=? "
                + "AND tablename<>'flyway_schema_history' ORDER BY tablename", String.class, schema)) {
            rows.put(table, database.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t ORDER BY row_to_json(t)::text", String.class));
        }
        return rows;
    }

    private static Map<String, Long> counts() {
        Map<String, Long> values = new java.util.LinkedHashMap<>();
        for (String table : List.of("users", "user_profiles", "user_identity_bindings", "workspaces", "workspace_memberships")) {
            values.put(table, sql.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return values;
    }

    private static void assertDelta(Map<String, Long> before, int created) {
        Map<String, Long> after = counts();
        before.forEach((table, count) -> assertThat(after.get(table)).as(table)
                .isEqualTo(count + (table.startsWith("workspace") ? 0 : created)));
    }

    private static Identity identity() { return new Identity("https://issuer.example/", UUID.randomUUID().toString()); }
    private static Plan observe(Plan plan) { observed.plan = plan; return plan; }
    private static Duration elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start); }
    private static Object outcome(Identity identity) {
        try { return auth.resolve(identity, ignored -> true); }
        catch (RuntimeException failure) { return failure; }
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        org.awaitility.Awaitility.await().pollInterval(Duration.ofMillis(10))
                .atMost(Duration.ofSeconds(seconds)).until(condition::getAsBoolean);
    }

    private static void awaitLatch(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) { throw new SQLException("test release deadline"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SQLException("test interrupted", failure);
        }
    }

    private static PSQLException findPostgres(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PSQLException pg) { return pg; }
        }
        throw new AssertionError("No real PostgreSQL exception", failure);
    }

    private static void assertMetadata(Throwable failure, String state, String table, String constraint) {
        PSQLException pg = findPostgres(failure);
        assertThat(pg.getSQLState()).isEqualTo(state);
        assertThat(pg.getServerErrorMessage()).isNotNull();
        assertThat(pg.getServerErrorMessage().getSchema()).isEqualTo("public");
        assertThat(pg.getServerErrorMessage().getTable()).isEqualTo(table);
        assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }

    private static class Plan {
        final List<Session> sessions = new CopyOnWriteArrayList<>();
        void beforeStatement(Session session, String statement) throws SQLException { }
        void afterInsert(Session session) throws SQLException { }
        void afterFind(Session session) throws SQLException { }
        void afterCommit(Session session) throws SQLException { }
    }

    private record Snapshot(int pid, String xid, String isolation) { }

    private static final class Session {
        final Connection connection;
        final List<Snapshot> inserts = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> findTransactions = new CopyOnWriteArrayList<>();
        volatile int pid;
        int finds;
        Session(Connection connection) { this.connection = connection; }
        Snapshot snapshot() throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT pg_backend_pid(), pg_current_xact_id()::text, current_setting('transaction_isolation')")) {
                rows.next();
                pid = rows.getInt(1);
                return new Snapshot(pid, rows.getString(2), rows.getString(3));
            }
        }
    }

    /** Test-only observations/injected failures; every required SQL/commit executes on real PG. */
    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        volatile Plan plan = new Plan();
        ObservedDataSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }
        private Connection wrap(Connection connection) {
            Plan currentPlan = plan;
            Session session = new Session(connection);
            currentPlan.sessions.add(session);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String statement) {
                            PreparedStatement prepared = (PreparedStatement) invoke(connection, method, arguments);
                            return wrapStatement(prepared, statement, session, currentPlan);
                        }
                        Object value = invoke(connection, method, arguments);
                        if (method.getName().equals("commit")) {
                            session.events.add("commit");
                            currentPlan.afterCommit(session); // physical commit has ALREADY returned
                        } else if (method.getName().equals("rollback") && (arguments == null || arguments.length == 0)) {
                            session.events.add("rollback"); // emitted only after actual rollback
                        }
                        return value;
                    });
        }
        private PreparedStatement wrapStatement(PreparedStatement prepared, String statement, Session session, Plan currentPlan) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        boolean execute = method.getName().startsWith("execute");
                        boolean find = statement.contains("FROM user_identity_bindings b");
                        boolean insert = statement.startsWith("INSERT INTO users ")
                                || statement.startsWith("INSERT INTO user_profiles ")
                                || statement.startsWith("INSERT INTO user_identity_bindings ");
                        if (execute && (find || insert)) {
                            Snapshot snapshot = session.snapshot();
                            if (find) { session.findTransactions.add(snapshot.xid()); }
                            currentPlan.beforeStatement(session, statement);
                        }
                        Object value;
                        try { value = invoke(prepared, method, arguments); }
                        catch (PSQLException failure) {
                            if ("23505".equals(failure.getSQLState()) && failure.getServerErrorMessage() != null
                                    && "user_identity_bindings_pk".equals(failure.getServerErrorMessage().getConstraint())) {
                                session.events.add("pair-conflict");
                            }
                            throw failure;
                        }
                        if (execute && insert) {
                            session.inserts.add(session.snapshot());
                            session.events.add("insert:" + statement.split(" ")[2]);
                            currentPlan.afterInsert(session);
                        }
                        if (execute && find) {
                            session.finds++;
                            session.events.add("find");
                            currentPlan.afterFind(session);
                        }
                        return value;
                    });
        }
        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try { return method.invoke(target, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
