package com.creastrix.platform.platformaccess;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import com.creastrix.platform.platformaccess.application.AccountEligibilityGeneration;
import com.creastrix.platform.platformaccess.application.OwnedPlatformAccessTransactions;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Intent;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.Kind;
import com.creastrix.platform.platformaccess.application.PlatformAccessService;
import com.creastrix.platform.platformaccess.application.TrustedPlatformAccessFacts;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Assurance;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.ReadReason;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Stamp;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.StepUp;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.Verification;
import com.creastrix.platform.platformaccess.persistence.JdbcPlatformAccessRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic fixture/observer support only: this is not a production authorization adapter. */
final class PlatformAccessTestSupport {
    static final List<String> TABLES = List.of("platform_access_grants", "platform_access_grant_targets",
            "platform_access_operation_intents", "platform_access_operation_outcomes", "platform_access_audit_events");
    static final Instant TIME = Instant.parse("2026-10-01T00:00:00Z");

    private PlatformAccessTestSupport() { }

    @FunctionalInterface
    interface SqlWork<T> { T run(Connection connection) throws Exception; }

    /** Each database is inside the class's newly owned container; no external database is accepted. */
    static DriverManagerDataSource database(PostgreSQLContainer postgres) throws SQLException {
        assertThat(postgres.isRunning()).isTrue();
        String name = "pa_s_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource admin = source(postgres.getJdbcUrl(), postgres);
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
        DriverManagerDataSource result = source(url, postgres);
        try (Connection connection = result.getConnection()) {
            assertThat(scalar(connection, "SELECT current_database()", String.class)).isEqualTo(name);
            assertThat(scalar(connection, "SELECT current_setting('server_version_num')::integer", Integer.class))
                    .isEqualTo(180004);
        }
        return result;
    }

    private static DriverManagerDataSource source(String url, PostgreSQLContainer postgres) {
        DriverManagerDataSource source = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "40");
        properties.setProperty("options", "-c statement_timeout=30000 -c lock_timeout=12000");
        source.setConnectionProperties(properties);
        return source;
    }

    static Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target(target).executeInTransaction(true).group(false).lockRetryCount(10);
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        return configuration.load();
    }

    static <T> T tx(DataSource source, SqlWork<T> work) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception | AssertionError failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    static int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            return statement.executeUpdate();
        }
    }

    static <T> T scalar(Connection connection, String sql, Class<T> type, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("scalar row: %s", sql).isTrue();
                T value = result.getObject(1, type);
                assertThat(result.next()).as("one scalar row").isFalse();
                return value;
            }
        }
    }

    static List<String> strings(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) { values.add(result.getString(1)); }
                return values;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            if (argument instanceof Instant instant) {
                requireMicroseconds(instant);
                argument = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            statement.setObject(index + 1, argument);
        }
    }

    /** Fixture discipline only. S does not introduce or prove a production Java timestamp boundary. */
    static void requireMicroseconds(Instant instant) {
        if (instant == null || instant.getNano() % 1000 != 0
                || instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw new IllegalArgumentException("Synthetic fixture requires finite exact microseconds");
        }
    }

    static UUID user(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, "INSERT INTO users(id) VALUES (?)", id);
        execute(connection, "INSERT INTO user_profiles(user_id) VALUES (?)", id);
        return id;
    }

    /**
     * Privileged synthetic setup only. The separate production service still
     * performs every admission, mutation and audit operation under test.
     */
    static WorkflowFixture workflow(DataSource source, boolean owner) throws Exception {
        WorkflowFixture fixture = tx(source, connection -> {
            UUID actor = user(connection);
            UUID target = user(connection);
            Instant issuedAt = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class)
                    .toInstant().minusSeconds(60);
            UUID actorGrant = workflowGrant(connection, actor, actor, owner,
                    owner ? List.of() : List.of(target), issuedAt);
            UUID targetGrant = workflowGrant(connection, target, actor, false, List.of(actor), issuedAt);
            return new WorkflowFixture(source, actor, target, actorGrant, targetGrant, issuedAt,
                    new AtomicReference<>());
        });
        fixture.refreshFacts();
        return fixture;
    }

    static UUID workflowGrant(Connection connection, UUID recipient, UUID issuer, boolean owner,
                                      List<UUID> targets, Instant issuedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO platform_access_grants(id,recipient_user_id,role,catalog_version,bundle_version,starts_at,
                    validity_kind,expires_at,scope_kind,scope_target_count,state,revision,issued_by_user_id,issued_at,
                    last_changed_by_user_id,last_changed_at)
                VALUES (?,?,?,1,1,?,?,?,?,?,'ACTIVE',1,?,?,NULL,NULL)
                """, id, recipient, owner ? "PLATFORM_OWNER" : "SUPPORT_READ", issuedAt,
                owner ? "UNBOUNDED" : "BOUNDED", owner ? null : issuedAt.plusSeconds(86400),
                owner ? "PLATFORM_SECURITY_METADATA" : "EXACT_USERS", targets.size(), issuer, issuedAt);
        for (UUID target : targets) {
            execute(connection, "INSERT INTO platform_access_grant_targets(grant_id,target_user_id) VALUES (?,?)",
                    id, target);
        }
        return id;
    }

    record WorkflowFixture(DataSource source, UUID actor, UUID target, UUID actorGrant, UUID targetGrant,
                           Instant issuedAt, AtomicReference<TrustedPlatformAccessFacts.Snapshot> facts) {
        PlatformAccessService service(DataSource observedSource) {
            return new PlatformAccessService(new OwnedPlatformAccessTransactions(observedSource),
                    new JdbcPlatformAccessRepository(), facts::get);
        }

        PlatformAccessService service() {
            return service(source);
        }

        Intent readIntent(UUID operationId) {
            return new Intent(actor, operationId, Kind.USER_SECURITY_READ, target, null,
                    ReadReason.USER_REQUESTED_SUPPORT, null);
        }

        Intent revokeIntent(UUID operationId) {
            return new Intent(actor, operationId, Kind.SUPPORT_REVOKE, target, targetGrant, null, 1L);
        }

        /** Explicit test action; current() never refreshes its own idle/MFA evidence. */
        void refreshFacts() throws SQLException {
            try (Connection connection = source.getConnection()) {
                Instant now = scalar(connection, "SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
                long generation = scalar(connection, "SELECT account_eligibility_generation FROM users WHERE id=?",
                        Long.class, actor);
                long revision = scalar(connection, "SELECT revision FROM platform_access_grants WHERE id=?",
                        Long.class, actorGrant);
                UUID session = facts.get() == null ? UUID.randomUUID() : facts.get().sessionGeneration();
                Stamp stamp = new Stamp(actor, AccountEligibilityGeneration.encode(generation), session,
                        actorGrant, revision);
                Assurance assurance = new Assurance(Verification.VERIFIED_MFA, stamp, now, now, now,
                        new StepUp(Verification.VERIFIED_STEP_UP, stamp, now));
                facts.set(new TrustedPlatformAccessFacts.Snapshot(actor, session, assurance));
            }
        }
    }

    static Map<String, List<String>> rows(Connection connection, List<String> tables, boolean generation)
            throws SQLException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String table : tables) {
            String json = table.equals("users") && generation
                    ? "(to_jsonb(t) - 'account_eligibility_generation')" : "to_jsonb(t)";
            rows.put(table, strings(connection, "SELECT " + json + "::text FROM public." + table
                    + " t ORDER BY " + json + "::text"));
        }
        return rows;
    }

    static PSQLException postgresFailure(Throwable failure, String state, String constraint) {
        Throwable current = failure;
        while (current != null && !(current instanceof PSQLException)) { current = current.getCause(); }
        assertThat(current).as("real PostgreSQL failure").isInstanceOf(PSQLException.class);
        PSQLException postgres = (PSQLException) current;
        assertThat(postgres.getSQLState()).isEqualTo(state);
        if (constraint != null) {
            assertThat(postgres.getServerErrorMessage()).isNotNull();
            assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        }
        return postgres;
    }

    static int pid(Connection connection) throws SQLException {
        return scalar(connection, "SELECT pg_backend_pid()", Integer.class);
    }

    static void await(BooleanSupplier predicate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() >= deadline) { throw new AssertionError("Bounded PostgreSQL observation timed out"); }
            Thread.onSpinWait();
        }
    }

    static void awaitBlocked(DataSource source, int waiter, int blocker) throws SQLException {
        try (Connection observer = source.getConnection()) {
            await(() -> {
                try {
                    return scalar(observer, "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blocker, waiter);
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
            assertThat(scalar(observer, "SELECT wait_event_type FROM pg_stat_activity WHERE pid=?", String.class, waiter))
                    .isEqualTo("Lock");
            System.out.printf("PLATFORM_ACCESS_CONTENTION waiter=%d blocker=%d confirmed=true%n", waiter, blocker);
        }
    }
}
