package com.creastrix.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.organization.persistence.JdbcOrganizationRepository;
import com.creastrix.platform.readymadeproduct.application.ReadyMadeProductService;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaCommandState;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaRejectionReason;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaResult;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.readymadeproduct.persistence.JdbcReadyMadeProductRepository;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.user.persistence.JdbcUserRepository;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotActiveException;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotOrganizationOwnerException;
import com.creastrix.platform.workspace.persistence.JdbcWorkspaceRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Actual Flyway upgrades, exclusively against this test's disposable database. */
@Testcontainers
@Timeout(120)
class FoundationMigrationUpgradeIntegrationTest {

    private static final String MIGRATION_ISOLATION = "CREASTRIX_FOUNDATION_MIGRATION_ISOLATION_V1";
    private static final String MIGRATION_VALIDATION = "CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1";
    private static final List<String> DOMAIN_TABLES = List.of(
            "users", "user_profiles", "organizations", "organization_memberships",
            "workspaces", "workspace_memberships", "workspace_membership_scopes",
            "ready_made_products", "ready_made_product_manual_quantity_delta_commands");
    private static final List<String> FOUNDATION_TABLES = List.of(
            "organizations", "organization_memberships", "workspaces", "workspace_memberships");

    // A new container/database for every invocation: invalid fixtures never reach another test or a host DB.
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("foundation_upgrade_" + UUID.randomUUID().toString().replace("-", ""));

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void establishOwnedDisposableDatabase() {
        assertThat(postgres.isRunning()).isTrue();
        source = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Properties limits = new Properties();
        limits.setProperty("connectTimeout", "5");
        limits.setProperty("socketTimeout", "50");
        source.setConnectionProperties(limits);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(40);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertOwnedDatabase(source);
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isEqualTo(180004);
    }

    @Test
    void freshDatabaseMigratesThroughActualV1ToV9() {
        MigrationTrace trace = new MigrationTrace(false);
        var result = migrate("9", new ObservedDataSource(source, trace, null, null));
        assertThat(result.migrationsExecuted).isEqualTo(9);
        assertVersionNineInstalled();
        trace.assertSuccessfulProtocol();
        for (String table : DOMAIN_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public." + table, Integer.class))
                    .as("fresh %s", table).isZero();
        }
    }

    @Test
    void populatedV8UpgradePreservesAllNineTablesAndExistingDatabaseSemantics() {
        migrate("8", source);
        try (AnnotationConfigApplicationContext context = serviceContext()) {
            UserService users = context.getBean(UserService.class);
            WorkspaceService workspaces = context.getBean(WorkspaceService.class);
            ReadyMadeProductService products = context.getBean(ReadyMadeProductService.class);
            OrganizationService organizations = context.getBean(OrganizationService.class);
            assertThat(AopUtils.isAopProxy(products)).isTrue();
            UUID actor = users.createUser().id();
            UUID historic = users.createUser().id();
            UUID organization = organizations.createOrganization(actor).id();
            jdbc.update("INSERT INTO public.organization_memberships VALUES (?, ?, 'OWNER', 'ACTIVE')",
                    organization, historic);
            UUID personal = workspaces.createUserOwnedWorkspace(historic).id();
            UUID firstWorkspace = workspaces.createOrganizationOwnedWorkspace(organization, actor).id();
            UUID secondWorkspace = workspaces.createOrganizationOwnedWorkspace(organization, historic).id();
            jdbc.update("INSERT INTO public.workspace_memberships VALUES (?, ?, 'ADMIN', 'ACTIVE')",
                    firstWorkspace, historic);
            jdbc.update("INSERT INTO public.workspace_membership_scopes VALUES (?, ?, 'READY_MADE_PRODUCTS')",
                    firstWorkspace, actor);
            UUID maximum = products.createReadyMadeProduct(personal, historic, Long.MAX_VALUE).id();
            UUID zero = products.createReadyMadeProduct(secondWorkspace, historic, 0).id();
            UUID ordinary = products.createReadyMadeProduct(firstWorkspace, actor, 10).id();
            products.archiveReadyMadeProduct(zero, historic);
            UUID registered = UUID.randomUUID();
            products.registerManualQuantityDelta(ordinary, registered, 3, actor);
            UUID applied = UUID.randomUUID();
            products.registerManualQuantityDelta(ordinary, applied, 2, actor);
            ManualQuantityDeltaResult appliedResult = products.applyManualQuantityDelta(ordinary, applied, 2, actor);
            UUID rejected = UUID.randomUUID();
            products.registerManualQuantityDelta(ordinary, rejected, -20, actor);
            ManualQuantityDeltaResult rejectedResult = products.applyManualQuantityDelta(ordinary, rejected, -20, actor);
            assertThat(appliedResult.state()).isEqualTo(ManualQuantityDeltaCommandState.APPLIED);
            assertThat(rejectedResult.state()).isEqualTo(ManualQuantityDeltaCommandState.REJECTED);
            users.changeStatus(historic, UserStatus.SUSPENDED);

            Map<String, List<String>> beforeRows = domainSnapshot();
            assertThat(beforeRows).allSatisfy((table, rows) -> assertThat(rows).as(table).isNotEmpty());
            Map<String, List<String>> beforeMetadata = legacyMetadata();
            List<Map<String, Object>> beforeHistory = versionEightHistory();
            MigrationTrace trace = new MigrationTrace(false);
            assertThat(migrate("9", new ObservedDataSource(source, trace, null, null)).migrationsExecuted).isOne();

            assertVersionNineInstalled();
            trace.assertSuccessfulProtocol();
            assertThat(domainSnapshot()).isEqualTo(beforeRows);
            assertThat(legacyMetadata()).isEqualTo(beforeMetadata);
            assertThat(versionEightHistory()).isEqualTo(beforeHistory);
            assertThat(products.findReadyMadeProduct(maximum).availableQuantity()).isEqualTo(Long.MAX_VALUE);
            assertThat(products.findReadyMadeProduct(zero).status()).isEqualTo(ReadyMadeProductStatus.ARCHIVED);
            assertThat(products.applyManualQuantityDelta(ordinary, applied, 2, actor)).isEqualTo(appliedResult);
            assertThat(products.applyManualQuantityDelta(ordinary, rejected, -20, actor)).isEqualTo(rejectedResult);
            assertThat(products.findReadyMadeProduct(ordinary).availableQuantity()).isEqualTo(12);
            assertThat(products.applyManualQuantityDelta(ordinary, registered, 3, actor).resultingAvailableQuantity())
                    .isEqualTo(15);
            assertThat(products.archiveReadyMadeProduct(ordinary, actor).status()).isEqualTo(ReadyMadeProductStatus.ARCHIVED);
            assertThat(products.activateReadyMadeProduct(ordinary, actor).status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
            assertThat(workspaces.createUserOwnedWorkspace(actor).ownerId()).isEqualTo(actor);
        }
    }

    @Test
    void freshDatabaseMigratesThroughActualV1ToV10() {
        assertThat(migrate("10", source).migrationsExecuted).isEqualTo(10);
        assertVersionTenInstalled();
        String function = workspaceCreationFunction();
        assertThreeCreationNoKeyUpdateSites(function);
        assertThat(workspaceCreationTriggerBinding()).isEqualTo(workspaceCreationFunctionOid());
        for (String table : DOMAIN_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public." + table, Integer.class))
                    .as("fresh V10 %s", table).isZero();
        }
        try (AnnotationConfigApplicationContext context = serviceContext()) {
            UserService users = context.getBean(UserService.class);
            WorkspaceService workspaces = context.getBean(WorkspaceService.class);
            OrganizationService organizations = context.getBean(OrganizationService.class);
            UUID actor = users.createUser().id();
            assertThat(workspaces.createUserOwnedWorkspace(actor).ownerId()).isEqualTo(actor);
            UUID organization = organizations.createOrganization(actor).id();
            assertThat(workspaces.createOrganizationOwnedWorkspace(organization, actor).ownerId())
                    .isEqualTo(organization);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "8"})
    void populatedUpgradeToV10ChangesOnlyThreeCreationLocksAndPreservesAllDomainRows(String initialVersion) {
        migrate(initialVersion, source);
        try (AnnotationConfigApplicationContext context = serviceContext()) {
            UserService users = context.getBean(UserService.class);
            WorkspaceService workspaces = context.getBean(WorkspaceService.class);
            ReadyMadeProductService products = context.getBean(ReadyMadeProductService.class);
            OrganizationService organizations = context.getBean(OrganizationService.class);
            assertThat(AopUtils.isAopProxy(workspaces)).isTrue();
            assertThat(AopUtils.isAopProxy(products)).isTrue();
            UUID actor = users.createUser().id();
            UUID historic = users.createUser().id();
            UUID outsider = users.createUser().id();
            UUID organization = organizations.createOrganization(actor).id();
            jdbc.update("INSERT INTO public.organization_memberships VALUES (?, ?, 'OWNER', 'ACTIVE')",
                    organization, historic);
            UUID personal = workspaces.createUserOwnedWorkspace(historic).id();
            UUID firstWorkspace = workspaces.createOrganizationOwnedWorkspace(organization, actor).id();
            UUID secondWorkspace = workspaces.createOrganizationOwnedWorkspace(organization, historic).id();
            // Different OWNER/ADMIN intersections survive per Workspace, including the suspended historical owner.
            jdbc.update("INSERT INTO public.workspace_memberships VALUES (?, ?, 'ADMIN', 'ACTIVE')",
                    firstWorkspace, outsider);
            jdbc.update("INSERT INTO public.workspace_membership_scopes VALUES (?, ?, 'READY_MADE_PRODUCTS')",
                    firstWorkspace, outsider);
            UUID maximum = products.createReadyMadeProduct(personal, historic, Long.MAX_VALUE).id();
            UUID zero = products.createReadyMadeProduct(secondWorkspace, historic, 0).id();
            UUID ordinary = products.createReadyMadeProduct(firstWorkspace, actor, 10).id();
            products.archiveReadyMadeProduct(zero, historic);
            UUID registered = UUID.randomUUID();
            ManualQuantityDeltaResult registeredResult = products.registerManualQuantityDelta(ordinary, registered, 3, actor);
            UUID applied = UUID.randomUUID();
            products.registerManualQuantityDelta(ordinary, applied, 2, actor);
            ManualQuantityDeltaResult appliedResult = products.applyManualQuantityDelta(ordinary, applied, 2, actor);
            UUID rejected = UUID.randomUUID();
            products.registerManualQuantityDelta(ordinary, rejected, -20, actor);
            ManualQuantityDeltaResult rejectedResult = products.applyManualQuantityDelta(ordinary, rejected, -20, actor);
            UUID overflow = UUID.randomUUID();
            products.registerManualQuantityDelta(maximum, overflow, 1, historic);
            ManualQuantityDeltaResult overflowResult = products.applyManualQuantityDelta(maximum, overflow, 1, historic);
            assertThat(registeredResult).isEqualTo(ManualQuantityDeltaResult.registered(ordinary, registered, 3));
            assertThat(appliedResult).isEqualTo(ManualQuantityDeltaResult.applied(ordinary, applied, 2, 12));
            assertThat(rejectedResult).isEqualTo(ManualQuantityDeltaResult.rejected(
                    ordinary, rejected, -20, ManualQuantityDeltaRejectionReason.UNDERFLOW, 12));
            assertThat(overflowResult).isEqualTo(ManualQuantityDeltaResult.rejected(
                    maximum, overflow, 1, ManualQuantityDeltaRejectionReason.OVERFLOW, Long.MAX_VALUE));
            users.changeStatus(historic, UserStatus.SUSPENDED);

            Map<String, List<String>> beforeRows = domainSnapshot();
            assertThat(beforeRows).allSatisfy((table, rows) -> assertThat(rows).as(table).isNotEmpty());
            if (initialVersion.equals("8")) {
                Map<String, List<String>> beforeV9Metadata = legacyMetadata();
                List<Map<String, Object>> beforeV9History = versionEightHistory();
                MigrationTrace trace = new MigrationTrace(false);
                assertThat(migrate("9", new ObservedDataSource(source, trace, null, null)).migrationsExecuted).isOne();
                assertVersionNineInstalled();
                trace.assertSuccessfulProtocol();
                assertThat(domainSnapshot()).isEqualTo(beforeRows);
                assertThat(legacyMetadata()).isEqualTo(beforeV9Metadata);
                assertThat(versionEightHistory()).isEqualTo(beforeV9History);
            }
            Map<String, List<String>> beforeMetadata = completeVersionNineMetadata();
            List<Map<String, Object>> beforeHistory = jdbc.queryForList(
                    "SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
            long functionOid = workspaceCreationFunctionOid();
            assertThat(workspaceCreationTriggerBinding()).isEqualTo(functionOid);
            String beforeFunction = workspaceCreationFunction();
            assertThat(beforeFunction.split("FOR UPDATE", -1)).hasSize(4);
            assertThat(beforeFunction).doesNotContain("FOR NO KEY UPDATE");

            assertThat(migrate("10", source).migrationsExecuted).isOne();
            assertVersionTenInstalled();
            assertThat(domainSnapshot()).isEqualTo(beforeRows);
            assertThat(jdbc.queryForList("SELECT * FROM public.flyway_schema_history WHERE version <> '10' "
                    + "ORDER BY installed_rank")).isEqualTo(beforeHistory);
            assertThat(workspaceCreationFunctionOid()).isEqualTo(functionOid);
            assertThat(workspaceCreationTriggerBinding()).isEqualTo(functionOid);
            String expectedFunction = beforeFunction.replace("FOR UPDATE", "FOR NO KEY UPDATE");
            assertThat(workspaceCreationFunction()).isEqualTo(expectedFunction);
            assertThreeCreationNoKeyUpdateSites(expectedFunction);
            String beforeEntry = functionOid + ":" + beforeFunction;
            assertThat(beforeMetadata.get("functions").stream().filter(beforeEntry::equals).count()).isOne();
            Map<String, List<String>> expectedMetadata = new LinkedHashMap<>(beforeMetadata);
            expectedMetadata.put("functions", beforeMetadata.get("functions").stream()
                    .map(entry -> entry.equals(beforeEntry) ? functionOid + ":" + expectedFunction : entry).toList());
            assertThat(completeVersionNineMetadata()).isEqualTo(expectedMetadata);

            assertThat(users.findUser(historic).status()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(products.findReadyMadeProduct(maximum).availableQuantity()).isEqualTo(Long.MAX_VALUE);
            assertThat(products.findReadyMadeProduct(zero).availableQuantity()).isZero();
            assertThat(products.findReadyMadeProduct(zero).status()).isEqualTo(ReadyMadeProductStatus.ARCHIVED);
            assertThat(products.registerManualQuantityDelta(ordinary, registered, 3, actor)).isEqualTo(registeredResult);
            assertThat(products.applyManualQuantityDelta(ordinary, applied, 2, actor)).isEqualTo(appliedResult);
            assertThat(products.applyManualQuantityDelta(ordinary, rejected, -20, actor)).isEqualTo(rejectedResult);
            assertThat(domainSnapshot()).as("authorized replay must preserve all historical rows").isEqualTo(beforeRows);
            assertThat(products.applyManualQuantityDelta(ordinary, registered, 3, actor))
                    .isEqualTo(ManualQuantityDeltaResult.applied(ordinary, registered, 3, 15));
            assertThat(products.archiveReadyMadeProduct(ordinary, actor).status()).isEqualTo(ReadyMadeProductStatus.ARCHIVED);
            assertThat(products.activateReadyMadeProduct(ordinary, actor).status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
            assertThat(workspaces.createUserOwnedWorkspace(actor).ownerId()).isEqualTo(actor);
            assertThat(workspaces.createOrganizationOwnedWorkspace(organization, actor).ownerId()).isEqualTo(organization);

            Map<String, List<String>> beforeInvalidOperations = domainSnapshot();
            assertThat(catchThrowable(() -> workspaces.createUserOwnedWorkspace(historic)))
                    .isInstanceOf(WorkspaceCreatorNotActiveException.class);
            assertThat(catchThrowable(() -> workspaces.createOrganizationOwnedWorkspace(organization, outsider)))
                    .isInstanceOf(WorkspaceCreatorNotOrganizationOwnerException.class);
            Throwable violation = catchThrowable(() -> jdbc.update(
                    "DELETE FROM public.workspace_memberships WHERE workspace_id=? AND user_id=?", personal, historic));
            PSQLException error = rootPostgres(violation);
            assertThat(error.getSQLState()).isEqualTo("23514");
            assertThat(error.getServerErrorMessage()).isNotNull();
            assertThat(error.getServerErrorMessage().getMessage()).isEqualTo(
                    "User-owned Workspace %s must retain its owner User %s as an ACTIVE ADMIN Workspace Membership"
                            .formatted(personal, historic));
            assertThat(domainSnapshot()).isEqualTo(beforeInvalidOperations);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"organization_active_owner", "user_workspace_owner_admin",
            "workspace_active_admin", "organization_workspace_owner_admin"})
    void invalidPreexistingPredicateRollsBackTheWholeMigrationWithoutRepair(String predicate) throws Exception {
        migrate("8", source);
        UUID first = user();
        UUID second = user();
        UUID organization = organization(first, second);
        if (predicate.equals("organization_active_owner")) {
            writeSkew("organization_memberships", "organization_id", organization, first,
                    "organization_memberships", "organization_id", organization, second);
        } else if (predicate.equals("user_workspace_owner_admin")) {
            UUID workspace = userWorkspace(first, second);
            Map<String, List<String>> enabledMetadata = legacyMetadata();
            // Deliberate damaged-data fixture only, in our owned disposable database. Unlike
            // the overlap case, this predicate cannot be broken by own-row V8 snapshot skew.
            // Keep FK/CHECKs and every other trigger; restore this exact trigger before migration.
            transactions.executeWithoutResult(status -> {
                jdbc.execute("ALTER TABLE public.workspace_memberships DISABLE TRIGGER workspace_memberships_preserve_foundation");
                jdbc.update("UPDATE public.workspace_memberships SET role='VIEWER' WHERE workspace_id=? AND user_id=?",
                        workspace, first);
                jdbc.execute("ALTER TABLE public.workspace_memberships ENABLE TRIGGER workspace_memberships_preserve_foundation");
            });
            assertThat(legacyMetadata()).isEqualTo(enabledMetadata);
        } else {
            UUID workspace = organizationWorkspace(organization, first, second);
            if (predicate.equals("workspace_active_admin")) {
                writeSkew("workspace_memberships", "workspace_id", workspace, first,
                        "workspace_memberships", "workspace_id", workspace, second);
            } else {
                writeSkew("workspace_memberships", "workspace_id", workspace, first,
                        "organization_memberships", "organization_id", organization, second);
                assertThat(count("workspace_memberships", "workspace_id", workspace)).isOne();
                assertThat(count("organization_memberships", "organization_id", organization)).isOne();
            }
        }
        Map<String, List<String>> before = domainSnapshot();
        Map<String, List<String>> metadata = legacyMetadata();
        List<Map<String, Object>> history = versionEightHistory();
        MigrationTrace trace = new MigrationTrace(false);
        Throwable error = catchThrowable(() -> migrate("9", new ObservedDataSource(source, trace, null, null)));
        assertMigrationError(error, "23514", MIGRATION_VALIDATION, "predicate=" + predicate);
        trace.assertRejectedValidationProtocol();
        assertVersionNineAbsent();
        assertThat(domainSnapshot()).isEqualTo(before);
        assertThat(legacyMetadata()).isEqualTo(metadata);
        assertThat(versionEightHistory()).isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read uncommitted", "repeatable read", "serializable"})
    void migrationRejectsActualNonRcEvenWhenTheSessionDefaultIsReadCommitted(String actualIsolation) {
        migrate("8", source);
        Map<String, List<String>> before = domainSnapshot();
        MigrationTrace trace = new MigrationTrace(false);
        Throwable error = catchThrowable(() -> migrate("9",
                new ObservedDataSource(source, trace, "read committed", actualIsolation)));
        PSQLException postgresError = rootPostgres(error);
        assertThat(postgresError.getSQLState()).isEqualTo("0A000");
        assertThat(postgresError.getServerErrorMessage()).isNotNull();
        assertThat(postgresError.getServerErrorMessage().getMessage()).contains(MIGRATION_ISOLATION);
        assertThat(trace.actualIsolation).isEqualTo(actualIsolation);
        assertThat(trace.defaultIsolation).isEqualTo("read committed");
        assertThat(trace.events).containsExactly("isolation", "rollback");
        assertVersionNineAbsent();
        assertThat(domainSnapshot()).isEqualTo(before);
    }

    @Test
    void actualReadCommittedMigrationIsAcceptedDespiteRepeatableReadSessionDefault() {
        migrate("8", source);
        MigrationTrace trace = new MigrationTrace(false);
        assertThat(migrate("9", new ObservedDataSource(source, trace,
                "repeatable read", "read committed")).migrationsExecuted).isOne();
        assertThat(trace.actualIsolation).isEqualTo("read committed");
        assertThat(trace.defaultIsolation).isEqualTo("repeatable read");
        trace.assertSuccessfulProtocol();
        assertVersionNineInstalled();
    }

    @Test
    void unexpectedFoundationDescendantStopsMigrationWithoutPartialEnforcement() {
        migrate("8", source);
        jdbc.execute("CREATE TABLE public.upgrade_organization_descendant () INHERITS (public.organizations)");
        Map<String, List<String>> metadata = legacyMetadata();
        MigrationTrace trace = new MigrationTrace(false);
        Throwable failure = catchThrowable(() -> migrate("9", new ObservedDataSource(source, trace, null, null)));
        PSQLException error = rootPostgres(failure);
        assertThat(error.getSQLState()).isEqualTo("55000");
        assertThat(error.getServerErrorMessage()).isNotNull();
        assertThat(error.getServerErrorMessage().getMessage())
                .isEqualTo("CREASTRIX_FOUNDATION_MIGRATION_BOUNDARY_V1: foundation descendants are unsupported");
        trace.assertRejectedValidationProtocol();
        assertVersionNineAbsent();
        assertThat(legacyMetadata()).isEqualTo(metadata);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.upgrade_organization_descendant')::text", String.class))
                .isEqualTo("upgrade_organization_descendant");
    }

    @RepeatedTest(3)
    void migrationValidationSeesV8WriterCommitAfterWaitingForTheFullBarrier() throws Exception {
        migrate("8", source);
        UUID firstOwner = user();
        UUID secondOwner = user();
        UUID organization = organization(firstOwner, secondOwner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.workspaces", Integer.class)).isZero();
        MigrationTrace trace = new MigrationTrace(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> migration = null;
        try (Connection first = transaction(Connection.TRANSACTION_REPEATABLE_READ);
             Connection writer = transaction(Connection.TRANSACTION_REPEATABLE_READ)) {
            try {
                establishTwoOwnerSnapshot(first, organization);
                establishTwoOwnerSnapshot(writer, organization);
                int writerPid = scalarInt(writer, "SELECT pg_backend_pid()");
                delete(first, "organization_memberships", "organization_id", organization, firstOwner);
                first.commit();
                delete(writer, "organization_memberships", "organization_id", organization, secondOwner);
                assertThat(count("organization_memberships", "organization_id", organization)).isOne();
                migration = executor.submit(() -> catchThrowable(() -> migrate("9",
                        new ObservedDataSource(source, trace, null, null))));
                await(trace.migrationEntered, "actual migration entry");
                assertThat(trace.pid.get()).isNotEqualTo(writerPid);
                assertThat(trace.actualIsolation).isEqualTo("read committed");
                assertPartialBarrierWait(trace, writerPid, migration);
                assertThat(trace.events).containsExactly("isolation", "locked:organizations");
                trace.events.add("writer-commit-start");
                writer.commit();
                trace.events.add("writer-commit-return");
                assertThat(count("organization_memberships", "organization_id", organization)).isZero();
                trace.writerCommitObserved.countDown();
                Throwable failure = migration.get(60, TimeUnit.SECONDS);
                assertMigrationError(failure, "23514", MIGRATION_VALIDATION,
                        "predicate=organization_active_owner");
                assertThat(trace.events).containsExactly(
                        "isolation", "locked:organizations", "writer-commit-start", "writer-commit-return",
                        "locked:organization_memberships", "locked:workspaces", "locked:workspace_memberships",
                        "validation", "rollback");
                assertVersionNineAbsent();
                assertThat(count("organization_memberships", "organization_id", organization)).isZero();
                assertThat(count("organizations", "id", organization)).isOne();
                System.out.printf("V9_UPGRADE_OVERLAP migration_pid=%d writer_pid=%d actual_isolation=%s trace=%s%n",
                        trace.pid.get(), writerPid, trace.actualIsolation, trace.events);
            } finally {
                trace.writerCommitObserved.countDown();
                first.rollback();
                writer.rollback();
            }
        } finally {
            trace.writerCommitObserved.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("upgrade worker cleanup").isTrue();
            if (migration != null && !migration.isDone()) {
                throw new AssertionError("Migration worker did not finish within bounded cleanup");
            }
        }
    }

    private org.flywaydb.core.api.output.MigrateResult migrate(String target, DataSource dataSource) {
        assertOwnedDatabase(source);
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).executeInTransaction(true).group(false)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load().migrate();
    }

    private void assertOwnedDatabase(DataSource dataSource) {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(source.getUrl()).isEqualTo(postgres.getJdbcUrl());
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT current_database()", String.class))
                .isEqualTo(postgres.getDatabaseName()).startsWith("foundation_upgrade_");
    }

    private void assertVersionNineInstalled() {
        assertThat(jdbc.queryForList("SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid "
                + "JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname='public' "
                + "AND t.tgname IN ('organizations_require_read_committed','organization_memberships_require_read_committed',"
                + "'workspaces_require_read_committed','workspace_memberships_require_read_committed')", Integer.class)).isEqualTo(4);
    }

    private void assertVersionNineAbsent() {
        assertThat(jdbc.queryForList("SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='9'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_regprocedure('public.foundation_require_read_committed()')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid "
                + "JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname='public' "
                + "AND t.tgname IN ('organizations_require_read_committed','organization_memberships_require_read_committed',"
                + "'workspaces_require_read_committed','workspace_memberships_require_read_committed')", Integer.class)).isZero();
    }

    private void assertVersionTenInstalled() {
        assertThat(jdbc.queryForList("SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE NOT success", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList("""
                SELECT r.relname FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=r.relnamespace
                WHERE n.nspname='public' AND t.tgfoid='public.foundation_require_read_committed()'::regprocedure
                  AND NOT t.tgisinternal AND t.tgenabled='O'
                ORDER BY r.relname
                """, String.class)).containsExactly("organization_memberships", "organizations",
                        "workspace_memberships", "workspaces");
    }

    private long workspaceCreationFunctionOid() {
        return jdbc.queryForObject("SELECT 'public.workspaces_require_initial_foundation()'::regprocedure::oid::bigint", Long.class);
    }

    private String workspaceCreationFunction() {
        return jdbc.queryForObject("SELECT pg_get_functiondef('public.workspaces_require_initial_foundation()'::regprocedure)",
                String.class);
    }

    private long workspaceCreationTriggerBinding() {
        return jdbc.queryForObject("""
                SELECT t.tgfoid::bigint FROM pg_trigger t
                WHERE t.tgrelid='public.workspaces'::regclass
                  AND t.tgname='workspaces_require_initial_foundation' AND NOT t.tgisinternal
                  AND t.tgdeferrable AND t.tginitdeferred AND t.tgtype=5 AND t.tgenabled='O'
                """, Long.class);
    }

    private void assertThreeCreationNoKeyUpdateSites(String function) {
        assertThat(function.split("FOR NO KEY UPDATE", -1)).hasSize(4);
        assertThat(function).doesNotContain("FOR UPDATE")
                .contains("SELECT status INTO owner_status FROM users WHERE id = NEW.owner_user_id FOR NO KEY UPDATE;")
                .contains("PERFORM 1 FROM organizations WHERE id = NEW.owner_organization_id FOR NO KEY UPDATE;")
                .contains("ORDER BY u.id\n        FOR NO KEY UPDATE;");
    }

    private Map<String, List<String>> completeVersionNineMetadata() {
        // V9's legacy comparator deliberately omits only V9's new objects. V10 must compare those too.
        Map<String, List<String>> metadata = new LinkedHashMap<>(legacyMetadata());
        metadata.put("functions", jdbc.queryForList("SELECT p.oid::text || ':' || pg_get_functiondef(p.oid) "
                + "FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                + "WHERE n.nspname='public' ORDER BY p.oid", String.class));
        metadata.put("triggers", jdbc.queryForList("""
                SELECT t.oid::text || ':' || t.tgrelid::text || ':' || t.tgfoid::text || ':'
                       || t.tgenabled::text || ':' || pg_get_triggerdef(t.oid, true)
                FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=r.relnamespace
                WHERE n.nspname='public' ORDER BY t.oid
                """, String.class));
        metadata.put("columns", jdbc.queryForList("""
                SELECT r.oid::text || ':' || a.attnum::text || ':' || a.attname || ':'
                       || format_type(a.atttypid,a.atttypmod) || ':' || a.attnotnull::text || ':'
                       || coalesce(pg_get_expr(d.adbin,d.adrelid), '')
                FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace
                JOIN pg_attribute a ON a.attrelid=r.oid
                LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                WHERE n.nspname='public' AND r.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
                ORDER BY r.oid,a.attnum
                """, String.class));
        return metadata;
    }

    private Map<String, List<String>> domainSnapshot() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : DOMAIN_TABLES) {
            snapshot.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM public." + table
                    + " t ORDER BY to_jsonb(t)::text", String.class));
        }
        return snapshot;
    }

    private List<Map<String, Object>> versionEightHistory() {
        return jdbc.queryForList("SELECT * FROM public.flyway_schema_history WHERE version IN "
                + "('1','2','3','4','5','6','7','8') ORDER BY installed_rank");
    }

    private Map<String, List<String>> legacyMetadata() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("constraints", jdbc.queryForList("SELECT c.oid::text || ':' || pg_get_constraintdef(c.oid, true) "
                + "FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid JOIN pg_namespace n ON n.oid=r.relnamespace "
                + "WHERE n.nspname='public' ORDER BY c.oid", String.class));
        result.put("indexes", jdbc.queryForList("SELECT i.indexrelid::text || ':' || pg_get_indexdef(i.indexrelid) "
                + "FROM pg_index i JOIN pg_class r ON r.oid=i.indrelid JOIN pg_namespace n ON n.oid=r.relnamespace "
                + "WHERE n.nspname='public' ORDER BY i.indexrelid", String.class));
        result.put("functions", jdbc.queryForList("SELECT p.oid::text || ':' || pg_get_functiondef(p.oid) "
                + "FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' "
                + "AND p.proname <> 'foundation_require_read_committed' ORDER BY p.oid", String.class));
        result.put("triggers", jdbc.queryForList("SELECT t.oid::text || ':' || t.tgenabled::text || ':' || pg_get_triggerdef(t.oid, true) "
                + "FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid JOIN pg_namespace n ON n.oid=r.relnamespace "
                + "WHERE n.nspname='public' AND NOT t.tgisinternal AND t.tgname NOT IN "
                + "('organizations_require_read_committed','organization_memberships_require_read_committed',"
                + "'workspaces_require_read_committed','workspace_memberships_require_read_committed') ORDER BY t.oid", String.class));
        return result;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO public.users (id) VALUES (?)", id);
            jdbc.update("INSERT INTO public.user_profiles (user_id) VALUES (?)", id);
        });
        return id;
    }

    private UUID organization(UUID first, UUID second) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO public.organizations (id) VALUES (?)", id);
            jdbc.update("INSERT INTO public.organization_memberships VALUES (?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                    id, first, id, second);
        });
        return id;
    }

    private UUID userWorkspace(UUID owner, UUID alternate) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO public.workspaces VALUES (?, 'USER', ?, NULL)", id, owner);
            memberships(id, owner, alternate);
        });
        return id;
    }

    private UUID organizationWorkspace(UUID owner, UUID first, UUID second) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO public.workspaces VALUES (?, 'ORGANIZATION', NULL, ?)", id, owner);
            memberships(id, first, second);
        });
        return id;
    }

    private void memberships(UUID workspace, UUID first, UUID second) {
        jdbc.update("INSERT INTO public.workspace_memberships VALUES (?, ?, 'ADMIN', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE')",
                workspace, first, workspace, second);
    }

    private int count(String table, String key, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM public." + table + " WHERE " + key + "=?", Integer.class, id);
    }

    private Connection transaction(int isolation) throws SQLException {
        assertOwnedDatabase(source);
        Connection connection = source.getConnection();
        connection.setTransactionIsolation(isolation);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout='30s'");
            statement.execute("SET LOCAL statement_timeout='40s'");
        }
        return connection;
    }

    private void writeSkew(String firstTable, String firstKey, UUID firstParent, UUID firstUser,
            String secondTable, String secondKey, UUID secondParent, UUID secondUser) throws Exception {
        try (Connection first = transaction(Connection.TRANSACTION_REPEATABLE_READ);
             Connection second = transaction(Connection.TRANSACTION_REPEATABLE_READ)) {
            try {
                // Fix both complete database snapshots before either writer changes a row.
                assertThat(scalarInt(first, "SELECT count(*) FROM public.organization_memberships")).isEqualTo(2);
                assertThat(scalarInt(second, "SELECT count(*) FROM public.organization_memberships")).isEqualTo(2);
                delete(first, firstTable, firstKey, firstParent, firstUser);
                delete(second, secondTable, secondKey, secondParent, secondUser);
                first.commit();
                second.commit();
            } finally {
                first.rollback();
                second.rollback();
            }
        }
    }

    private void establishTwoOwnerSnapshot(Connection connection, UUID organization) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM public.organization_memberships WHERE organization_id=?")) {
            statement.setObject(1, organization);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
        }
    }

    private void delete(Connection connection, String table, String key, UUID parent, UUID user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM public." + table + " WHERE " + key + "=? AND user_id=?")) {
            statement.setObject(1, parent);
            statement.setObject(2, user);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private void assertPartialBarrierWait(MigrationTrace trace, int writerPid, Future<?> migration) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (migration.isDone()) {
                throw new AssertionError("Migration completed before the expected partial barrier", (Throwable) migration.get());
            }
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity waiter JOIN pg_stat_activity holder ON holder.pid=?
                        WHERE waiter.pid=? AND waiter.datid=(SELECT oid FROM pg_database WHERE datname=current_database())
                          AND holder.datid=waiter.datid AND waiter.wait_event_type='Lock'
                          AND ?=ANY(pg_blocking_pids(waiter.pid))
                          AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid=waiter.pid AND l.granted
                              AND l.relation='public.organizations'::regclass AND l.mode='ShareRowExclusiveLock')
                          AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid=waiter.pid AND NOT l.granted
                              AND l.relation='public.organization_memberships'::regclass AND l.mode='ShareRowExclusiveLock')
                          AND NOT EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid=waiter.pid AND l.granted
                              AND l.relation IN ('public.workspaces'::regclass, 'public.workspace_memberships'::regclass)
                              AND l.mode='ShareRowExclusiveLock'))
                    """, Boolean.class, writerPid, trace.pid.get(), writerPid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("No exact migration PID → writer PID partial-barrier wait within 20 seconds");
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

    private static void assertMigrationError(Throwable failure, String sqlState, String marker, String detail) {
        PSQLException error = rootPostgres(failure);
        assertThat(error.getSQLState()).isEqualTo(sqlState);
        assertThat(error.getServerErrorMessage()).isNotNull();
        assertThat(error.getServerErrorMessage().getMessage()).contains(marker);
        assertThat(error.getServerErrorMessage().getDetail()).isEqualTo(detail);
    }

    private static void await(CountDownLatch gate, String name) throws InterruptedException {
        assertThat(gate.await(20, TimeUnit.SECONDS)).as(name).isTrue();
    }

    private AnnotationConfigApplicationContext serviceContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.register(JdbcUserRepository.class, UserService.class,
                JdbcOrganizationRepository.class, OrganizationService.class,
                JdbcWorkspaceRepository.class, WorkspaceService.class,
                JdbcReadyMadeProductRepository.class, ReadyMadeProductService.class);
        context.refresh();
        return context;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }

    /** Observes actual JDBC calls. No SQL replacement, fabricated result, or manual migration commit. */
    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final MigrationTrace trace;
        private final String defaultIsolation;
        private final String transactionIsolation;

        private ObservedDataSource(DataSource delegate, MigrationTrace trace,
                String defaultIsolation, String transactionIsolation) {
            this.delegate = delegate;
            this.trace = trace;
            this.defaultIsolation = defaultIsolation;
            this.transactionIsolation = transactionIsolation;
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
            try (Statement statement = raw.createStatement()) {
                statement.execute("SET lock_timeout='30s'");
                statement.execute("SET statement_timeout='40s'");
                if (defaultIsolation != null) {
                    statement.execute("SET default_transaction_isolation='" + isolationKeyword(defaultIsolation) + "'");
                }
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        boolean starting = method.getName().equals("setAutoCommit")
                                && Boolean.FALSE.equals(args[0]) && raw.getAutoCommit();
                        Object result = invoke(raw, method, args);
                        if (starting && transactionIsolation != null) {
                            try (Statement statement = raw.createStatement()) {
                                statement.execute("SET TRANSACTION ISOLATION LEVEL " + isolationKeyword(transactionIsolation));
                            }
                        }
                        if (method.getName().equals("commit") || method.getName().equals("rollback")) {
                            trace.transactionEnded(raw, method.getName());
                        }
                        if (result instanceof Statement statement) {
                            String preparedSql = args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
                            Class<?> statementType = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {statementType},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        String sql = statementArgs != null && statementArgs.length > 0
                                                && statementArgs[0] instanceof String text ? text : preparedSql;
                                        boolean execute = statementMethod.getName().startsWith("execute") && sql != null;
                                        if (execute) {
                                            trace.before(raw, sql);
                                        }
                                        Object statementResult = invoke(statement, statementMethod, statementArgs);
                                        if (execute) {
                                            trace.after(raw, sql);
                                        }
                                        return statementResult;
                                    });
                        }
                        return result;
                    });
        }

        private static String isolationKeyword(String isolation) {
            assertThat(isolation).isIn("read committed", "read uncommitted", "repeatable read", "serializable");
            return isolation;
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static final class MigrationTrace {
        private final boolean overlap;
        private final AtomicInteger pid = new AtomicInteger();
        private final CountDownLatch migrationEntered = new CountDownLatch(1);
        private final CountDownLatch writerCommitObserved = new CountDownLatch(1);
        private final List<String> events = new CopyOnWriteArrayList<>();
        private Connection migrationConnection;
        private Connection historyConnection;
        private int historyPid;
        private boolean historyAutoCommit;
        private String actualIsolation;
        private String defaultIsolation;

        private MigrationTrace(boolean overlap) {
            this.overlap = overlap;
        }

        private void before(Connection connection, String sql) throws Exception {
            if (migrationConnection == null && sql.contains(MIGRATION_ISOLATION) && !sql.contains(MIGRATION_VALIDATION)) {
                assertThat(connection.getAutoCommit()).as("real Flyway V9 physical transaction").isFalse();
                migrationConnection = connection;
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT pg_backend_pid(), current_setting('transaction_isolation'), current_setting('default_transaction_isolation')")) {
                    assertThat(result.next()).isTrue();
                    pid.set(result.getInt(1));
                    actualIsolation = result.getString(2);
                    defaultIsolation = result.getString(3);
                }
                events.add("isolation");
                migrationEntered.countDown();
            }
            if (connection == migrationConnection && sql.contains(MIGRATION_VALIDATION)) {
                assertThat(connection.getAutoCommit()).isFalse();
                List<String> locks = events.stream().filter(event -> event.startsWith("locked:")).toList();
                assertThat(locks).containsExactly("locked:organizations", "locked:organization_memberships",
                        "locked:workspaces", "locked:workspace_memberships");
                assertThat(actualIsolation).isEqualTo("read committed");
                events.add("validation");
            }
        }

        private void after(Connection connection, String sql) throws Exception {
            boolean historyInsert = migrationConnection != null
                    && sql.matches("(?is).*INSERT\\s+INTO\\s+.*flyway_schema_history.*");
            if (historyInsert) {
                historyConnection = connection;
                historyAutoCommit = connection.getAutoCommit();
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(result.next()).isTrue();
                    historyPid = result.getInt(1);
                }
                if (connection != migrationConnection) {
                    events.add("history-on-other-pid:" + historyPid);
                }
            }
            if (connection != migrationConnection) {
                return;
            }
            for (String table : FOUNDATION_TABLES) {
                if (sql.contains("LOCK TABLE ONLY public." + table + " IN SHARE ROW EXCLUSIVE MODE")) {
                    if (overlap && table.equals("organization_memberships")) {
                        // Only delays return from the real acquired lock; never changes the SQL or result.
                        await(writerCommitObserved, "independent observation of committed V8 writer");
                    }
                    assertThat(connection.getAutoCommit()).isFalse();
                    events.add("locked:" + table);
                }
                if (sql.contains("CREATE TRIGGER " + table + "_require_read_committed")) {
                    assertThat(events).contains("validation");
                    assertThat(connection.getAutoCommit()).isFalse();
                    events.add("trigger:" + table);
                }
            }
            if (sql.contains("CREATE FUNCTION public.foundation_require_read_committed")) {
                assertThat(events).contains("validation");
                assertThat(connection.getAutoCommit()).isFalse();
                events.add("function");
            }
            if (sql.matches("(?is).*INSERT\\s+INTO\\s+.*flyway_schema_history.*")) {
                assertThat(events).contains("function", "trigger:organizations", "trigger:organization_memberships",
                        "trigger:workspaces", "trigger:workspace_memberships");
                assertThat(connection.getAutoCommit()).isFalse();
                events.add("history");
            }
        }

        private void transactionEnded(Connection connection, String outcome) {
            if (connection == migrationConnection && !events.contains("commit") && !events.contains("rollback")) {
                events.add(outcome);
            }
            if (connection == historyConnection && connection != migrationConnection
                    && !events.contains("history-commit") && !events.contains("history-rollback")) {
                events.add("history-" + outcome);
            }
        }

        private void assertSuccessfulProtocol() {
            System.out.printf("V9_TRANSACTION_BOUNDARY migration_pid=%d history_pid=%d history_autocommit=%s trace=%s%n",
                    pid.get(), historyPid, historyAutoCommit, events);
            assertThat(historyPid).as("Flyway history and V9 DDL must use the same physical PostgreSQL connection")
                    .isEqualTo(pid.get());
            assertThat(historyAutoCommit).isFalse();
            assertThat(actualIsolation).isEqualTo("read committed");
            assertThat(events).containsExactly("isolation", "locked:organizations", "locked:organization_memberships",
                    "locked:workspaces", "locked:workspace_memberships", "validation", "function",
                    "trigger:organizations", "trigger:organization_memberships", "trigger:workspaces",
                    "trigger:workspace_memberships", "history", "commit");
        }

        private void assertRejectedValidationProtocol() {
            assertThat(events).containsExactly("isolation", "locked:organizations", "locked:organization_memberships",
                    "locked:workspaces", "locked:workspace_memberships", "validation", "rollback");
        }
    }
}
