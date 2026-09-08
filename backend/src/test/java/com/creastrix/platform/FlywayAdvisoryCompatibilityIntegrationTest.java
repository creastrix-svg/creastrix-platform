package com.creastrix.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Isolated compatibility evidence, not a replacement for production locking or independent review. */
@Testcontainers
@Timeout(120)
class FlywayAdvisoryCompatibilityIntegrationTest {

    private static final String INJECTED_FAILURE = "COMPATIBILITY_PROOF_AFTER_REAL_V9_HISTORY_INSERT";
    private static final Pattern TRY_LOCK = Pattern.compile("pg_try_advisory_lock\\(\\s*(-?\\d+)\\s*\\)");
    private static final Pattern UNLOCK = Pattern.compile("pg_advisory_unlock\\(\\s*(-?\\d+)\\s*\\)");

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("flyway_advisory_" + UUID.randomUUID().toString().replace("-", ""));

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void initializeOwnedV8Database() {
        assertThat(postgres.isRunning()).isTrue();
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "50");
        source.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(40);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(postgres.getDatabaseName());
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isEqualTo(180004);
        assertThat(configure(source, "8").migrate().migrationsExecuted).isEqualTo(8);
        UUID user = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO public.users (id, status) VALUES (?, 'ACTIVE')", user);
            jdbc.update("INSERT INTO public.user_profiles (user_id) VALUES (?)", user);
        });
    }

    @RepeatedTest(3)
    void sessionAdvisoryLockSerializesTwoMigratorsAndIsExplicitlyReleasedAfterSuccess() throws Exception {
        proveTwoMigrators(false);
    }

    @RepeatedTest(3)
    void sessionAdvisoryLockIsExplicitlyReleasedAfterHistoryInsertFaultAndWaitingMigratorResumes() throws Exception {
        proveTwoMigrators(true);
    }

    private void proveTwoMigrators(boolean failFirstAfterHistory) throws Exception {
        List<String> beforeUsers = jdbc.queryForList("SELECT row_to_json(u)::text FROM public.users u ORDER BY id", String.class);
        List<String> beforeProfiles = jdbc.queryForList("SELECT row_to_json(p)::text FROM public.user_profiles p ORDER BY user_id", String.class);
        Trace first = new Trace("first", failFirstAfterHistory, true);
        Trace second = new Trace("second", false, false);
        first.other = second;
        second.other = first;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Outcome> firstFuture = null;
        Future<Outcome> secondFuture = null;
        try {
            firstFuture = executor.submit(() -> migrateObserved(first));
            await(first.acquired, "first actual successful pg_try_advisory_lock result");
            assertThat(first.pid.get()).isPositive();
            assertExactHolder(first.pid.get(), first.key.get());
            secondFuture = executor.submit(() -> migrateObserved(second));
            await(second.contended, "second actual false pg_try_advisory_lock result");
            assertThat(second.failedTryPid.get()).isPositive().isNotEqualTo(first.pid.get());
            assertThat(second.failedTryKey.get()).isEqualTo(first.key.get());
            assertExactHolder(first.pid.get(), second.failedTryKey.get());
            assertThat(lockCount(second.failedTryPid.get(), second.failedTryKey.get())).isZero();
            assertThat(firstFuture.isDone()).isFalse();
            assertThat(secondFuture.isDone()).isFalse();
            assertThat(first.historyInserted.get()).isFalse();
            assertThat(second.historyInserted.get()).isFalse();
            // Flyway uses nonblocking try-lock/retry: this proves same-key contention,
            // not a fictitious pg_blocking_pids wait or a time-based inference.
            System.out.printf("ADVISORY_CONTENTION fault=%s holder_pid=%d contender_pid=%d key=%d actual_try_result=false holder_pg_locks=1%n",
                    failFirstAfterHistory, first.pid.get(), second.failedTryPid.get(), first.key.get());
            first.release.countDown();
            Outcome firstResult = firstFuture.get(60, TimeUnit.SECONDS);
            Outcome secondResult = secondFuture.get(60, TimeUnit.SECONDS);
            if (failFirstAfterHistory) {
                assertThat(firstResult.failure()).isNotNull();
                assertThat(causeMessages(firstResult.failure())).contains(INJECTED_FAILURE);
                assertThat(first.historyInserted.get()).isTrue();
                assertThat(first.rolledBackAfterHistory.get()).isTrue();
                assertThat(first.committedAfterHistory.get()).isFalse();
                assertThat(second.observedPriorV9History.get()).isZero();
                assertThat(second.observedPriorFunction.get()).isZero();
                assertThat(second.observedPriorGuards.get()).isZero();
                assertThat(secondResult.failure()).isNull();
                assertThat(secondResult.result().migrationsExecuted).isOne();
            } else {
                assertThat(firstResult.failure()).isNull();
                assertThat(firstResult.result().migrationsExecuted).isOne();
                assertThat(first.committedAfterHistory.get()).isTrue();
                assertThat(second.observedPriorV9History.get()).isOne();
                assertThat(second.observedPriorFunction.get()).isOne();
                assertThat(second.observedPriorGuards.get()).isEqualTo(4);
                assertThat(secondResult.failure()).isNull();
                assertThat(secondResult.result().migrationsExecuted).isZero();
            }
            first.assertExplicitRelease();
            second.assertExplicitRelease();
            assertThat(first.lockHeldAfterTransactionEnd.get()).isTrue();
            assertThat(second.pid.get()).isEqualTo(second.failedTryPid.get());
            assertThat(second.key.get()).isEqualTo(first.key.get());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='9' AND success", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='9'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND database=(SELECT oid FROM pg_database WHERE datname=current_database())", Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT row_to_json(u)::text FROM public.users u ORDER BY id", String.class)).isEqualTo(beforeUsers);
            assertThat(jdbc.queryForList("SELECT row_to_json(p)::text FROM public.user_profiles p ORDER BY user_id", String.class)).isEqualTo(beforeProfiles);
            assertThat(configure(source, "9").migrate().migrationsExecuted).as("ordinary fresh retry sees installed V9").isZero();
            System.out.printf("ADVISORY_COMPLETION fault=%s first_trace=%s second_trace=%s first_history_rollback=%s waiting_migrator_executed=%d retry_executed=0 users_and_profiles_preserved=true%n",
                    failFirstAfterHistory, first.events, second.events, first.rolledBackAfterHistory.get(), secondResult.result().migrationsExecuted);
        } finally {
            first.release.countDown();
            second.release.countDown();
            executor.shutdown();
            boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            if (!terminated) {
                executor.shutdownNow();
                terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            }
            assertThat(terminated).as("owned migrator executor terminated").isTrue();
        }
    }

    private static Flyway configure(DataSource dataSource, String target) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).executeInTransaction(true).group(false).lockRetryCount(30)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load();
        assertThat(flyway.getConfiguration().isExecuteInTransaction()).isTrue();
        assertThat(flyway.getConfiguration().isGroup()).isFalse();
        return flyway;
    }

    private Outcome migrateObserved(Trace trace) {
        try {
            return new Outcome(configure(new ObservedSource(source, trace), "9").migrate(), null);
        } catch (Throwable failure) {
            return new Outcome(null, failure);
        }
    }

    private record Outcome(MigrateResult result, Throwable failure) {
    }

    private long lockCount(int pid, long key) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks
                 WHERE locktype='advisory' AND granted AND pid=?
                   AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                   AND classid::bigint=? AND objid::bigint=? AND objsubid=1
                """, Long.class, pid, (key >>> 32) & 0xffffffffL, key & 0xffffffffL);
    }

    private void assertExactHolder(int pid, long key) {
        assertThat(lockCount(pid, key)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE pid=? AND datid=(SELECT oid FROM pg_database WHERE datname=current_database())
                """, Integer.class, pid)).isOne();
    }

    private static void await(CountDownLatch gate, String reason) throws InterruptedException {
        assertThat(gate.await(20, TimeUnit.SECONDS)).as(reason).isTrue();
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private final class Trace {
        private final String name;
        private final boolean injectHistoryFault;
        private final boolean holdFirstAcquisition;
        private final CountDownLatch acquired = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch contended = new CountDownLatch(1);
        private final CountDownLatch unlockObserved = new CountDownLatch(1);
        private final AtomicInteger pid = new AtomicInteger();
        private final AtomicLong key = new AtomicLong();
        private final AtomicInteger failedTryPid = new AtomicInteger();
        private final AtomicLong failedTryKey = new AtomicLong();
        private final AtomicBoolean historyInserted = new AtomicBoolean();
        private final AtomicBoolean rolledBackAfterHistory = new AtomicBoolean();
        private final AtomicBoolean committedAfterHistory = new AtomicBoolean();
        private final AtomicBoolean lockHeldAfterTransactionEnd = new AtomicBoolean();
        private final AtomicBoolean explicitlyUnlockedAlive = new AtomicBoolean();
        private final AtomicInteger observedPriorV9History = new AtomicInteger(-1);
        private final AtomicInteger observedPriorFunction = new AtomicInteger(-1);
        private final AtomicInteger observedPriorGuards = new AtomicInteger(-1);
        private final List<String> events = new CopyOnWriteArrayList<>();
        private Trace other;

        private Trace(String name, boolean injectHistoryFault, boolean holdFirstAcquisition) {
            this.name = name;
            this.injectHistoryFault = injectHistoryFault;
            this.holdFirstAcquisition = holdFirstAcquisition;
        }

        private void actualBooleanResult(Connection raw, String sql, boolean value) throws Exception {
            Matcher tryLock = TRY_LOCK.matcher(sql);
            if (tryLock.find()) {
                long actualKey = Long.parseLong(tryLock.group(1));
                int actualPid = raw.unwrap(PGConnection.class).getBackendPID();
                events.add("try:" + actualPid + ":" + actualKey + ":" + value);
                if (!value && failedTryPid.compareAndSet(0, actualPid)) {
                    failedTryKey.set(actualKey);
                    assertExactHolder(other.pid.get(), actualKey);
                    contended.countDown();
                } else if (value && pid.compareAndSet(0, actualPid)) {
                    key.set(actualKey);
                    assertExactHolder(actualPid, actualKey);
                    if (!holdFirstAcquisition) {
                        await(other.unlockObserved, "first observed explicit unlock while still alive");
                        assertThat(other.explicitlyUnlockedAlive.get()).isTrue();
                        observedPriorV9History.set(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='9'", Integer.class));
                        observedPriorFunction.set(jdbc.queryForObject("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='foundation_require_read_committed'", Integer.class));
                        observedPriorGuards.set(jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND t.tgname IN ('organizations_require_read_committed','organization_memberships_require_read_committed','workspaces_require_read_committed','workspace_memberships_require_read_committed')", Integer.class));
                    }
                    acquired.countDown();
                    if (holdFirstAcquisition) {
                        await(release, "release first real session advisory lock holder");
                    }
                }
            }
            Matcher unlock = UNLOCK.matcher(sql);
            if (unlock.find()) {
                long actualKey = Long.parseLong(unlock.group(1));
                assertThat(value).as("actual PostgreSQL pg_advisory_unlock result").isTrue();
                assertThat(actualKey).isEqualTo(key.get());
                assertThat(raw.isClosed()).isFalse();
                assertThat(raw.unwrap(PGConnection.class).getBackendPID()).isEqualTo(pid.get());
                assertThat(lockCount(pid.get(), actualKey)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid=? AND datid=(SELECT oid FROM pg_database WHERE datname=current_database())", Integer.class, pid.get())).isOne();
                explicitlyUnlockedAlive.set(true);
                events.add("explicit-unlock-live:" + pid.get());
                unlockObserved.countDown();
            }
        }

        private void afterExecute(Connection raw, String sql, Object result) throws SQLException {
            if (sql.matches("(?is).*INSERT\\s+INTO\\s+.*flyway_schema_history.*")
                    && historyInserted.compareAndSet(false, true)) {
                assertThat(raw.getAutoCommit()).isFalse();
                assertThat(raw.unwrap(PGConnection.class).getBackendPID()).isEqualTo(pid.get());
                assertThat(result).as("actual history INSERT JDBC update count").isEqualTo(1);
                events.add("actual-history-insert:" + pid.get());
                if (injectHistoryFault) {
                    // The genuine JDBC execution completed. Inject only at its return boundary;
                    // no SQL/result replacement and no observer-controlled migration commit.
                    throw new SQLException(INJECTED_FAILURE, "P0001");
                }
            }
        }

        private void transactionEnded(Connection raw, String action) throws SQLException {
            if (historyInserted.get() && raw.unwrap(PGConnection.class).getBackendPID() == pid.get()
                    && !committedAfterHistory.get() && !rolledBackAfterHistory.get()) {
                if (action.equals("commit")) {
                    committedAfterHistory.set(true);
                } else {
                    rolledBackAfterHistory.set(true);
                }
                assertExactHolder(pid.get(), key.get());
                lockHeldAfterTransactionEnd.set(true);
                events.add("actual-" + action + "-session-lock-still-held:" + pid.get());
            }
        }

        private void assertExplicitRelease() {
            assertThat(explicitlyUnlockedAlive.get()).as(name + " explicitly unlocked before physical connection close").isTrue();
            assertThat(events).anyMatch(event -> event.startsWith("explicit-unlock-live:"));
        }
    }

    /** Transparent JDBC observation; only bounded gates and one explicit post-execution fault. */
    private final class ObservedSource extends AbstractDataSource {
        private final DataSource delegate;
        private final Trace trace;

        private ObservedSource(DataSource delegate, Trace trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(delegate.getConnection(username, password));
        }

        private Connection observe(Connection raw) throws SQLException {
            try (Statement limits = raw.createStatement()) {
                limits.execute("SET lock_timeout='30s'");
                limits.execute("SET statement_timeout='40s'");
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        Object result = invoke(raw, method, args);
                        if ((method.getName().equals("commit") || method.getName().equals("rollback"))
                                && (args == null || args.length == 0)) {
                            trace.transactionEnded(raw, method.getName());
                        }
                        if (result instanceof Statement statement) {
                            String preparedSql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                            Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {type},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        String sql = statementMethod.getName().startsWith("execute") && statementArgs != null
                                                && statementArgs.length > 0 && statementArgs[0] instanceof String text ? text : preparedSql;
                                        Object statementResult = invoke(statement, statementMethod, statementArgs);
                                        if (sql != null && statementMethod.getName().startsWith("execute")) {
                                            trace.afterExecute(raw, sql, statementResult);
                                        }
                                        if (statementResult instanceof ResultSet rows && sql != null) {
                                            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                                                    (resultProxy, resultMethod, resultArgs) -> {
                                                        Object actual = invoke(rows, resultMethod, resultArgs);
                                                        if (resultMethod.getName().equals("getBoolean") && actual instanceof Boolean value) {
                                                            trace.actualBooleanResult(raw, sql, value);
                                                        }
                                                        return actual;
                                                    });
                                        }
                                        return statementResult;
                                    });
                        }
                        return result;
                    });
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
