package com.creastrix.platform.platformaccess;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.creastrix.platform.platformaccess.PlatformAccessTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Isolated storage acceptance, not employee-service admission or raw-SQL actor authorization.
 * Each case owns a new database in this class's new Testcontainer; SQL fixtures are deliberately privileged.
 */
@Testcontainers
@Timeout(120)
class PlatformAccessStorageIntegrationTest {
    private static final List<String> PRIOR_TABLES = List.of("users", "user_profiles", "organizations",
            "organization_memberships", "workspaces", "workspace_memberships", "workspace_membership_scopes",
            "ready_made_products", "ready_made_product_manual_quantity_delta_commands", "user_identity_bindings");

    @org.junit.jupiter.params.ParameterizedTest(name = "populatedV11={0}, failAfterActualV12History={1}")
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void v12ValidationSchemaGenerationAndHistoryShareOnePhysicalTransaction(boolean populated, boolean fail)
            throws Exception {
        DataSource source = database(POSTGRES);
        Map<String, List<String>> before;
        List<String> history = List.of();
        if (populated) {
            flyway(source, "11").migrate();
            populatePriorDomains(source);
            try (Connection c = source.getConnection()) {
                before = rows(c, PRIOR_TABLES, false);
                assertThat(before.values()).allSatisfy(value -> assertThat(value).isNotEmpty());
                history = strings(c, "SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank");
            }
        } else {
            before = new java.util.LinkedHashMap<>();
            PRIOR_TABLES.forEach(table -> before.put(table, List.of()));
        }
        V12Trace trace = new V12Trace(source, fail);
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> flyway(trace.observed(), "12").migrate());
        trace.verify(fail);
        if (fail) {
            assertThat(failure).isNotNull();
            Throwable cause = failure;
            while (cause != null && cause != trace.injected) { cause = cause.getCause(); }
            assertThat(cause).as("exact injected fault, not setup failure").isSameAs(trace.injected);
        } else { assertThat(failure).isNull(); }
        try (Connection c = source.getConnection()) {
            assertThat(rows(c, PRIOR_TABLES, !fail)).isEqualTo(before);
            assertV12Installed(c, !fail);
            if (populated) {
                assertThat(strings(c, "SELECT to_jsonb(h)::text FROM flyway_schema_history h "
                        + "WHERE version <> '12' ORDER BY installed_rank")).isEqualTo(history);
            }
        }
        if (fail) {
            V12Trace retry = new V12Trace(source, false);
            flyway(retry.observed(), "12").migrate();
            retry.verify(false);
            try (Connection c = source.getConnection()) {
                assertV12Installed(c, true);
                assertThat(rows(c, PRIOR_TABLES, true)).isEqualTo(before);
            }
        }
        System.out.printf("V12_MIGRATION_CASE populated=%s injectedAfterRealHistory=%s preserved=true%n", populated, fail);
    }

    @Test
    void migrationWaitsForTheExactUserWriterBeforeValidationAndBackfill() throws Exception {
        DataSource source = database(POSTGRES);
        flyway(source, "11").migrate();
        UUID user = tx(source, PlatformAccessTestSupport::user);
        V12Trace trace = new V12Trace(source, false);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection writer = source.getConnection()) {
            writer.setAutoCommit(false);
            execute(writer, "UPDATE users SET status='SUSPENDED' WHERE id=?", user);
            int blocker = pid(writer);
            var migration = executor.submit(() -> flyway(trace.observed(), "12").migrate());
            try {
                assertThat(trace.barrierAttempt.await(8, TimeUnit.SECONDS)).isTrue();
                awaitBlocked(source, trace.migrationPid.get(), blocker);
                assertThat(migration.isDone()).isFalse();
                writer.commit();
                assertThat(migration.get(40, TimeUnit.SECONDS).migrationsExecuted).isEqualTo(1);
            } finally { writer.rollback(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        trace.verify(false);
        try (Connection c = source.getConnection()) {
            assertThat(scalar(c, "SELECT status || ':' || account_eligibility_generation FROM users WHERE id=?",
                    String.class, user)).isEqualTo("SUSPENDED:1");
            assertV12Installed(c, true);
        }
    }

    @Test
    void unsupportedUserDescendantStopsMigrationWithoutPartialSchema() throws Exception {
        DataSource source = database(POSTGRES);
        flyway(source, "11").migrate();
        try (Connection c = source.getConnection()) { execute(c, "CREATE TABLE users_child () INHERITS (users)"); }
        postgresFailure(catchThrowable(() -> flyway(source, "12").migrate()), "55000", null);
        try (Connection c = source.getConnection()) { assertV12Installed(c, false); }
    }

    private static void populatePriorDomains(DataSource source) throws Exception {
        tx(source, c -> {
            UUID owner = user(c);
            UUID second = user(c);
            UUID organization = UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            execute(c, "INSERT INTO organizations(id) VALUES (?)", organization);
            execute(c, "INSERT INTO organization_memberships VALUES (?,?,'OWNER','ACTIVE')", organization, owner);
            execute(c, "INSERT INTO workspaces VALUES (?,'ORGANIZATION',NULL,?)", workspace, organization);
            execute(c, "INSERT INTO workspace_memberships VALUES (?,?,'ADMIN','ACTIVE')", workspace, owner);
            execute(c, "INSERT INTO workspace_membership_scopes VALUES (?,?,'READY_MADE_PRODUCTS')", workspace, owner);
            execute(c, "INSERT INTO ready_made_products VALUES (?,?,?,'ACTIVE',17)", product, workspace, owner);
            execute(c, "INSERT INTO ready_made_product_manual_quantity_delta_commands"
                    + "(product_id,command_id,delta,state) VALUES (?,?,3,'REGISTERED')", product, UUID.randomUUID());
            execute(c, "INSERT INTO user_identity_bindings VALUES ('https://synthetic.invalid','prior-bound-user',?)", second);
            execute(c, "UPDATE users SET status='SUSPENDED' WHERE id=?", second);
            return null;
        });
    }

    private static void assertV12Installed(Connection c, boolean installed) throws Exception {
        List<String> expected = new java.util.ArrayList<>(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"));
        if (installed) { expected.add("12"); }
        assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"))
                .containsExactlyElementsOf(expected);
        assertThat(scalar(c, "SELECT count(*)::integer FROM pg_attribute WHERE attrelid='users'::regclass "
                + "AND attname='account_eligibility_generation' AND NOT attisdropped", Integer.class))
                .isEqualTo(installed ? 1 : 0);
        for (String table : TABLES) {
            assertThat(scalar(c, "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table)).isEqualTo(installed);
            if (installed) { assertThat(scalar(c, "SELECT count(*)::integer FROM " + table, Integer.class)).isZero(); }
        }
        for (String name : List.of("users_enforce_initial_generation", "users_reject_generation_assignment",
                "users_increment_eligibility_generation")) {
            assertThat(scalar(c, "SELECT count(*)::integer FROM pg_trigger WHERE tgname=?", Integer.class, name))
                    .isEqualTo(installed ? 1 : 0);
            assertThat(scalar(c, "SELECT to_regprocedure(?) IS NOT NULL", Boolean.class, "public." + name + "()"))
                    .isEqualTo(installed);
        }
        if (installed) {
            assertThat(scalar(c, "SELECT count(*)::integer FROM users WHERE account_eligibility_generation<>1", Integer.class)).isZero();
        }
    }

    private record MigrationPoint(String event, int pid, String vxid, String xid, boolean autoCommit) { }

    /** Observes real driver calls; never replaces SQL/results or invokes migration commit itself. */
    private static final class V12Trace {
        private final DataSource source;
        private final boolean fail;
        private final SQLException injected = new SQLException("PLATFORM_ACCESS_AFTER_REAL_V12_HISTORY_INSERT", "XX000");
        private final List<MigrationPoint> points = new java.util.ArrayList<>();
        private final List<String> violations = new java.util.ArrayList<>();
        private Connection migrating;
        private final CountDownLatch barrierAttempt = new CountDownLatch(1);
        private final AtomicInteger migrationPid = new AtomicInteger();
        private boolean terminal;
        private boolean historyObserved;
        private int ddl;

        private V12Trace(DataSource source, boolean fail) { this.source = source; this.fail = fail; }

        private DataSource observed() {
            return new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException { return observe(source.getConnection()); }
                @Override public Connection getConnection(String u, String p) throws SQLException {
                    return observe(source.getConnection(u, p));
                }
            };
        }

        private Connection observe(Connection raw) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        boolean tracked = raw == migrating && !terminal;
                        String name = method.getName();
                        if (tracked && (name.equals("close")
                                || (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])))) {
                            violations.add(name);
                        }
                        if (tracked && (name.equals("commit") || name.equals("rollback"))) { point(raw, name); }
                        Object result = invoke(raw, method, args);
                        if (tracked && (name.equals("commit") || name.equals("rollback"))) { terminal = true; }
                        if (!(result instanceof java.sql.Statement statement)) { return result; }
                        String prepared = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                        Class<?> kind = statement instanceof java.sql.PreparedStatement
                                ? java.sql.PreparedStatement.class : java.sql.Statement.class;
                        return java.lang.reflect.Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[] {kind},
                                (sp, sm, sa) -> {
                                    String sql = sa != null && sa.length > 0 && sa[0] instanceof String s ? s : prepared;
                                    boolean execute = sm.getName().startsWith("execute") && sql != null;
                                    if (execute && migrating == null && sql.contains("LOCK TABLE ONLY public.users")) {
                                        migrating = raw; point(raw, "barrier-before");
                                        migrationPid.set(pid(raw));
                                        barrierAttempt.countDown();
                                    }
                                    Object actual = invoke(statement, sm, sa);
                                    if (execute && raw == migrating && !terminal) { after(raw, sql); }
                                    return actual;
                                });
                    });
        }

        private void after(Connection raw, String sql) throws Exception {
            if (sql.contains("LOCK TABLE ONLY public.users")) { point(raw, "barrier-acquired"); }
            if (sql.contains("CREASTRIX_PLATFORM_ACCESS_MIGRATION_VALIDATION_V1")) { point(raw, "validation"); }
            if (sql.contains("ADD COLUMN account_eligibility_generation")) { point(raw, "generation-ddl"); }
            if (sql.matches("(?is).*CREATE\\s+(TABLE|FUNCTION|TRIGGER|CONSTRAINT\\s+TRIGGER)\\s+.*")) {
                point(raw, "ddl-" + ++ddl);
            }
            if (sql.matches("(?is).*INSERT\\s+INTO\\s+.*flyway_schema_history.*")) {
                assertThat(scalar(raw, "SELECT count(*)::integer FROM flyway_schema_history WHERE version='12' AND success", Integer.class)).isOne();
                assertThat(scalar(raw, "SELECT count(*)::integer FROM users WHERE account_eligibility_generation<>1", Integer.class)).isZero();
                historyObserved = true;
                point(raw, "real-history-insert");
                try (Connection observer = source.getConnection()) {
                    assertThat(pid(observer)).isNotEqualTo(pid(raw));
                    assertThat(scalar(observer, "SELECT count(*)::integer FROM flyway_schema_history WHERE version='12'", Integer.class)).isZero();
                    assertThat(scalar(observer, "SELECT count(*)::integer FROM pg_attribute WHERE attrelid='users'::regclass "
                            + "AND attname='account_eligibility_generation' AND NOT attisdropped", Integer.class)).isZero();
                    for (String table : TABLES) {
                        assertThat(scalar(observer, "SELECT to_regclass(?) IS NULL", Boolean.class, "public." + table)).isTrue();
                    }
                    System.out.printf("V12_UNCOMMITTED_VISIBILITY migrator=%d observer=%d history=false schema=false%n", pid(raw), pid(observer));
                }
                if (fail) { throw injected; }
            }
        }

        private void point(Connection c, String event) throws SQLException {
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT pg_backend_pid(), "
                    + "(SELECT virtualtransaction FROM pg_locks WHERE pid=pg_backend_pid() "
                    + "AND locktype='virtualxid' AND granted), pg_current_xact_id_if_assigned()::text, "
                    + "current_setting('transaction_isolation')")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(4)).isEqualTo("read committed");
                points.add(new MigrationPoint(event, r.getInt(1), r.getString(2), r.getString(3), c.getAutoCommit()));
            }
        }

        private void verify(boolean rollback) {
            System.out.printf("V12_PHYSICAL_TRANSACTION rollback=%s points=%s violations=%s%n", rollback, points, violations);
            assertThat(historyObserved).isTrue();
            assertThat(terminal).isTrue();
            assertThat(violations).isEmpty();
            assertThat(points.stream().map(MigrationPoint::event)).contains("barrier-before", "barrier-acquired", "validation", "generation-ddl", "real-history-insert")
                    .endsWith(rollback ? "rollback" : "commit");
            assertThat(ddl).isGreaterThanOrEqualTo(5);
            assertThat(points.stream().map(MigrationPoint::pid).distinct()).hasSize(1);
            assertThat(points.stream().map(MigrationPoint::vxid).distinct()).hasSize(1);
            assertThat(points).allSatisfy(p -> { assertThat(p.vxid()).isNotBlank(); assertThat(p.autoCommit()).isFalse(); });
            assertThat(points.stream().map(MigrationPoint::xid).filter(java.util.Objects::nonNull).distinct()).hasSize(1);
            assertThat(points.stream().filter(p -> p.event().startsWith("ddl-") || p.event().equals("real-history-insert")))
                    .allSatisfy(p -> assertThat(p.xid()).isNotBlank());
        }

        private static Object invoke(Object receiver, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(receiver, args); }
            catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private DataSource latest() throws SQLException {
        DataSource source = database(POSTGRES);
        flyway(source, "12").migrate();
        return source;
    }

    @ParameterizedTest(name = "owner={0}, targets={1}")
    @CsvSource({"true,0", "false,1", "false,100"})
    void exactZeroOneAndHundredTargetScopesCommit(boolean owner, int count) throws Exception {
        DataSource source = latest();
        UUID grant = tx(source, connection -> {
            UUID actor = user(connection);
            List<UUID> targets = new ArrayList<>();
            for (int index = 0; index < count; index++) { targets.add(user(connection)); }
            return grant(connection, actor, owner, targets, TIME);
        });
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT scope_target_count FROM platform_access_grants WHERE id=?",
                    Integer.class, grant)).isEqualTo(count);
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_grant_targets WHERE grant_id=?",
                    Long.class, grant)).isEqualTo((long) count);
            assertThat(scalar(observer, "SELECT state || ':' || revision FROM platform_access_grants WHERE id=?",
                    String.class, grant)).isEqualTo("ACTIVE:1");
        }
    }

    @ParameterizedTest(name = "prior state={0}, expired={1}, inactive recipient={2}")
    @CsvSource({"ACTIVE,false,false", "SUSPENDED,false,false", "ACTIVE,true,false", "ACTIVE,false,true"})
    void allNonrevokedGrantsOccupyTheImmediateRecipientSlot(String state, boolean expired, boolean inactive)
            throws Exception {
        DataSource source = latest();
        UUID[] ids = tx(source, connection -> {
            UUID recipient = user(connection);
            UUID target = user(connection);
            Instant now = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            UUID first = grant(connection, recipient, false, List.of(target), expired ? now.minusSeconds(864000) : now);
            assertThat(scalar(connection, "SELECT expires_at < clock_timestamp() FROM platform_access_grants WHERE id=?",
                    Boolean.class, first)).isEqualTo(expired);
            if (!state.equals("ACTIVE")) { transition(connection, first, state, recipient); }
            if (inactive) { execute(connection, "UPDATE users SET status='SUSPENDED' WHERE id=?", recipient); }
            return new UUID[] {recipient, target, first};
        });
        Throwable failure = catchThrowable(() -> tx(source, connection ->
                grant(connection, ids[0], false, List.of(ids[1]), TIME)));
        postgresFailure(failure, "23505", "platform_access_grants_single_nonrevoked");
        try (Connection observer = source.getConnection()) {
            assertThat(strings(observer, "SELECT id::text FROM platform_access_grants WHERE recipient_user_id=?", ids[0]))
                    .containsExactly(ids[2].toString());
        }
        tx(source, connection -> { transition(connection, ids[2], "REVOKED", ids[0]); return null; });
        UUID replacement = tx(source, connection -> grant(connection, ids[0], false, List.of(ids[1]), TIME));
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_grants WHERE recipient_user_id=?",
                    Long.class, ids[0])).isEqualTo(2L);
            assertThat(scalar(observer, "SELECT state FROM platform_access_grants WHERE id=?", String.class, ids[2]))
                    .isEqualTo("REVOKED");
            assertThat(scalar(observer, "SELECT id FROM platform_access_grants WHERE recipient_user_id=? AND state <> 'REVOKED'",
                    UUID.class, ids[0])).isEqualTo(replacement);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED"})
    void concurrentSlotRivalWaitsForExactBlockerAndCannotOverwriteWinner(String winnerState) throws Exception {
        DataSource source = latest();
        UUID[] users = tx(source, connection -> new UUID[] {user(connection), user(connection)});
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger rivalPid = new AtomicInteger();
        UUID winner;
        try (Connection first = source.getConnection()) {
            first.setAutoCommit(false);
            int blocker = pid(first);
            winner = grant(first, users[0], false, List.of(users[1]), TIME);
            if (winnerState.equals("SUSPENDED")) { transition(first, winner, winnerState, users[0]); }
            var rival = executor.submit(() -> catchThrowable(() -> tx(source, second -> {
                rivalPid.set(pid(second));
                started.countDown();
                return grant(second, users[0], false, List.of(users[1]), TIME);
            })));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlocked(source, rivalPid.get(), blocker);
                first.commit();
                postgresFailure(rival.get(15, TimeUnit.SECONDS), "23505", "platform_access_grants_single_nonrevoked");
            } finally {
                first.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        try (Connection observer = source.getConnection()) {
            assertThat(strings(observer, "SELECT id::text FROM platform_access_grants WHERE recipient_user_id=?", users[0]))
                    .containsExactly(winner.toString());
            assertThat(scalar(observer, "SELECT state FROM platform_access_grants WHERE id=?", String.class, winner))
                    .isEqualTo(winnerState);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void supportDeclaredBoundsFailAtDatabaseAndFixtureBoundary(int count) throws Exception {
        DataSource source = latest();
        UUID recipient = tx(source, PlatformAccessTestSupport::user);
        Throwable failure = catchThrowable(() -> tx(source, connection -> {
            insertGrant(connection, UUID.randomUUID(), recipient, false, count, TIME);
            return null;
        }));
        postgresFailure(failure, "23514", "platform_access_grants_scope_shape");
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) { ids.add(UUID.randomUUID()); }
        try (Connection connection = source.getConnection()) {
            assertThatThrownBy(() -> grant(connection, recipient, false, ids, TIME))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(scalar(connection, "SELECT count(*) FROM platform_access_grants", Long.class)).isZero();
        }
    }

    @Test
    void duplicateFixtureTargetsAreRejectedBeforeAnyNormalizationOrSql() throws Exception {
        DataSource source = latest();
        UUID target = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            assertThatThrownBy(() -> grant(connection, UUID.randomUUID(), false, List.of(target, target), TIME))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(scalar(connection, "SELECT count(*) FROM platform_access_grants", Long.class)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "DUPLICATE", "MISSING"})
    void rawTargetNullDuplicateAndMissingUserFailPrecisely(String input) throws Exception {
        DataSource source = latest();
        Throwable failure = catchThrowable(() -> tx(source, connection -> {
            UUID recipient = user(connection);
            UUID target = user(connection);
            UUID grant = UUID.randomUUID();
            insertGrant(connection, grant, recipient, false, 2, TIME);
            execute(connection, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)", grant, target);
            execute(connection, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)", grant,
                    input.equals("NULL") ? null : input.equals("DUPLICATE") ? target : UUID.randomUUID());
            return null;
        }));
        postgresFailure(failure, input.equals("NULL") ? "23502" : input.equals("DUPLICATE") ? "23505" : "23503",
                input.equals("NULL") ? null : input.equals("DUPLICATE") ? "platform_access_grant_targets_pk"
                        : "platform_access_grant_targets_user_fk");
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_grants", Long.class)).isZero();
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_grant_targets", Long.class)).isZero();
        }
    }

    @Test
    void exactTargetCountIsDeferredButCannotCommitAnIncompleteScope() throws Exception {
        DataSource source = latest();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            UUID recipient = user(connection);
            UUID grant = UUID.randomUUID();
            insertGrant(connection, grant, recipient, false, 2, TIME);
            execute(connection, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)",
                    grant, user(connection));
            assertThat(scalar(connection, "SELECT count(*) FROM platform_access_grant_targets WHERE grant_id=?",
                    Long.class, grant)).isOne();
            postgresFailure(catchThrowable(connection::commit), "23514", "platform_access_grant_targets_exact_count");
            connection.rollback();
        }
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT count(*) FROM platform_access_grants", Long.class)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE", "TRUNCATE"})
    void committedTargetsCannotBeMutatedOrRemoved(String operation) throws Exception {
        DataSource source = latest();
        UUID grant = tx(source, connection -> grant(connection, user(connection), false, List.of(user(connection)), TIME));
        Map<String, List<String>> before = snapshot(source);
        String sql = switch (operation) {
            case "UPDATE" -> "UPDATE platform_access_grant_targets SET target_user_id=target_user_id WHERE grant_id='" + grant + "'";
            case "DELETE" -> "DELETE FROM platform_access_grant_targets WHERE grant_id='" + grant + "'";
            default -> "TRUNCATE platform_access_grant_targets";
        };
        postgresFailure(catchThrowable(() -> tx(source, connection -> execute(connection, sql))), "23514",
                "platform_access_history_immutable");
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @Test
    void committedScopeCannotGrowAndConcurrentAdditionsSerializeOnExactParent() throws Exception {
        DataSource source = latest();
        UUID[] ids = tx(source, connection -> new UUID[] {
                grant(connection, user(connection), false, List.of(user(connection)), TIME), user(connection), user(connection)});
        Map<String, List<String>> before = snapshot(source);
        var executor = Executors.newSingleThreadExecutor();
        AtomicInteger waiterPid = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(1);
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid = pid(blocker);
            execute(blocker, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)", ids[0], ids[1]);
            var second = executor.submit(() -> catchThrowable(() -> tx(source, connection -> {
                waiterPid.set(pid(connection));
                attempted.countDown();
                return execute(connection, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)",
                        ids[0], ids[2]);
            })));
            try {
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlocked(source, waiterPid.get(), blockerPid);
                postgresFailure(catchThrowable(blocker::commit), "23514", "platform_access_grant_targets_exact_count");
                blocker.rollback();
                postgresFailure(second.get(15, TimeUnit.SECONDS), "23514", "platform_access_grant_targets_exact_count");
            } finally { blocker.rollback(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECIPIENT", "SCOPE_COUNT", "EXPIRY"})
    void immutableGrantAssignmentsRejectAnOtherwiseValidTransition(String field) throws Exception {
        DataSource source = latest();
        GrantMutationFixture fixture = grantMutationFixture(source);
        assertThat(fixture.alternate()).isNotEqualTo(fixture.recipient());
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT EXISTS (SELECT 1 FROM users WHERE id=?)", Boolean.class,
                    fixture.alternate())).isTrue();
        }
        String column = switch (field) {
            case "RECIPIENT" -> "recipient_user_id";
            case "SCOPE_COUNT" -> "scope_target_count";
            case "EXPIRY" -> "expires_at";
            default -> throw new IllegalArgumentException("Unknown synthetic immutable-field case");
        };
        Object replacement = switch (field) {
            case "RECIPIENT" -> fixture.alternate();
            case "SCOPE_COUNT" -> 2;
            case "EXPIRY" -> TIME.plusSeconds(86401);
            default -> throw new IllegalArgumentException("Unknown synthetic immutable-field case");
        };
        Map<String, List<String>> before = snapshot(source);
        // A valid lifecycle/revision/time change prevents the neighbouring lifecycle guard
        // from making this regression pass when the immutable-tuple guard is missing.
        postgresFailure(catchThrowable(() -> tx(source, connection -> execute(connection,
                "UPDATE platform_access_grants SET state='SUSPENDED',revision=revision+1,"
                        + "last_changed_by_user_id=?,last_changed_at=?," + column + "=? WHERE id=?",
                fixture.recipient(), TIME.plusSeconds(1), replacement, fixture.grant()))),
                "23514", "platform_access_grants_immutable_assignment");
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @ParameterizedTest(name = "state={0}, revisionStep={1}, guard={2}")
    @CsvSource({"ACTIVE,1,platform_access_grants_transition",
            "SUSPENDED,0,platform_access_grants_revision_step",
            "SUSPENDED,2,platform_access_grants_revision_step"})
    void grantNoopAndInvalidRevisionFailAtTheirOwnNamedGuard(String state, int step, String constraint)
            throws Exception {
        DataSource source = latest();
        GrantMutationFixture fixture = grantMutationFixture(source);
        Map<String, List<String>> before = snapshot(source);
        // Exact named failures distinguish these guards from neighbouring shape checks.
        postgresFailure(catchThrowable(() -> tx(source, connection -> execute(connection,
                "UPDATE platform_access_grants SET state=?,revision=revision+?,"
                        + "last_changed_by_user_id=?,last_changed_at=? WHERE id=?",
                state, step, fixture.recipient(), TIME.plusSeconds(1), fixture.grant()))), "23514", constraint);
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @Test
    void matchingLifecycleRevisionAndMetadataCommitWithoutImmutableAssignment() throws Exception {
        DataSource source = latest();
        GrantMutationFixture fixture = grantMutationFixture(source);
        Map<String, List<String>> before = snapshot(source);
        String immutableProjectionSql = "SELECT (to_jsonb(g) - ARRAY['state','revision',"
                + "'last_changed_by_user_id','last_changed_at'])::text FROM platform_access_grants g WHERE id=?";
        String immutableBefore;
        try (Connection observer = source.getConnection()) {
            immutableBefore = scalar(observer, immutableProjectionSql, String.class, fixture.grant());
        }
        // Positive control for the exact lifecycle/revision/metadata portion used above.
        int updated = tx(source, connection -> execute(connection,
                "UPDATE platform_access_grants SET state='SUSPENDED',revision=revision+1,"
                        + "last_changed_by_user_id=?,last_changed_at=? WHERE id=?",
                fixture.recipient(), TIME.plusSeconds(1), fixture.grant()));
        assertThat(updated).isEqualTo(1);
        try (Connection observer = source.getConnection()) {
            assertThat(scalar(observer, "SELECT state || ':' || revision FROM platform_access_grants WHERE id=?",
                    String.class, fixture.grant())).isEqualTo("SUSPENDED:2");
            assertThat(scalar(observer, "SELECT last_changed_by_user_id FROM platform_access_grants WHERE id=?",
                    UUID.class, fixture.grant())).isEqualTo(fixture.recipient());
            assertThat(scalar(observer, "SELECT last_changed_at FROM platform_access_grants WHERE id=?",
                    OffsetDateTime.class, fixture.grant()).toInstant()).isEqualTo(TIME.plusSeconds(1));
            assertThat(scalar(observer, immutableProjectionSql, String.class, fixture.grant())).isEqualTo(immutableBefore);
        }
        Map<String, List<String>> after = snapshot(source);
        assertThat(after.get("platform_access_grants")).hasSize(1);
        for (String table : TABLES) {
            if (!table.equals("platform_access_grants")) { assertThat(after.get(table)).isEqualTo(before.get(table)); }
        }
    }

    private record GrantMutationFixture(UUID grant, UUID recipient, UUID alternate) { }

    private GrantMutationFixture grantMutationFixture(DataSource source) throws Exception {
        return tx(source, connection -> {
            UUID recipient = user(connection);
            UUID alternate = user(connection);
            UUID grant = grant(connection, recipient, false, List.of(user(connection)), TIME);
            return new GrantMutationFixture(grant, recipient, alternate);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform_access_grants", "platform_access_grant_targets", "platform_access_operation_intents",
            "platform_access_operation_outcomes", "platform_access_audit_events"})
    void everyNewTableRejectsNonReadCommittedEvenForZeroRowWrites(String table) throws Exception {
        DataSource source = latest();
        for (int isolation : List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            try (Connection connection = source.getConnection()) {
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(false);
                PSQLException failure = postgresFailure(catchThrowable(() -> execute(connection,
                        "DELETE FROM " + table + " WHERE false")), "0A000", null);
                assertThat(failure.getServerErrorMessage().getMessage())
                        .isEqualTo("CREASTRIX_PLATFORM_ACCESS_WRITE_ISOLATION_V1: platform access write requires READ COMMITTED");
                assertThat(failure.getServerErrorMessage().getTable()).isEqualTo(table);
                assertThat(failure.getServerErrorMessage().getSchema()).isEqualTo("public");
                assertThat(failure.getServerErrorMessage().getDetail()).isEqualTo("operation=DELETE; actual_isolation="
                        + (isolation == Connection.TRANSACTION_REPEATABLE_READ ? "repeatable read" : "serializable"));
                connection.rollback();
            }
        }
    }

    @Test
    void realHikariGuardFailureClosesConnectionRollsBackAndNewReadCommittedLeaseWorks() throws Exception {
        DriverManagerDataSource source = (DriverManagerDataSource) latest();
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(source.getUrl());
        configuration.setUsername(POSTGRES.getUsername());
        configuration.setPassword(POSTGRES.getPassword());
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(5000);
        configuration.addDataSourceProperty("socketTimeout", "30");
        configuration.addDataSourceProperty("connectTimeout", "5");
        UUID failedUser;
        int failedPid;
        try (HikariDataSource pool = new HikariDataSource(configuration)) {
            try (Connection connection = pool.getConnection()) {
                failedPid = pid(connection);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                failedUser = user(connection);
                postgresFailure(catchThrowable(() -> execute(connection,
                        "DELETE FROM platform_access_grants WHERE false")), "0A000", null);
                assertThat(connection.isClosed()).as("standard Hikari 0A000 eviction remains visible").isTrue();
            }
            UUID success = tx(pool, connection -> {
                assertThat(pid(connection)).isNotEqualTo(failedPid);
                assertThat(scalar(connection, "SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                UUID recipient = user(connection);
                grant(connection, recipient, true, List.of(), TIME);
                return recipient;
            });
            try (Connection observer = source.getConnection()) {
                assertThat(scalar(observer, "SELECT count(*) FROM users WHERE id=?", Long.class, failedUser)).isZero();
                assertThat(scalar(observer, "SELECT count(*) FROM users WHERE id=?", Long.class, success)).isOne();
                assertThat(scalar(observer, "SELECT count(*) FROM platform_access_grants WHERE recipient_user_id=?",
                        Long.class, success)).isOne();
            }
        }
    }

    @Test
    void storedTimestampsAreFiniteMicrosecondsAndRawCoercionIsNotMisrepresentedAsJavaValidation() throws Exception {
        DataSource source = latest();
        Instant exact = TIME.plusNanos(123456000);
        UUID grant = tx(source, connection -> grant(connection, user(connection), true, List.of(), exact));
        try (Connection connection = source.getConnection()) {
            assertThat(scalar(connection, "SELECT issued_at FROM platform_access_grants WHERE id=?",
                    OffsetDateTime.class, grant).toInstant()).isEqualTo(exact);
            assertThat(strings(connection, "SELECT DISTINCT datetime_precision::text FROM information_schema.columns "
                    + "WHERE table_schema='public' AND table_name LIKE 'platform_access_%' AND data_type='timestamp with time zone'"))
                    .containsExactly("6");
            assertThat(scalar(connection, "SELECT ('2026-10-01T00:00:00.1234567Z'::timestamptz(6))",
                    OffsetDateTime.class).toInstant()).isEqualTo(TIME.plusNanos(123457000));
            assertThatThrownBy(() -> requireMicroseconds(TIME.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        }
        for (String infinity : List.of("infinity", "-infinity")) {
            Throwable failure = catchThrowable(() -> tx(source, connection -> {
                UUID id = user(connection);
                return execute(connection, """
                        INSERT INTO platform_access_grants(id,recipient_user_id,role,catalog_version,bundle_version,
                            starts_at,validity_kind,scope_kind,scope_target_count,state,revision,issued_by_user_id,issued_at,
                            last_changed_by_user_id,last_changed_at)
                        VALUES (?,?,'PLATFORM_OWNER',1,1,?::timestamptz,'UNBOUNDED','PLATFORM_SECURITY_METADATA',0,
                            'ACTIVE',1,?,?::timestamptz,NULL,NULL)
                        """, UUID.randomUUID(), id, infinity, id, infinity);
            }));
            postgresFailure(failure, "23514", "platform_access_grants_finite_times");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeReadAndRevokeHistoryCommitsWithExactSuccessAssociation(boolean revoke) throws Exception {
        DataSource source = latest();
        History history = tx(source, connection -> {
            History created = registration(connection, revoke);
            UUID audit = event(connection, created, revoke ? "GRANT_REVOKED" : "READ_ADMITTED", TIME.plusSeconds(20));
            if (revoke) { revokeAt(connection, created, TIME.plusSeconds(20)); }
            outcome(connection, created, audit, TIME.plusSeconds(20), revoke);
            return created;
        });
        try (Connection connection = source.getConnection()) {
            assertThat(scalar(connection, "SELECT outcome_kind FROM platform_access_operation_outcomes "
                    + "WHERE initiator_user_id=? AND operation_id=?", String.class, history.actor(), history.operation()))
                    .isEqualTo(revoke ? "REVOKED" : "READ_ADMITTED");
            assertThat(strings(connection, "SELECT event_kind FROM platform_access_audit_events "
                    + "WHERE operation_id=? ORDER BY event_at", history.operation()))
                    .containsExactly("INTENT_RECORDED", revoke ? "GRANT_REVOKED" : "READ_ADMITTED");
            assertThat(scalar(connection, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
                    + "AND table_name='platform_access_operation_outcomes' AND column_name IN ('account_status','payload','body')",
                    Long.class)).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"platform_access_operation_intents,UPDATE", "platform_access_operation_intents,DELETE",
            "platform_access_operation_intents,TRUNCATE", "platform_access_operation_outcomes,UPDATE",
            "platform_access_operation_outcomes,DELETE", "platform_access_operation_outcomes,TRUNCATE",
            "platform_access_audit_events,UPDATE", "platform_access_audit_events,DELETE",
            "platform_access_audit_events,TRUNCATE", "platform_access_grants,DELETE", "platform_access_grants,TRUNCATE"})
    void retainedRecordsRejectUpdateDeleteAndTruncateWithoutChangingAnyRows(String table, String operation)
            throws Exception {
        DataSource source = latest();
        tx(source, connection -> {
            History history = registration(connection, false);
            UUID audit = event(connection, history, "READ_ADMITTED", TIME.plusSeconds(20));
            outcome(connection, history, audit, TIME.plusSeconds(20), false);
            return null;
        });
        Map<String, List<String>> before = snapshot(source);
        String identityColumn = table.equals("platform_access_audit_events") || table.equals("platform_access_grants")
                ? "id" : "operation_id";
        String sql = operation.equals("UPDATE") ? "UPDATE " + table + " SET " + identityColumn + "=" + identityColumn
                : operation.equals("DELETE") ? "DELETE FROM " + table : "TRUNCATE " + table + " CASCADE";
        postgresFailure(catchThrowable(() -> tx(source, connection -> execute(connection, sql))),
                "23514", "platform_access_history_immutable");
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGISTRATION", "DENIAL", "OTHER_OPERATION", "WRONG_REVISION", "RECEIPT"})
    void readOutcomeCannotBorrowNonexecutionOrDifferentSuccessAudit(String scenario) throws Exception {
        DataSource source = latest();
        History history = tx(source, connection -> registration(connection, false));
        Map<String, List<String>> before = snapshot(source);
        Throwable failure = catchThrowable(() -> tx(source, connection -> {
            UUID audit;
            Instant time = TIME.plusSeconds(20);
            if (scenario.equals("REGISTRATION")) {
                audit = scalar(connection, "SELECT id FROM platform_access_audit_events WHERE operation_id=? "
                        + "AND event_kind='INTENT_RECORDED'", UUID.class, history.operation());
                time = TIME;
            } else if (scenario.equals("OTHER_OPERATION")) {
                History other = registration(connection, false);
                audit = event(connection, other, "READ_ADMITTED", time);
            } else {
                audit = event(connection, history, scenario.equals("DENIAL") ? "ACCESS_DENIED" : "READ_ADMITTED", time,
                        scenario.equals("RECEIPT") ? "OPERATION_RECEIPT" : null);
            }
            if (scenario.equals("WRONG_REVISION")) {
                execute(connection, """
                        INSERT INTO platform_access_operation_outcomes(initiator_user_id,operation_id,outcome_kind,
                            admitted_at,actor_grant_id,actor_grant_revision,audit_event_id)
                        VALUES (?,?,'READ_ADMITTED',?,?,2,?)
                        """, history.actor(), history.operation(), time, history.authority(), audit);
            } else { outcome(connection, history, audit, time, false); }
            return null;
        }));
        postgresFailure(failure, "23514", scenario.equals("REGISTRATION") || scenario.equals("DENIAL")
                || scenario.equals("RECEIPT") ? "platform_access_operation_outcomes_read_matches"
                        : "platform_access_operation_outcomes_audit_matches");
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_MUTATION", "WRONG_BEFORE_AFTER"})
    void revokeOutcomeRequiresRealMatchingMutationAndExactBeforeAfter(String scenario) throws Exception {
        DataSource source = latest();
        History history = tx(source, connection -> registration(connection, true));
        Map<String, List<String>> before = snapshot(source);
        Throwable failure = catchThrowable(() -> tx(source, connection -> {
            UUID audit = event(connection, history, "GRANT_REVOKED", TIME.plusSeconds(20));
            if (!scenario.equals("NO_MUTATION")) { revokeAt(connection, history, TIME.plusSeconds(20)); }
            if (scenario.equals("WRONG_BEFORE_AFTER")) {
                execute(connection, """
                        INSERT INTO platform_access_operation_outcomes(initiator_user_id,operation_id,outcome_kind,
                            admitted_at,actor_grant_id,actor_grant_revision,audit_event_id,
                            before_state,before_revision,after_state,after_revision)
                        VALUES (?,?,'REVOKED',?,?,1,?,'ACTIVE',2,'REVOKED',3)
                        """, history.actor(), history.operation(), TIME.plusSeconds(20), history.authority(), audit);
            } else { outcome(connection, history, audit, TIME.plusSeconds(20), true); }
            return null;
        }));
        postgresFailure(failure, "23514", "platform_access_operation_outcomes_revoke_matches");
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @Test
    void registrationWithoutActualAuditCannotCommitAndPairCannotBeReused() throws Exception {
        DataSource source = latest();
        Throwable missingAudit = catchThrowable(() -> tx(source, connection -> {
            UUID actor = user(connection);
            UUID authority = grant(connection, actor, true, List.of(), TIME);
            insertIntent(connection, new History(actor, authority, user(connection), null, UUID.randomUUID(), UUID.randomUUID()), false);
            return null;
        }));
        postgresFailure(missingAudit, "23514", "platform_access_operation_intents_registration_audit");
        History history = tx(source, connection -> registration(connection, false));
        Map<String, List<String>> before = snapshot(source);
        postgresFailure(catchThrowable(() -> tx(source, connection -> {
            insertIntent(connection, history, false);
            return null;
        })), "23505", "platform_access_operation_intents_pk");
        assertThat(snapshot(source)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTENT_KIND", "INTENT_REASON", "INTENT_VERSION", "AUDIT_KIND", "AUDIT_REASON",
            "AUDIT_OUTCOME", "AUDIT_NULL_PROJECTION", "OUTCOME_KIND", "OUTCOME_READ_BEFORE", "OUTCOME_INFINITE"})
    void closedShapesRejectUnknownMissingAndInapplicableFields(String malformed) throws Exception {
        DataSource source = latest();
        History history = tx(source, connection -> registration(connection, false));
        Map<String, List<String>> before = snapshot(source);
        Throwable failure = catchThrowable(() -> tx(source, connection -> {
            if (malformed.startsWith("INTENT")) {
                execute(connection, """
                        INSERT INTO platform_access_operation_intents(initiator_user_id,operation_id,intent_version,
                            kind,target_user_id,read_reason,registered_at,correlation_id,actor_grant_id,actor_grant_revision)
                        VALUES (?,?,?,?,?,?,?, ?,?,1)
                        """, history.actor(), UUID.randomUUID(), malformed.equals("INTENT_VERSION") ? 2 : 1,
                        malformed.equals("INTENT_KIND") ? "UNSUPPORTED" : "USER_SECURITY_READ", history.target(),
                        malformed.equals("INTENT_REASON") ? null : "USER_REQUESTED_SUPPORT", TIME,
                        UUID.randomUUID(), history.authority());
            } else if (malformed.startsWith("AUDIT")) {
                insertReadAudit(connection, history, UUID.randomUUID(),
                        malformed.equals("AUDIT_KIND") ? "UNSUPPORTED" : "READ_ADMITTED",
                        malformed.equals("AUDIT_REASON") ? "FREE_TEXT_IS_FORBIDDEN" : "USER_REQUESTED_SUPPORT",
                        malformed.equals("AUDIT_OUTCOME") ? "DELIVERED" : "ADMITTED",
                        malformed.equals("AUDIT_NULL_PROJECTION") ? null : "USER_SECURITY", TIME.plusSeconds(20));
            } else {
                UUID audit = event(connection, history, "READ_ADMITTED", TIME.plusSeconds(20));
                execute(connection, """
                        INSERT INTO platform_access_operation_outcomes(initiator_user_id,operation_id,outcome_kind,
                            admitted_at,actor_grant_id,actor_grant_revision,audit_event_id,before_state)
                        VALUES (?,?,?,?::timestamptz,?,1,?,?)
                        """, history.actor(), history.operation(), malformed.equals("OUTCOME_KIND") ? "UNKNOWN" : "READ_ADMITTED",
                        malformed.equals("OUTCOME_INFINITE") ? "infinity" : TIME.plusSeconds(20).toString(), history.authority(), audit,
                        malformed.equals("OUTCOME_READ_BEFORE") ? "ACTIVE" : null);
            }
            return null;
        }));
        String constraint = malformed.equals("INTENT_VERSION") ? "platform_access_operation_intents_version"
                : malformed.startsWith("INTENT") ? "platform_access_operation_intents_shape"
                : malformed.startsWith("AUDIT") ? "platform_access_audit_events_event_shape"
                : malformed.equals("OUTCOME_INFINITE") ? "platform_access_operation_outcomes_finite_time"
                : "platform_access_operation_outcomes_shape";
        postgresFailure(failure, "23514", constraint);
        assertThat(snapshot(source)).isEqualTo(before);
    }

    private record History(UUID actor, UUID authority, UUID target, UUID targetGrant, UUID operation, UUID correlation) { }

    private History registration(Connection connection, boolean revoke) throws SQLException {
        UUID actor = user(connection);
        UUID authority = grant(connection, actor, true, List.of(), TIME);
        UUID target = user(connection);
        UUID targetGrant = revoke ? grant(connection, target, false, List.of(actor), TIME) : null;
        History history = new History(actor, authority, target, targetGrant, UUID.randomUUID(), UUID.randomUUID());
        insertIntent(connection, history, revoke);
        event(connection, history, "INTENT_RECORDED", TIME);
        return history;
    }

    private void insertIntent(Connection connection, History history, boolean revoke) throws SQLException {
        execute(connection, """
                INSERT INTO platform_access_operation_intents(initiator_user_id,operation_id,intent_version,kind,
                    target_user_id,target_grant_id,read_reason,expected_revision,registered_at,correlation_id,
                    actor_grant_id,actor_grant_revision)
                VALUES (?,?,1,?,?,?,?,?,?,?, ?,1)
                """, history.actor(), history.operation(), revoke ? "SUPPORT_REVOKE" : "USER_SECURITY_READ", history.target(),
                history.targetGrant(), revoke ? null : "USER_REQUESTED_SUPPORT", revoke ? 1L : null,
                TIME, history.correlation(), history.authority());
    }

    private UUID event(Connection connection, History history, String kind, Instant time) throws SQLException {
        return event(connection, history, kind, time, null);
    }

    private UUID event(Connection connection, History history, String kind, Instant time, String projectionOverride)
            throws SQLException {
        UUID event = UUID.randomUUID();
        if (history.targetGrant() == null) {
            String projection = projectionOverride != null ? projectionOverride : kind.equals("ACCESS_DENIED") ? null : "USER_SECURITY";
            insertReadAudit(connection, history, event, kind,
                    kind.equals("ACCESS_DENIED") ? "INTENT_MISMATCH" : "USER_REQUESTED_SUPPORT",
                    kind.equals("INTENT_RECORDED") ? "REGISTERED" : kind.equals("ACCESS_DENIED") ? "DENIED" : "ADMITTED",
                    projection, time);
        } else {
            boolean revoked = kind.equals("GRANT_REVOKED");
            execute(connection, """
                    INSERT INTO platform_access_audit_events(id,event_version,event_kind,event_at,correlation_id,attempt_id,
                        actor_user_id,actor_grant_id,actor_grant_revision,actor_role,catalog_version,bundle_version,
                        supplied_target_user_id,supplied_target_grant_id,target_verification,target_user_id,target_grant_id,
                        initiator_user_id,operation_id,action,permission,outcome,before_state,before_revision,after_state,after_revision)
                    VALUES (?,1,?,?,?,?, ?,?,1,'PLATFORM_OWNER',1,1,?,?,'VERIFIED',?,?,?,?,'SUPPORT_REVOKE',
                        'STAFF_GRANT_SUSPEND_REVOKE',?,?,?,?,?)
                    """, event, kind, time, history.correlation(), UUID.randomUUID(), history.actor(), history.authority(),
                    history.target(), history.targetGrant(), history.target(), history.targetGrant(), history.actor(), history.operation(),
                    revoked ? "COMMITTED" : "REGISTERED", revoked ? "ACTIVE" : null, revoked ? 1L : null,
                    revoked ? "REVOKED" : null, revoked ? 2L : null);
        }
        return event;
    }

    private void insertReadAudit(Connection connection, History history, UUID event, String kind, String reason,
            String outcome, String projection, Instant time) throws SQLException {
        execute(connection, """
                INSERT INTO platform_access_audit_events(id,event_version,event_kind,event_at,correlation_id,attempt_id,
                    actor_user_id,actor_grant_id,actor_grant_revision,actor_role,catalog_version,bundle_version,
                    supplied_target_user_id,target_verification,target_user_id,initiator_user_id,operation_id,
                    action,permission,projection,reason,outcome)
                VALUES (?,1,?,?,?,?, ?,?,1,'PLATFORM_OWNER',1,1,?,'VERIFIED',?,?,?,
                    'USER_SECURITY_READ','USER_SECURITY_READ',?,?,?)
                """, event, kind, time, history.correlation(), UUID.randomUUID(), history.actor(), history.authority(),
                history.target(), history.target(), history.actor(), history.operation(), projection, reason, outcome);
    }

    private void outcome(Connection connection, History history, UUID audit, Instant time, boolean revoke) throws SQLException {
        execute(connection, """
                INSERT INTO platform_access_operation_outcomes(initiator_user_id,operation_id,outcome_kind,admitted_at,
                    actor_grant_id,actor_grant_revision,audit_event_id,before_state,before_revision,after_state,after_revision)
                VALUES (?,?,?,?,?,1,?,?,?,?,?)
                """, history.actor(), history.operation(), revoke ? "REVOKED" : "READ_ADMITTED", time, history.authority(), audit,
                revoke ? "ACTIVE" : null, revoke ? 1L : null, revoke ? "REVOKED" : null, revoke ? 2L : null);
    }

    private void revokeAt(Connection connection, History history, Instant time) throws SQLException {
        execute(connection, """
                UPDATE platform_access_grants SET state='REVOKED',revision=revision+1,last_changed_by_user_id=?,
                    last_changed_at=?,revoked_by_user_id=?,revoked_at=? WHERE id=?
                """, history.actor(), time, history.actor(), time, history.targetGrant());
    }

    private Map<String, List<String>> snapshot(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection()) { return rows(connection, TABLES, true); }
    }

    private UUID grant(Connection connection, UUID recipient, boolean owner, List<UUID> targets, Instant issuedAt)
            throws SQLException {
        if (targets == null || targets.stream().anyMatch(java.util.Objects::isNull)
                || targets.stream().distinct().count() != targets.size()
                || (owner ? !targets.isEmpty() : targets.isEmpty() || targets.size() > 100)) {
            throw new IllegalArgumentException("Synthetic scope must be exact and contain no duplicates");
        }
        UUID id = UUID.randomUUID();
        insertGrant(connection, id, recipient, owner, targets.size(), issuedAt);
        for (UUID target : targets) {
            execute(connection, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)", id, target);
        }
        return id;
    }

    private void insertGrant(Connection connection, UUID id, UUID recipient, boolean owner, int count, Instant issuedAt)
            throws SQLException {
        execute(connection, """
                INSERT INTO platform_access_grants(id,recipient_user_id,role,catalog_version,bundle_version,starts_at,
                    validity_kind,expires_at,scope_kind,scope_target_count,state,revision,issued_by_user_id,issued_at,
                    last_changed_by_user_id,last_changed_at)
                VALUES (?,?,?,1,1,?,?,?,?,?,'ACTIVE',1,?,?,NULL,NULL)
                """, id, recipient, owner ? "PLATFORM_OWNER" : "SUPPORT_READ", issuedAt,
                owner ? "UNBOUNDED" : "BOUNDED", owner ? null : issuedAt.plusSeconds(86400),
                owner ? "PLATFORM_SECURITY_METADATA" : "EXACT_USERS", count, recipient, issuedAt);
    }

    private void transition(Connection connection, UUID id, String state, UUID actor) throws SQLException {
        execute(connection, """
                UPDATE platform_access_grants SET state=?, revision=revision+1,
                    last_changed_by_user_id=?,last_changed_at=COALESCE(last_changed_at,issued_at)+interval '1 second',
                    revoked_by_user_id=?,revoked_at=CASE WHEN ?='REVOKED'
                        THEN COALESCE(last_changed_at,issued_at)+interval '1 second' ELSE NULL END WHERE id=?
                """, state, actor, state.equals("REVOKED") ? actor : null, state, id);
    }
}
