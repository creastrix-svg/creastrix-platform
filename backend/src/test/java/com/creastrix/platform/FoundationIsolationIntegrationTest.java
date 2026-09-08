package com.creastrix.platform;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotActiveException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.PGConnection;
import org.postgresql.util.PSQLException;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Author regression evidence: real V8 reproductions and V9 admission in owned disposable databases. */
@SpringBootTest
@Testcontainers
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
class FoundationIsolationIntegrationTest {

    private static final String MARKER = "CREASTRIX_FOUNDATION_WRITE_ISOLATION_V1";
    private static final String FUNCTION = "foundation_require_read_committed";
    private static final String V8_DATABASE = "foundation_v8_" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> DOMAIN_TABLES = List.of("users", "user_profiles", "organizations",
            "organization_memberships", "workspaces", "workspace_memberships", "workspace_membership_scopes",
            "ready_made_products", "ready_made_product_manual_quantity_delta_commands");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrganizationService organizations;
    @Autowired
    private WorkspaceService workspaces;

    @BeforeAll
    static void createSeparateOwnedV8Baseline() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            connection.commit();
            connection.setAutoCommit(true);
            execute(connection, "CREATE DATABASE " + V8_DATABASE);
        }
        Flyway.configure().dataSource(url(true), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("8").load().migrate();
        assertThat(database(true).queryForList("SELECT version FROM public.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    static Stream<Arguments> forbiddenModesAndTables() {
        return Stream.of(Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_REPEATABLE_READ,
                        Connection.TRANSACTION_SERIALIZABLE)
                .flatMap(mode -> Stream.of(Target.values()).map(target -> Arguments.of(mode, target)));
    }

    @ParameterizedTest(name = "isolation {0}, exact public target {1}")
    @MethodSource("forbiddenModesAndTables")
    void allOrdinaryWritePathsReachTheExactStatementGuard(int isolation, Target target) throws Exception {
        Fixture fixture = fixture(false, true);
        Map<String, List<String>> before = snapshot(false);
        for (WriteAttempt attempt : attempts(target, fixture)) {
            try (Connection connection = connection(false, isolation)) {
                String actual = text(connection, "SHOW transaction_isolation");
                assertThat(actual).isEqualTo(isolationName(isolation));
                Throwable failure = assertThrows(Exception.class, () -> attempt.action().run(connection), attempt.label());
                assertGuard(failure, "public", target.table, attempt.operation(), actual);
                connection.rollback();
            }
            assertThat(snapshot(false)).as("no partial effects: %s / %s", target, attempt.label()).isEqualTo(before);
        }
    }

    @Test
    void readCommittedAdmitsCoherentCreationAndOrdinaryAlternateWritePaths() throws Exception {
        Fixture fixture = fixture(false, true);
        for (Target target : Target.values()) {
            for (WriteAttempt attempt : attempts(target, fixture)) {
                if (attempt.operation().equals("TRUNCATE") || attempt.operation().equals("DELETE")
                        && !attempt.label().contains("zero")) {
                    continue;
                }
                // The transaction is intentionally rolled back: standalone parent INSERTs
                // need their Membership partner at commit, separately proved below.
                try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
                    attempt.action().run(connection);
                    connection.rollback();
                }
            }
        }
        UUID organization = organizations.createOrganization(fixture.a()).id();
        UUID workspace = workspaces.createOrganizationOwnedWorkspace(organization, fixture.a()).id();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.organization_memberships WHERE organization_id=?",
                Integer.class, organization)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.workspace_memberships WHERE workspace_id=?",
                Integer.class, workspace)).isOne();
        try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            execute(connection, "DELETE FROM public.organization_memberships WHERE organization_id='" + fixture.org()
                    + "' AND user_id='" + fixture.b() + "'");
            connection.commit();
        }
        assertThat(count(false, "organization_memberships", "organization_id", fixture.org())).isOne();
    }

    @Test
    void readCommittedPreservesExistingTruncateAndWorkspaceDeletionGuards() throws Exception {
        Fixture fixture = fixture(false, true);
        for (Target target : Target.values()) {
            Map<String, List<String>> before = snapshot(false);
            try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
                Throwable failure = assertThrows(SQLException.class, () -> execute(connection, target.truncate()));
                assertThat(rootPostgres(failure).getSQLState()).isEqualTo("23514");
                assertThat(rootPostgres(failure).getServerErrorMessage().getMessage()).doesNotContain(MARKER);
                connection.rollback();
            }
            assertThat(snapshot(false)).isEqualTo(before);
        }
        try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            Throwable failure = assertThrows(SQLException.class, () -> execute(connection,
                    "DELETE FROM public.workspaces WHERE id='" + fixture.workspace() + "'"));
            assertThat(rootPostgres(failure).getSQLState()).isEqualTo("23514");
            assertThat(rootPostgres(failure).getServerErrorMessage().getMessage()).contains("cannot be deleted");
            connection.rollback();
        }
    }

    @Test
    void readCommittedMembershipReassignmentAndRoleStatusChangesPreserveBothParentFoundations() throws Exception {
        Fixture source = fixture(false, true);
        Fixture destination = fixture(false, true);
        try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            try (var ownerMove = connection.prepareStatement("UPDATE public.organization_memberships "
                    + "SET organization_id=? WHERE organization_id=? AND user_id=?");
                    var adminMove = connection.prepareStatement("UPDATE public.workspace_memberships "
                            + "SET workspace_id=? WHERE workspace_id=? AND user_id=?")) {
                ownerMove.setObject(1, destination.org());
                ownerMove.setObject(2, source.org());
                ownerMove.setObject(3, source.b());
                assertThat(ownerMove.executeUpdate()).isOne();
                adminMove.setObject(1, destination.workspace());
                adminMove.setObject(2, source.workspace());
                adminMove.setObject(3, source.b());
                assertThat(adminMove.executeUpdate()).isOne();
            }
            connection.commit();
        }
        assertThat(count(false, "organization_memberships", "organization_id", source.org())).isOne();
        assertThat(count(false, "workspace_memberships", "workspace_id", source.workspace())).isOne();
        assertThat(count(false, "organization_memberships", "organization_id", destination.org())).isEqualTo(3);
        assertThat(count(false, "workspace_memberships", "workspace_id", destination.workspace())).isEqualTo(3);
        jdbc.update("UPDATE public.workspace_memberships SET role='VIEWER', status='SUSPENDED' "
                + "WHERE workspace_id=? AND user_id=?", destination.workspace(), source.b());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.workspace_memberships WHERE workspace_id=? "
                + "AND role='ADMIN' AND status='ACTIVE'", Integer.class, destination.workspace())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.workspace_memberships WHERE workspace_id=? "
                + "AND user_id=? AND role='VIEWER' AND status='SUSPENDED'", Integer.class,
                destination.workspace(), source.b())).isOne();
    }

    @Test
    void nativeV8ForeignKeyFeatureNotSupportedCannotPassGuardAssertion() throws Exception {
        try (Connection connection = connection(true, Connection.TRANSACTION_REPEATABLE_READ)) {
            String actual = text(connection, "SHOW transaction_isolation");
            assertThat(actual).isEqualTo("repeatable read");
            Throwable nativeFailure = assertThrows(SQLException.class,
                    () -> execute(connection, "TRUNCATE public.organizations CONTINUE IDENTITY RESTRICT"));
            PSQLException error = rootPostgres(nativeFailure);
            assertThat(error.getSQLState()).isEqualTo("0A000");
            assertThat(error.getServerErrorMessage().getMessage()).contains("foreign key");
            assertThrows(AssertionError.class,
                    () -> assertGuard(nativeFailure, "public", "organizations", "TRUNCATE", actual));
            connection.rollback();
        }
    }

    @Test
    void exactGuardAssertionRejectsWrongSchemaRelationOperationIsolationAndMarker() throws Exception {
        try (Connection connection = connection(false, Connection.TRANSACTION_REPEATABLE_READ)) {
            String actual = text(connection, "SHOW transaction_isolation");
            assertThat(actual).isEqualTo("repeatable read");
            Throwable failure = assertThrows(SQLException.class,
                    () -> execute(connection, "DELETE FROM public.organizations WHERE false"));
            assertGuard(failure, "public", "organizations", "DELETE", actual);
            assertThrows(AssertionError.class, () -> assertGuard(failure, "decoy", "organizations", "DELETE", "repeatable read"));
            assertThrows(AssertionError.class, () -> assertGuard(failure, "public", "workspaces", "DELETE", "repeatable read"));
            assertThrows(AssertionError.class, () -> assertGuard(failure, "public", "organizations", "UPDATE", "repeatable read"));
            assertThrows(AssertionError.class, () -> assertGuard(failure, "public", "organizations", "DELETE", "serializable"));
            assertThrows(AssertionError.class, () -> assertGuard(failure, "public", "organizations", "DELETE",
                    "repeatable read", "ANOTHER_GUARD"));
            connection.rollback();
        }
    }

    @Test
    void requiredPublicServicesPreserveExactGuardEvidenceAndPoolRecoversAfterAmbientRollbackFailure() throws Exception {
        Fixture fixture = fixture(false, true);
        assertThat(AopUtils.isAopProxy(organizations)).isTrue();
        assertThat(AopUtils.isAopProxy(workspaces)).isTrue();
        assertThat(jdbc.getDataSource()).isInstanceOf(com.zaxxer.hikari.HikariDataSource.class);
        for (int isolation : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            for (boolean organizationCall : List.of(false, true)) {
                Map<String, List<String>> before = snapshot(false);
                UUID sentinelUser = UUID.randomUUID();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setIsolationLevel(isolation);
                tx.setTimeout(40);
                AtomicReference<String> actual = new AtomicReference<>();
                AtomicReference<Integer> failedPid = new AtomicReference<>();
                AtomicReference<Connection> pooled = new AtomicReference<>();
                AtomicReference<Connection> physical = new AtomicReference<>();
                AtomicReference<RuntimeException> serviceFailure = new AtomicReference<>();
                TransactionSystemException failure = assertThrows(TransactionSystemException.class, () -> tx.executeWithoutResult(status -> {
                    Connection bound = DataSourceUtils.getConnection(jdbc.getDataSource());
                    pooled.set(bound);
                    try {
                        physical.set((Connection) bound.unwrap(PGConnection.class));
                        assertThat(bound.getAutoCommit()).isFalse();
                    } catch (SQLException error) {
                        throw new AssertionError("Cannot observe the real ambient pooled connection", error);
                    }
                    failedPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    actual.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
                    assertThat(actual.get()).isEqualTo(isolationName(isolation));
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users", Integer.class)).isPositive();
                    // Unguarded writes preceding the real service call must also roll back.
                    jdbc.update("INSERT INTO public.users(id) VALUES (?)", sentinelUser);
                    jdbc.update("INSERT INTO public.user_profiles(user_id) VALUES (?)", sentinelUser);
                    try {
                        if (organizationCall) {
                            organizations.createOrganization(fixture.a());
                        } else {
                            workspaces.createUserOwnedWorkspace(fixture.a());
                        }
                    } catch (RuntimeException original) {
                        serviceFailure.set(original);
                        throw original;
                    }
                }));
                assertThat(serviceFailure.get()).isNotNull();
                assertThat(failure).isNotSameAs(serviceFailure.get());
                assertThat(failure.getApplicationException()).isSameAs(serviceFailure.get());
                String relation = organizationCall ? "organizations" : "workspaces";
                assertGuard(serviceFailure.get(), "public", relation, "INSERT", actual.get());
                assertThat(failure).hasRootCauseInstanceOf(SQLException.class).hasRootCauseMessage("Connection is closed");
                assertThrows(AssertionError.class, () -> assertGuard(failure, "public", relation, "INSERT", actual.get()));
                assertThrows(AssertionError.class, () -> assertGuard(new SQLException("Connection is closed"),
                        "public", relation, "INSERT", actual.get()));
                assertThat(pooled.get().isClosed()).isTrue();
                awaitPhysicalConnectionClosedAndBackendGone(physical.get(), failedPid.get());
                assertThat(snapshot(false)).isEqualTo(before);
                assertThat(count(false, "users", "id", sentinelUser)).isZero();

                // Recovery means a new physical transaction after the failed outer one
                // has ended, not continuation of that broken transaction via savepoint.
                TransactionTemplate recovered = new TransactionTemplate(transactionManager);
                recovered.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                recovered.setTimeout(40);
                AtomicReference<Integer> recoveredPid = new AtomicReference<>();
                UUID created = recovered.execute(status -> {
                    recoveredPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    assertThat(recoveredPid.get()).isNotEqualTo(failedPid.get());
                    assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                    return organizationCall ? organizations.createOrganization(fixture.a()).id()
                            : workspaces.createUserOwnedWorkspace(fixture.a()).id();
                });
                assertThat(created).isNotNull();
                assertThat(count(false, relation, "id", created)).isOne();
                assertThat(count(false, organizationCall ? "organization_memberships" : "workspace_memberships",
                        organizationCall ? "organization_id" : "workspace_id", created)).isOne();
                System.out.printf("FOUNDATION_HIKARI_AMBIENT isolation=%s table=%s failed_pid=%d recovered_pid=%d "
                        + "same_application_exception=true physical_closed=true failed_rows=0 recovery_commit=true%n",
                        actual.get(), relation, failedPid.get(), recoveredPid.get());
            }
        }
    }

    private static void awaitPhysicalConnectionClosedAndBackendGone(Connection physical, int pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            while (System.nanoTime() < deadline) {
                long backends;
                try (var statement = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid=? "
                        + "AND datid=(SELECT oid FROM pg_database WHERE datname=current_database())")) {
                    statement.setQueryTimeout(5);
                    statement.setInt(1, pid);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        backends = result.getLong(1);
                    }
                }
                observer.rollback();
                if (physical.isClosed() && backends == 0) {
                    return;
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("Hikari physical connection/backend did not close within the bounded wait: " + pid);
    }

    @Test
    void actualIsolationNotSessionDefaultDeterminesAdmissionAndNoModeIsSilentlyChanged() throws Exception {
        try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            execute(connection, "SET default_transaction_isolation = 'serializable'");
            assertThat(text(connection, "SHOW default_transaction_isolation")).isEqualTo("serializable");
            assertThat(text(connection, "SHOW transaction_isolation")).isEqualTo("read committed");
            execute(connection, "UPDATE public.organizations SET id=id WHERE false");
            connection.rollback();
        }
        try (Connection connection = connection(false, Connection.TRANSACTION_SERIALIZABLE)) {
            execute(connection, "SET default_transaction_isolation = 'read committed'");
            assertThat(text(connection, "SHOW default_transaction_isolation")).isEqualTo("read committed");
            String actual = text(connection, "SHOW transaction_isolation");
            Throwable failure = assertThrows(SQLException.class,
                    () -> execute(connection, "UPDATE public.organizations SET id=id WHERE false"));
            assertGuard(failure, "public", "organizations", "UPDATE", actual);
            connection.rollback();
        }
        try (Connection connection = connection(false, Connection.TRANSACTION_REPEATABLE_READ)) {
            number(connection, "SELECT count(*) FROM public.organizations");
            Throwable failure = assertThrows(SQLException.class,
                    () -> execute(connection, "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"));
            assertThat(rootPostgres(failure).getSQLState()).isEqualTo("25001");
            connection.rollback();
        }
    }

    @Test
    void savepointRecoveryRetainsOuterTransactionButNeverAdmitsAnotherForbiddenWrite() throws Exception {
        Map<String, List<String>> before = snapshot(false);
        try (Connection connection = connection(false, Connection.TRANSACTION_REPEATABLE_READ)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                String actual = text(connection, "SHOW transaction_isolation");
                assertThat(actual).isEqualTo("repeatable read");
                var savepoint = connection.setSavepoint();
                Throwable failure = assertThrows(SQLException.class,
                        () -> execute(connection, "DELETE FROM public.organizations WHERE false"));
                assertGuard(failure, "public", "organizations", "DELETE", actual);
                connection.rollback(savepoint);
                assertThat(text(connection, "SHOW transaction_isolation")).isEqualTo("repeatable read");
                number(connection, "SELECT count(*) FROM public.organizations");
            }
            connection.commit();
        }
        assertThat(snapshot(false)).isEqualTo(before);
    }

    @Test
    void readOnlyHigherIsolationAndUserSuspensionDoNotBecomeStructuralFailures() throws Exception {
        Fixture fixture = fixture(false, true);
        for (int isolation : List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            try (Connection connection = connection(false, isolation)) {
                execute(connection, "SET TRANSACTION READ ONLY");
                assertThat(number(connection, "SELECT count(*) FROM public.organizations")).isPositive();
                connection.commit();
            }
        }
        try (Connection connection = connection(false, Connection.TRANSACTION_REPEATABLE_READ)) {
            execute(connection, "UPDATE public.users SET status='SUSPENDED' WHERE id IN ('" + fixture.a()
                    + "','" + fixture.b() + "')");
            connection.commit();
        }
        try (Connection connection = connection(false, Connection.TRANSACTION_READ_COMMITTED)) {
            execute(connection, "UPDATE public.organization_memberships SET role=role WHERE organization_id='" + fixture.org() + "'");
            execute(connection, "UPDATE public.workspace_memberships SET role=role WHERE workspace_id='" + fixture.workspace() + "'");
            connection.commit();
        }
        assertThat(count(false, "organization_memberships", "organization_id", fixture.org())).isEqualTo(2);
        assertThat(count(false, "workspace_memberships", "workspace_id", fixture.workspace())).isEqualTo(2);
        assertThatThrownBy(() -> workspaces.createUserOwnedWorkspace(fixture.a()))
                .isInstanceOf(WorkspaceCreatorNotActiveException.class);
    }

    @Test
    void guardMetadataUsesExactPublicRelationAndFunctionOidsDespiteSameNamedDecoys() {
        Map<String, Map<String, Object>> expected = guardMetadata();
        String schema = "guard_decoy_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            jdbc.execute("CREATE FUNCTION " + schema + "." + FUNCTION
                    + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
            for (Target target : Target.values()) {
                jdbc.execute("CREATE TABLE " + schema + "." + target.table + " (id uuid)");
                jdbc.execute("CREATE TRIGGER " + target.table + "_require_read_committed BEFORE UPDATE ON "
                        + schema + "." + target.table + " FOR EACH ROW EXECUTE FUNCTION " + schema + "." + FUNCTION + "()");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                    + "WHERE p.proname=? AND n.nspname IN ('public', ?)", Integer.class, FUNCTION, schema)).isEqualTo(2);
            assertThat(guardMetadata()).isEqualTo(expected);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Integer.class, schema)).isZero();
        assertThat(guardMetadata()).isEqualTo(expected);
    }

    @RepeatedTest(3)
    void v8RepeatableReadReproducesBothOriginalWriteSkewsWhileReadCommittedRejectsTheLastRemoval() throws Exception {
        for (boolean workspace : List.of(false, true)) {
            runRemovalPair(true, Connection.TRANSACTION_REPEATABLE_READ, workspace ? "ADMIN" : "OWNER", false, true);
            runRemovalPair(true, Connection.TRANSACTION_READ_COMMITTED, workspace ? "ADMIN" : "OWNER", false, false);
        }
    }

    @RepeatedTest(3)
    void v9ReadCommittedPreservesOwnerAdminAndCrossTableIntersectionInBothCommitOrders() throws Exception {
        runRemovalPair(false, Connection.TRANSACTION_READ_COMMITTED, "OWNER", false, false);
        runRemovalPair(false, Connection.TRANSACTION_READ_COMMITTED, "ADMIN", false, false);
        runRemovalPair(false, Connection.TRANSACTION_READ_COMMITTED, "CROSS", false, false);
        runRemovalPair(false, Connection.TRANSACTION_READ_COMMITTED, "CROSS", true, false);
    }

    @RepeatedTest(3)
    void v9ForbiddenSnapshotPairsPreserveBothOwnersAndAdminsWithoutPartialEffects() throws Exception {
        for (int isolation : List.of(Connection.TRANSACTION_READ_UNCOMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            for (String kind : List.of("OWNER", "ADMIN", "CROSS")) {
                Fixture fixture = fixture(false, !kind.equals("OWNER"));
                Map<String, List<String>> before = snapshot(false);
                try (Connection first = connection(false, isolation); Connection second = connection(false, isolation)) {
                    String firstIsolation = text(first, "SHOW transaction_isolation");
                    String secondIsolation = text(second, "SHOW transaction_isolation");
                    assertThat(firstIsolation).isEqualTo(isolationName(isolation));
                    assertThat(secondIsolation).isEqualTo(isolationName(isolation));
                    assertThat(number(first, "SELECT count(*) FROM public.organization_memberships WHERE organization_id='" + fixture.org() + "'")).isEqualTo(2);
                    assertThat(number(second, "SELECT count(*) FROM public.organization_memberships WHERE organization_id='" + fixture.org() + "'")).isEqualTo(2);
                    assertThat(number(first, "SELECT pg_backend_pid()")).isNotEqualTo(number(second, "SELECT pg_backend_pid()"));
                    String firstTable = kind.equals("OWNER") ? "organization_memberships" : "workspace_memberships";
                    String secondTable = kind.equals("ADMIN") ? "workspace_memberships" : "organization_memberships";
                    Throwable firstFailure = assertThrows(SQLException.class, () -> remove(first, firstTable, fixture, fixture.a()));
                    Throwable secondFailure = assertThrows(SQLException.class, () -> remove(second, secondTable, fixture, fixture.b()));
                    assertGuard(firstFailure, "public", firstTable, "DELETE", firstIsolation);
                    assertGuard(secondFailure, "public", secondTable, "DELETE", secondIsolation);
                    first.rollback();
                    second.rollback();
                }
                assertThat(snapshot(false)).isEqualTo(before);
            }
        }
    }

    private void runRemovalPair(boolean v8, int isolation, String kind, boolean reverse, boolean bothCommit) throws Exception {
        Fixture fixture = fixture(v8, !kind.equals("OWNER"));
        String firstTable = kind.equals("OWNER") ? "organization_memberships" : "workspace_memberships";
        String secondTable = kind.equals("ADMIN") ? "workspace_memberships" : "organization_memberships";
        if (reverse) {
            String swap = firstTable;
            firstTable = secondTable;
            secondTable = swap;
        }
        var workers = Executors.newSingleThreadExecutor();
        try (Connection first = connection(v8, isolation); Connection second = connection(v8, isolation)) {
            try {
                assertThat(number(first, "SELECT count(*) FROM public.organization_memberships WHERE organization_id='" + fixture.org() + "'")).isEqualTo(2);
                assertThat(number(second, "SELECT count(*) FROM public.organization_memberships WHERE organization_id='" + fixture.org() + "'")).isEqualTo(2);
                int firstPid = (int) number(first, "SELECT pg_backend_pid()");
                int secondPid = (int) number(second, "SELECT pg_backend_pid()");
                assertThat(firstPid).isNotEqualTo(secondPid);
                remove(first, firstTable, fixture, fixture.a());
                remove(second, secondTable, fixture, fixture.b());
                execute(first, "SET CONSTRAINTS ALL IMMEDIATE");
                Future<Throwable> secondCommit = workers.submit(() -> {
                    try {
                        second.commit();
                        return null;
                    } catch (SQLException failure) {
                        return failure;
                    }
                });
                awaitExactBlock(v8, secondPid, firstPid, secondCommit);
                first.commit();
                Throwable failure = secondCommit.get(60, TimeUnit.SECONDS);
                if (bothCommit) {
                    assertThat(failure).as("V8 RED: both old-snapshot removals commit").isNull();
                } else {
                    PSQLException postgres = rootPostgres(failure);
                    assertThat(postgres.getSQLState()).isEqualTo("23514");
                    assertThat(postgres.getServerErrorMessage().getMessage())
                            .contains(kind.equals("OWNER") ? "ACTIVE OWNER Organization Membership" : "must retain at least one User who is both an ACTIVE OWNER of Organization");
                }
            } finally {
                first.rollback();
                workers.shutdown();
                assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                second.rollback();
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        String resultTable = kind.equals("OWNER") ? "organization_memberships" : "workspace_memberships";
        if (!kind.equals("CROSS")) {
            assertThat(count(v8, resultTable, kind.equals("OWNER") ? "organization_id" : "workspace_id",
                    kind.equals("OWNER") ? fixture.org() : fixture.workspace())).isEqualTo(bothCommit ? 0 : 1);
        }
        if (kind.equals("ADMIN")) {
            assertThat(count(v8, "organization_memberships", "organization_id", fixture.org())).isEqualTo(2);
        }
        if (kind.equals("CROSS")) {
            assertThat(database(v8).queryForObject("SELECT count(*) FROM public.workspace_memberships wm "
                    + "JOIN public.organization_memberships om ON om.user_id=wm.user_id WHERE wm.workspace_id=? "
                    + "AND om.organization_id=? AND wm.role='ADMIN' AND wm.status='ACTIVE' "
                    + "AND om.role='OWNER' AND om.status='ACTIVE'", Integer.class, fixture.workspace(), fixture.org())).isOne();
        }
        System.out.printf("FOUNDATION_%s_%s_%s reverse=%s: %s%n", v8 ? "V8" : "V9", isolationName(isolation), kind,
                reverse, bothCommit ? "RED reproduced: both committed, structural count zero" : "GREEN: one commit, precise 23514, invariant retained");
    }

    private static void awaitExactBlock(boolean v8, int waiter, int blocker, Future<?> future) throws Exception {
        assertThat(waiter).isNotEqualTo(blocker);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (Connection observer = connection(v8, Connection.TRANSACTION_READ_COMMITTED)) {
            while (System.nanoTime() < deadline) {
                assertThat(future.isDone()).as("worker must still be blocked, not failed or completed early").isFalse();
                try (var statement = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity w "
                        + "JOIN pg_stat_activity b ON b.pid=? WHERE w.pid=? "
                        + "AND w.datid=(SELECT oid FROM pg_database WHERE datname=current_database()) "
                        + "AND b.datid=w.datid AND w.wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(w.pid))")) {
                    statement.setInt(1, blocker);
                    statement.setInt(2, waiter);
                    statement.setInt(3, blocker);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        if (result.getInt(1) == 1) {
                            return;
                        }
                    }
                }
                observer.rollback();
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("No exact same-database lock wait " + waiter + " -> " + blocker);
    }

    private Map<String, Map<String, Object>> guardMetadata() {
        Long functionOid = jdbc.queryForObject("SELECT p.oid::bigint FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                + "WHERE n.nspname='public' AND p.proname=? AND p.pronargs=0", Long.class, FUNCTION);
        assertThat(functionOid).isNotNull();
        String definition = jdbc.queryForObject("SELECT pg_get_functiondef(CAST(? AS oid))", String.class, functionOid);
        assertThat(definition).contains(MARKER, "transaction_isolation", "TG_TABLE_SCHEMA", "TG_TABLE_NAME", "TG_OP", "0A000");
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Target target : Target.values()) {
            Long relationOid = jdbc.queryForObject("SELECT c.oid::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                    + "WHERE n.nspname='public' AND c.relname=? AND c.relkind='r'", Long.class, target.table);
            Map<String, Object> row = jdbc.queryForMap("SELECT oid::bigint AS trigger_oid, tgrelid::bigint AS relation_oid, "
                    + "tgfoid::bigint AS function_oid, tgtype::integer AS trigger_type, tgenabled::text AS enabled, "
                    + "tgdeferrable, tginitdeferred, tgisinternal, tgnargs::integer AS arguments "
                    + "FROM pg_trigger WHERE tgrelid=CAST(? AS oid) AND tgname=?", relationOid,
                    target.table + "_require_read_committed");
            assertThat(row).containsEntry("relation_oid", relationOid).containsEntry("function_oid", functionOid)
                    .containsEntry("trigger_type", 62).containsEntry("enabled", "O")
                    .containsEntry("tgdeferrable", false).containsEntry("tginitdeferred", false)
                    .containsEntry("tgisinternal", false).containsEntry("arguments", 0);
            result.put(target.table, row);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgfoid=CAST(? AS oid)", Integer.class, functionOid)).isEqualTo(4);
        return result;
    }

    private static List<WriteAttempt> attempts(Target target, Fixture fixture) {
        List<WriteAttempt> attempts = new ArrayList<>();
        String table = "public." + target.table;
        String columns = target.columns;
        String[] names = columns.split(",");
        String values = target.values(fixture);
        String sourceColumns = String.join(",", java.util.Arrays.stream(names).map(name -> "s." + name).toList());
        String identity = names[0];
        for (boolean zero : List.of(true, false)) {
            String label = zero ? "zero" : "multi";
            String predicate = zero ? "false" : "true";
            attempts.add(sql("INSERT " + label, "INSERT", "INSERT INTO " + table + " (" + columns + ") SELECT "
                    + sourceColumns + " FROM (VALUES " + values + ") AS s(" + columns + ") WHERE " + predicate));
            attempts.add(sql("UPDATE " + label, "UPDATE", "UPDATE " + table + " SET " + identity + "=" + identity + " WHERE " + predicate));
            attempts.add(sql("DELETE " + label, "DELETE", "DELETE FROM " + table + " WHERE " + predicate));
            attempts.add(new WriteAttempt("prepared INSERT " + label, "INSERT", connection -> {
                try (var statement = connection.prepareStatement("INSERT INTO " + table + " (" + columns + ") SELECT "
                        + sourceColumns + " FROM (VALUES " + values + ") AS s(" + columns + ") WHERE ?")) {
                    statement.setBoolean(1, !zero);
                    statement.executeUpdate();
                }
            }));
            for (String operation : List.of("UPDATE", "DELETE")) {
                attempts.add(new WriteAttempt("prepared " + operation + " " + label, operation, connection -> {
                    String prefix = operation.equals("UPDATE") ? "UPDATE " + table + " SET " + identity + "=" + identity : "DELETE FROM " + table;
                    try (var statement = connection.prepareStatement(prefix + " WHERE ?")) {
                        statement.setBoolean(1, !zero);
                        statement.executeUpdate();
                    }
                }));
            }
            attempts.add(sql("MERGE INSERT " + label, "INSERT", "MERGE INTO " + table + " t USING (SELECT "
                    + sourceColumns + " FROM (VALUES " + values + ") AS s(" + columns + ") WHERE " + predicate
                    + ") s ON false WHEN NOT MATCHED THEN INSERT (" + columns + ") VALUES (" + sourceColumns + ")"));
            attempts.add(sql("MERGE UPDATE " + label, "UPDATE", "MERGE INTO " + table + " t USING (SELECT * FROM "
                    + table + " WHERE " + predicate + ") s ON t." + identity + "=s." + identity
                    + (names.length > 1 && target.membership() ? " AND t.user_id=s.user_id" : "")
                    + " WHEN MATCHED THEN UPDATE SET " + identity + "=t." + identity));
            attempts.add(sql("MERGE DELETE " + label, "DELETE", "MERGE INTO " + table + " t USING (SELECT * FROM "
                    + table + " WHERE " + predicate + ") s ON t." + identity + "=s." + identity
                    + (target.membership() ? " AND t.user_id=s.user_id" : "") + " WHEN MATCHED THEN DELETE"));
            String copy = zero ? "" : target.copy(fixture);
            attempts.add(new WriteAttempt("COPY " + label, "INSERT", connection -> connection.unwrap(PGConnection.class)
                    .getCopyAPI().copyIn("COPY " + table + " (" + columns + ") FROM STDIN", new StringReader(copy))));
        }
        attempts.add(sql("UPSERT no conflict", "INSERT", "INSERT INTO " + table + " (" + columns + ") VALUES "
                + values + " ON CONFLICT DO NOTHING"));
        attempts.add(sql("UPSERT conflict DO UPDATE", "INSERT", "INSERT INTO " + table + " SELECT * FROM " + table
                + " WHERE true ON CONFLICT (" + (target.membership() ? identity + ",user_id" : identity) + ") DO UPDATE SET "
                + identity + "=EXCLUDED." + identity));
        attempts.add(sql("TRUNCATE full FK closure, exact target first", "TRUNCATE", target.truncate()));
        return attempts;
    }

    private static WriteAttempt sql(String label, String operation, String sql) {
        return new WriteAttempt(label, operation, connection -> execute(connection, sql));
    }

    private static Fixture fixture(boolean v8, boolean withWorkspace) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        try (Connection connection = connection(v8, Connection.TRANSACTION_READ_COMMITTED)) {
            for (UUID user : List.of(a, b, c, d)) {
                execute(connection, "INSERT INTO public.users(id) VALUES ('" + user + "')");
                execute(connection, "INSERT INTO public.user_profiles(user_id) VALUES ('" + user + "')");
            }
            for (UUID organization : List.of(org, otherOrg)) {
                execute(connection, "INSERT INTO public.organizations(id) VALUES ('" + organization + "')");
                execute(connection, "INSERT INTO public.organization_memberships VALUES ('" + organization + "','" + a
                        + "','OWNER','ACTIVE'),('" + organization + "','" + b + "','OWNER','ACTIVE')");
            }
            if (withWorkspace) {
                execute(connection, "INSERT INTO public.workspaces VALUES ('" + workspace + "','ORGANIZATION',NULL,'" + org + "')");
                execute(connection, "INSERT INTO public.workspace_memberships VALUES ('" + workspace + "','" + a
                        + "','ADMIN','ACTIVE'),('" + workspace + "','" + b + "','ADMIN','ACTIVE')");
                execute(connection, "INSERT INTO public.workspaces VALUES ('" + otherWorkspace + "','USER','" + c + "',NULL)");
                execute(connection, "INSERT INTO public.workspace_memberships VALUES ('" + otherWorkspace + "','" + c + "','ADMIN','ACTIVE')");
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new AssertionError("Cannot create coherent disposable fixture", failure);
        }
        return new Fixture(a, b, c, d, org, workspace);
    }

    private static void remove(Connection connection, String table, Fixture fixture, UUID user) throws SQLException {
        boolean organization = table.equals("organization_memberships");
        try (var statement = connection.prepareStatement("DELETE FROM public." + table + " WHERE "
                + (organization ? "organization_id" : "workspace_id") + "=? AND user_id=?")) {
            statement.setObject(1, organization ? fixture.org() : fixture.workspace());
            statement.setObject(2, user);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private static int count(boolean v8, String table, String key, UUID value) {
        return database(v8).queryForObject("SELECT count(*) FROM public." + table + " WHERE " + key + "=?", Integer.class, value);
    }

    private static Map<String, List<String>> snapshot(boolean v8) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (Connection reader = connection(v8, Connection.TRANSACTION_READ_COMMITTED)) {
            for (String table : DOMAIN_TABLES) {
                List<String> tableRows = new ArrayList<>();
                try (var statement = reader.createStatement(); var result = statement.executeQuery(
                        "SELECT to_jsonb(t)::text FROM public." + table + " t ORDER BY to_jsonb(t)::text")) {
                    while (result.next()) {
                        tableRows.add(result.getString(1));
                    }
                }
                rows.put(table, tableRows);
            }
            reader.commit();
        } catch (SQLException failure) {
            throw new AssertionError("Cannot read owned disposable database snapshot", failure);
        }
        return rows;
    }

    private static JdbcTemplate database(boolean v8) {
        assertThat(POSTGRES.isRunning()).isTrue();
        return new JdbcTemplate(new DriverManagerDataSource(url(v8), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static String url(boolean v8) {
        String original = POSTGRES.getJdbcUrl();
        return v8 ? original.replace("/" + POSTGRES.getDatabaseName(), "/" + V8_DATABASE) : original;
    }

    private static Connection connection(boolean v8, int isolation) throws SQLException {
        assertThat(POSTGRES.isRunning()).isTrue();
        Connection connection = DriverManager.getConnection(url(v8), POSTGRES.getUsername(), POSTGRES.getPassword());
        try {
            assertThat(text(connection, "SELECT current_database()")).isEqualTo(v8 ? V8_DATABASE : POSTGRES.getDatabaseName());
            assertThat(text(connection, "SHOW server_version")).startsWith("18.4");
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            execute(connection, "SET LOCAL lock_timeout='30s'");
            execute(connection, "SET LOCAL statement_timeout='40s'");
            return connection;
        } catch (Throwable failure) {
            connection.close();
            throw failure;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql) throws SQLException {
        return Long.parseLong(text(connection, sql));
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static String isolationName(int isolation) {
        return switch (isolation) {
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "read uncommitted";
            case Connection.TRANSACTION_READ_COMMITTED -> "read committed";
            case Connection.TRANSACTION_REPEATABLE_READ -> "repeatable read";
            case Connection.TRANSACTION_SERIALIZABLE -> "serializable";
            default -> throw new IllegalArgumentException("Unexpected isolation " + isolation);
        };
    }

    private static PSQLException rootPostgres(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(PSQLException.class);
        return (PSQLException) root;
    }

    private static void assertGuard(Throwable failure, String schema, String table, String operation, String isolation) {
        assertGuard(failure, schema, table, operation, isolation, MARKER);
    }

    private static void assertGuard(Throwable failure, String schema, String table, String operation, String isolation, String marker) {
        PSQLException error = rootPostgres(failure);
        assertThat(error.getSQLState()).isEqualTo("0A000");
        var diagnostic = error.getServerErrorMessage();
        assertThat(diagnostic).isNotNull();
        assertThat(diagnostic.getMessage()).isEqualTo(marker + ": foundation write requires READ COMMITTED");
        assertThat(diagnostic.getSchema()).isEqualTo(schema);
        assertThat(diagnostic.getTable()).isEqualTo(table);
        assertThat(diagnostic.getDetail()).isEqualTo("operation=" + operation + "; actual_isolation=" + isolation);
    }

    private record Fixture(UUID a, UUID b, UUID c, UUID d, UUID org, UUID workspace) { }
    private record WriteAttempt(String label, String operation, SqlAction action) { }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection connection) throws Exception;
    }

    private enum Target {
        ORGANIZATIONS("organizations", "id"),
        ORGANIZATION_MEMBERSHIPS("organization_memberships", "organization_id,user_id,role,status"),
        WORKSPACES("workspaces", "id,owner_type,owner_user_id,owner_organization_id"),
        WORKSPACE_MEMBERSHIPS("workspace_memberships", "workspace_id,user_id,role,status");

        final String table;
        final String columns;

        Target(String table, String columns) {
            this.table = table;
            this.columns = columns;
        }

        boolean membership() {
            return this == ORGANIZATION_MEMBERSHIPS || this == WORKSPACE_MEMBERSHIPS;
        }

        String values(Fixture fixture) {
            return switch (this) {
                case ORGANIZATIONS -> "('" + UUID.randomUUID() + "'::uuid),('" + UUID.randomUUID() + "'::uuid)";
                case ORGANIZATION_MEMBERSHIPS -> "('" + fixture.org() + "'::uuid,'" + fixture.c()
                        + "'::uuid,'OWNER'::text,'ACTIVE'::text),('" + fixture.org() + "'::uuid,'" + fixture.d() + "'::uuid,'OWNER','ACTIVE')";
                case WORKSPACES -> "('" + UUID.randomUUID() + "'::uuid,'USER'::text,'" + fixture.c()
                        + "'::uuid,NULL::uuid),('" + UUID.randomUUID() + "'::uuid,'USER','" + fixture.d() + "'::uuid,NULL::uuid)";
                case WORKSPACE_MEMBERSHIPS -> "('" + fixture.workspace() + "'::uuid,'" + fixture.c()
                        + "'::uuid,'VIEWER'::text,'ACTIVE'::text),('" + fixture.workspace() + "'::uuid,'" + fixture.d() + "'::uuid,'VIEWER','ACTIVE')";
            };
        }

        String copy(Fixture fixture) {
            return switch (this) {
                case ORGANIZATIONS -> UUID.randomUUID() + "\n" + UUID.randomUUID() + "\n";
                case ORGANIZATION_MEMBERSHIPS -> fixture.org() + "\t" + fixture.c() + "\tOWNER\tACTIVE\n";
                case WORKSPACES -> UUID.randomUUID() + "\tUSER\t" + fixture.c() + "\t\\N\n";
                case WORKSPACE_MEMBERSHIPS -> fixture.workspace() + "\t" + fixture.c() + "\tVIEWER\tACTIVE\n";
            };
        }

        String truncate() {
            String closure = switch (this) {
                case ORGANIZATIONS -> "organizations,organization_memberships,workspaces,workspace_memberships,workspace_membership_scopes,ready_made_products,ready_made_product_manual_quantity_delta_commands";
                case ORGANIZATION_MEMBERSHIPS -> "organization_memberships";
                case WORKSPACES -> "workspaces,workspace_memberships,workspace_membership_scopes,ready_made_products,ready_made_product_manual_quantity_delta_commands";
                case WORKSPACE_MEMBERSHIPS -> "workspace_memberships,workspace_membership_scopes";
            };
            return "TRUNCATE public." + closure.replace(",", ",public.") + " CONTINUE IDENTITY RESTRICT";
        }
    }
}
