package com.creastrix.platform.readymadeproduct;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.readymadeproduct.application.ReadyMadeProductService;
import com.creastrix.platform.readymadeproduct.application.port.ReadyMadeProductRepository;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductCreatorNotActiveException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductCreatorNotAuthorizedException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductNotFoundException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.readymadeproduct.persistence.JdbcReadyMadeProductRepository;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.Workspace;
import com.creastrix.platform.workspace.domain.WorkspacePermissionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests for the Ready-Made Product structural foundation against a
 * real PostgreSQL instance.
 *
 * <p>Database invariants are proven with raw SQL that bypasses the application
 * service, so they are shown to hold independently from the Java application
 * layer. Authentication and external caller identity proof are not implemented
 * and are not tested here.
 */
@SpringBootTest
@Testcontainers
class ReadyMadeProductFoundationIntegrationTest {

    private static final String CHECK_VIOLATION_SQL_STATE = "23514";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ReadyMadeProductService readyMadeProductService;

    @Autowired
    private ReadyMadeProductRepository readyMadeProductRepository;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------
    // Migration, schema, and wiring
    // ------------------------------------------------------------------

    @Test
    void realPostgresIsUsed() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("18.4");
        }
    }

    @Test
    void v6MigrationIsAppliedAfterV1ThroughV5() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void v6AddsExactlyTheReadyMadeProductsTable() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE '%product%' "
                        + "ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly("ready_made_products");
    }

    @Test
    void noListingPersistenceIsIntroduced() {
        var listingRelations = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE '%listing%'",
                String.class);
        assertThat(listingRelations).isEmpty();
    }

    @Test
    void schemaDeclaresExpectedColumnsAndTypes() {
        var columns = jdbcTemplate.queryForList(
                "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default "
                        + "FROM information_schema.columns c "
                        + "WHERE c.table_schema = 'public' "
                        + "AND c.table_name = 'ready_made_products' "
                        + "AND (c.table_schema || '.' || c.table_name)::regclass::oid::bigint = ? "
                        + "ORDER BY c.column_name",
                readyMadeProductsRelationOid());
        assertThat(columns)
                .extracting(row -> row.get("column_name"))
                .containsExactly(
                        "available_quantity", "created_by_user_id", "id", "status", "workspace_id");
        assertThat(columns)
                .extracting(row -> row.get("data_type"))
                .containsExactly("bigint", "uuid", "uuid", "text", "uuid");
        assertThat(columns)
                .allSatisfy(row -> assertThat(row.get("is_nullable")).isEqualTo("NO"));
        // No database defaults exist in V6: every value is written explicitly.
        assertThat(columns)
                .allSatisfy(row -> assertThat(row.get("column_default")).isNull());
    }

    @Test
    void schemaDeclaresExpectedKeysAndConstraints() {
        var constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = ? AND contype IN ('p', 'c', 'f') ORDER BY conname",
                String.class, readyMadeProductsRelationOid());
        assertThat(constraints).containsExactlyInAnyOrder(
                "ready_made_products_pk",
                "ready_made_products_workspace_fk",
                "ready_made_products_created_by_user_fk",
                "ready_made_products_status_allowed",
                "ready_made_products_available_quantity_non_negative");

        var pkColumns = jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid "
                        + "AND a.attnum = ANY (c.conkey) "
                        + "WHERE c.conrelid = ? AND c.oid = ?",
                String.class, readyMadeProductsRelationOid(),
                constraintOidOf("ready_made_products_pk"));
        assertThat(pkColumns).containsExactly("id");

        var foreignKeys = jdbcTemplate.queryForList(
                "SELECT conname, confdeltype, confrelid::regclass::text AS referenced "
                        + "FROM pg_constraint WHERE contype = 'f' AND conrelid = ? "
                        + "ORDER BY conname",
                readyMadeProductsRelationOid());
        assertThat(foreignKeys).hasSize(2);
        var createdByFk = foreignKeys.get(0);
        assertThat(createdByFk.get("conname"))
                .isEqualTo("ready_made_products_created_by_user_fk");
        assertThat(createdByFk.get("confdeltype")).isEqualTo("r");
        assertThat(createdByFk.get("referenced")).isEqualTo("users");
        var workspaceFk = foreignKeys.get(1);
        assertThat(workspaceFk.get("conname")).isEqualTo("ready_made_products_workspace_fk");
        assertThat(workspaceFk.get("confdeltype")).isEqualTo("r");
        assertThat(workspaceFk.get("referenced")).isEqualTo("workspaces");

        // The closed lifecycle set is asserted against the exact canonical
        // pg_get_constraintdef output of PostgreSQL 18.4, so adding any further
        // lifecycle value fails this test.
        assertThat(constraintDefinitionOf("ready_made_products_status_allowed")).isEqualTo(
                "CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'ARCHIVED'::text])))");
        assertThat(constraintDefinitionOf("ready_made_products_available_quantity_non_negative"))
                .isEqualTo("CHECK ((available_quantity >= 0))");
    }

    @Test
    void noSpeculativeIndexExistsBeyondThePrimaryKey() {
        var indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'ready_made_products' "
                        + "ORDER BY indexname",
                String.class);
        assertThat(indexes).containsExactly("ready_made_products_pk");
    }

    @Test
    void readyMadeProductTriggersAreDeclaredWithTheExpectedSemantics() {
        var triggers = jdbcTemplate.queryForList(
                "SELECT tgname, tgfoid, tgconstraint <> 0 AS is_constraint, tgdeferrable, "
                        + "tginitdeferred, pg_get_triggerdef(oid) AS definition "
                        + "FROM pg_trigger WHERE NOT tgisinternal "
                        + "AND tgrelid = ? ORDER BY tgname",
                readyMadeProductsRelationOid());
        assertThat(triggers)
                .extracting(row -> row.get("tgname"))
                .containsExactly(
                        "ready_made_products_forbid_delete",
                        "ready_made_products_forbid_identity_change",
                        "ready_made_products_forbid_truncate",
                        "ready_made_products_require_valid_creation",
                        "ready_made_products_revalidate_creation");

        // Phase 1 validates and locks BEFORE the row exists, so before the
        // implicit foreign-key row locks of the INSERT are taken.
        var phase1 = trigger(triggers, "ready_made_products_require_valid_creation");
        assertThat(phase1.get("is_constraint")).isEqualTo(false);
        assertThat(definitionOf(phase1))
                .contains("BEFORE INSERT ON public.ready_made_products")
                .contains("FOR EACH ROW")
                .doesNotContain("CONSTRAINT TRIGGER");

        // Phase 2 is the final commit-time revalidation.
        var creationGate = trigger(triggers, "ready_made_products_revalidate_creation");
        assertThat(creationGate.get("is_constraint")).isEqualTo(true);
        assertThat(creationGate.get("tgdeferrable")).isEqualTo(true);
        assertThat(creationGate.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(creationGate))
                .contains("CONSTRAINT TRIGGER")
                .contains("AFTER INSERT ON public.ready_made_products")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("FOR EACH ROW");
        assertThat(definitionOf(trigger(triggers, "ready_made_products_forbid_identity_change")))
                .contains("BEFORE UPDATE ON public.ready_made_products")
                .contains("FOR EACH ROW");
        assertThat(definitionOf(trigger(triggers, "ready_made_products_forbid_delete")))
                .contains("BEFORE DELETE ON public.ready_made_products")
                .contains("FOR EACH ROW");
        assertThat(definitionOf(trigger(triggers, "ready_made_products_forbid_truncate")))
                .contains("AFTER TRUNCATE ON public.ready_made_products")
                .contains("FOR EACH STATEMENT");

        // Phase 1 acquires the authorization row locks in the documented order:
        // Membership, then the required scope grant, then the creator User.
        // Both phases delegate to the same shared validation function, resolved
        // through the actual trigger-to-function binding (tgfoid).
        String phase1Source = triggerFunctionSource("ready_made_products_require_valid_creation");
        String phase2Source = triggerFunctionSource("ready_made_products_revalidate_creation");
        assertThat(phase1Source).contains("ready_made_products_validate_creation")
                .contains("true")
                .contains("RETURN NEW");
        assertThat(phase2Source).contains("ready_made_products_validate_creation")
                .contains("false")
                .contains("RETURN NULL");

        String validation = functionSourceByOid(jdbcTemplate.queryForObject(
                "SELECT p.oid::bigint FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'public' "
                        + "AND p.proname = 'ready_made_products_validate_creation'",
                Long.class));
        assertThat(validation.indexOf("FROM workspace_memberships"))
                .isLessThan(validation.indexOf("FROM workspace_membership_scopes"));
        assertThat(validation.indexOf("FROM workspace_membership_scopes"))
                .isLessThan(validation.indexOf("FROM users"));
        assertThat(validation).contains("FOR UPDATE");
    }

    /**
     * Adversarial metadata isolation: a same-named table, same-named PK and
     * CHECK constraints, and a same-named function in a separate decoy schema
     * must never be inspected instead of {@code public.ready_made_products}.
     * The decoy schema name consists only of a fixed safe prefix and UUID hex
     * digits, and it is dropped in a finally block.
     */
    @Test
    void metadataInspectionIsIsolatedFromSameNamedObjectsInAnotherSchema() {
        String decoySchema =
                "rmp_decoy_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(decoySchema).matches("rmp_decoy_[0-9a-f]{32}");
        Long publicOid = readyMadeProductsRelationOid();
        try {
            jdbcTemplate.execute("CREATE SCHEMA " + decoySchema);
            jdbcTemplate.execute("CREATE TABLE " + decoySchema + ".ready_made_products ("
                    + "id UUID NOT NULL, decoy_column TEXT NOT NULL, "
                    + "status TEXT NOT NULL, "
                    + "CONSTRAINT ready_made_products_pk PRIMARY KEY (id, decoy_column), "
                    + "CONSTRAINT ready_made_products_status_allowed "
                    + "CHECK (status IN ('DECOY')), "
                    + "CONSTRAINT ready_made_products_available_quantity_non_negative "
                    + "CHECK (status <> ''))");
            jdbcTemplate.execute("CREATE FUNCTION " + decoySchema
                    + ".ready_made_products_require_valid_creation() RETURNS TRIGGER AS $$ "
                    + "BEGIN RETURN NULL; END; $$ LANGUAGE plpgsql");
            jdbcTemplate.execute("CREATE FUNCTION " + decoySchema
                    + ".ready_made_products_validate_creation() RETURNS VOID AS $$ "
                    + "BEGIN RETURN; END; $$ LANGUAGE plpgsql");

            // The decoy objects really exist and really collide by name.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_class c JOIN pg_namespace n "
                            + "ON n.oid = c.relnamespace WHERE c.relname = 'ready_made_products' "
                            + "AND n.nspname = ?",
                    Integer.class, decoySchema)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "WHERE n.nspname = ? AND p.proname IN "
                            + "('ready_made_products_require_valid_creation', "
                            + "'ready_made_products_validate_creation')",
                    Integer.class, decoySchema)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conname = 'ready_made_products_pk'",
                    String.class)).hasSize(2);

            // The metadata path still analyses only public.ready_made_products.
            assertThat(readyMadeProductsRelationOid()).isEqualTo(publicOid);
            schemaDeclaresExpectedColumnsAndTypes();
            schemaDeclaresExpectedKeysAndConstraints();
            noSpeculativeIndexExistsBeyondThePrimaryKey();
            readyMadeProductTriggersAreDeclaredWithTheExpectedSemantics();

            // The function source is obtained through the real trigger binding,
            // so the decoy function body is never read.
            assertThat(triggerFunctionSource("ready_made_products_require_valid_creation"))
                    .contains("ready_made_products_validate_creation")
                    .doesNotContain("RETURN NULL");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + decoySchema + " CASCADE");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_namespace WHERE nspname = ?", Integer.class, decoySchema))
                .isZero();
    }

    @Test
    void readyMadeProductServiceIsWiredToJdbcReadyMadeProductRepository() {
        assertThat(readyMadeProductService).isNotNull();
        assertThat(readyMadeProductRepository).isInstanceOf(JdbcReadyMadeProductRepository.class);
    }

    // ------------------------------------------------------------------
    // Creation through the application service
    // ------------------------------------------------------------------

    @Test
    void activeAdminOfUserOwnedWorkspaceCreatesActiveProductWithZeroQuantity() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());

        ReadyMadeProduct created =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), admin.id(), 0L);

        assertThat(created.workspaceId()).isEqualTo(workspace.id());
        assertThat(created.createdByUserId()).isEqualTo(admin.id());
        assertThat(created.status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
        assertThat(created.availableQuantity()).isZero();
        // The initial ADMIN Membership needs no stored scope grant.
        assertThat(scopeRowCount(workspace.id(), admin.id())).isZero();
        assertThat(readyMadeProductRepository.findById(created.id())).contains(created);
        assertThat(readyMadeProductService.findReadyMadeProduct(created.id())).isEqualTo(created);
    }

    @Test
    void activeAdminOfOrganizationOwnedWorkspaceCreatesActiveProductWithPositiveQuantity() {
        User admin = activeUser();
        Organization organization = organizationService.createOrganization(admin.id());
        Workspace workspace =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), admin.id());

        ReadyMadeProduct created =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), admin.id(), 7L);

        assertThat(created.workspaceId()).isEqualTo(workspace.id());
        assertThat(created.createdByUserId()).isEqualTo(admin.id());
        assertThat(created.status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
        assertThat(created.availableQuantity()).isEqualTo(7L);
        assertThat(scopeRowCount(workspace.id(), admin.id())).isZero();
    }

    @Test
    void activeEditorWithExplicitReadyMadeProductsScopeCreatesProduct() {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");

        ReadyMadeProduct created =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), editor.id(), 3L);

        assertThat(created.createdByUserId()).isEqualTo(editor.id());
        assertThat(created.status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
        assertThat(created.availableQuantity()).isEqualTo(3L);
    }

    /**
     * The lifecycle state is not a creation input: no public application
     * operation of this slice accepts a Ready-Made Product lifecycle state.
     */
    @Test
    void noApplicationOperationAcceptsALifecycleStatusInput() {
        var statusAcceptingMethods = Arrays
                .stream(ReadyMadeProductService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.asList(method.getParameterTypes())
                        .contains(ReadyMadeProductStatus.class))
                .map(Method::getName)
                .toList();
        assertThat(statusAcceptingMethods).isEmpty();

        var createParameters = Arrays
                .stream(ReadyMadeProductService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("createReadyMadeProduct"))
                .map(method -> Arrays.asList(method.getParameterTypes()))
                .toList();
        assertThat(createParameters)
                .containsExactly(List.of(UUID.class, UUID.class, long.class));
    }

    @Test
    void findReadyMadeProductFailsForUnknownIdentity() {
        UUID unknownId = UUID.randomUUID();

        assertThatExceptionOfType(ReadyMadeProductNotFoundException.class)
                .isThrownBy(() -> readyMadeProductService.findReadyMadeProduct(unknownId))
                .satisfies(failure -> assertThat(failure.readyMadeProductId()).isEqualTo(unknownId))
                .withMessageContaining(unknownId.toString());
    }

    @Test
    void negativeInitialQuantityIsRejectedAndLeavesNoRows() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        Integer productsBefore = totalRows("ready_made_products");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> readyMadeProductService
                        .createReadyMadeProduct(workspace.id(), admin.id(), -1L))
                .withMessageContaining("availableQuantity must not be negative");

        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
    }

    // ------------------------------------------------------------------
    // Rejected creation authorization through the application service
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} creator")
    @CsvSource({"SUSPENDED", "DEACTIVATED"})
    void nonActiveCreatorIsRejectedAndLeavesNoRows(UserStatus creatorStatus) {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        userService.changeStatus(admin.id(), creatorStatus);
        Integer productsBefore = totalRows("ready_made_products");

        assertThatExceptionOfType(ReadyMadeProductCreatorNotActiveException.class)
                .isThrownBy(() -> readyMadeProductService
                        .createReadyMadeProduct(workspace.id(), admin.id(), 1L))
                .satisfies(failure -> {
                    assertThat(failure.creatorUserId()).isEqualTo(admin.id());
                    assertThat(failure.creatorStatus()).isEqualTo(creatorStatus);
                });

        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
    }

    @Test
    void creatorWithoutWorkspaceMembershipIsRejectedAndLeavesNoRows() {
        User admin = activeUser();
        User outsider = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        Integer productsBefore = totalRows("ready_made_products");

        assertThatExceptionOfType(ReadyMadeProductCreatorNotAuthorizedException.class)
                .isThrownBy(() -> readyMadeProductService
                        .createReadyMadeProduct(workspace.id(), outsider.id(), 1L))
                .satisfies(failure -> {
                    assertThat(failure.workspaceId()).isEqualTo(workspace.id());
                    assertThat(failure.creatorUserId()).isEqualTo(outsider.id());
                });

        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
    }

    /**
     * Every Membership shape without effective READY_MADE_PRODUCTS write
     * authority is rejected: non-ACTIVE Memberships, an EDITOR without the
     * scope, an EDITOR holding only another scope, and a VIEWER even with the
     * READY_MADE_PRODUCTS scope granted.
     */
    @ParameterizedTest(name = "{0} Membership with scopes [{1}]")
    @CsvSource({
            "EDITOR, INVITED, READY_MADE_PRODUCTS",
            "EDITOR, SUSPENDED, READY_MADE_PRODUCTS",
            "ADMIN, INVITED, ",
            "ADMIN, SUSPENDED, ",
            "EDITOR, ACTIVE, ",
            "EDITOR, ACTIVE, PROJECTS",
            "EDITOR, ACTIVE, LISTINGS",
            "VIEWER, ACTIVE, READY_MADE_PRODUCTS"
    })
    void membershipWithoutEffectiveWriteAuthorizationIsRejectedAndLeavesNoRows(
            String role, String membershipStatus, String grantedScope) {
        User admin = activeUser();
        User creator = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), creator.id(), role, membershipStatus);
        if (grantedScope != null) {
            addScopeThroughRawSql(workspace.id(), creator.id(), grantedScope);
        }
        Integer productsBefore = totalRows("ready_made_products");

        assertThatExceptionOfType(ReadyMadeProductCreatorNotAuthorizedException.class)
                .isThrownBy(() -> readyMadeProductService
                        .createReadyMadeProduct(workspace.id(), creator.id(), 1L))
                .satisfies(failure -> {
                    assertThat(failure.workspaceId()).isEqualTo(workspace.id());
                    assertThat(failure.creatorUserId()).isEqualTo(creator.id());
                });

        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
    }

    // ------------------------------------------------------------------
    // PostgreSQL creation gate against direct SQL bypassing the service
    // ------------------------------------------------------------------

    @Test
    void directInsertWithArchivedInitialStateIsRejectedAndLeavesNoRow() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> insertProductThroughRawSql(
                productId, workspace.id(), admin.id(), "ARCHIVED", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(failure,
                        "requires the initial lifecycle state ACTIVE", "ARCHIVED"));

        assertThat(productCount(productId)).isZero();
    }

    @Test
    void directInsertWithAnUnknownLifecycleValueIsRejectedByTheClosedCheckSet() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        UUID productId = UUID.randomUUID();

        // On INSERT the BEFORE INSERT creation gate runs before the table CHECK
        // constraint is evaluated, so an unknown initial lifecycle value is
        // rejected there first, still with SQLSTATE 23514 and still without any
        // surviving row.
        assertThatThrownBy(() -> insertProductThroughRawSql(
                productId, workspace.id(), admin.id(), "PUBLISHED", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "requires the initial lifecycle state ACTIVE", "PUBLISHED"));
        assertThat(productCount(productId)).isZero();

        // The closed CHECK set itself is proven independently of the creation
        // gate: no stored Ready-Made Product may reach a value outside
        // ACTIVE | ARCHIVED either.
        ReadyMadeProduct stored =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), admin.id(), 1L);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET status = 'PUBLISHED' WHERE id = ?", stored.id()))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "ready_made_products_status_allowed"));
        assertThat(readyMadeProductService.findReadyMadeProduct(stored.id()).status())
                .isEqualTo(ReadyMadeProductStatus.ACTIVE);
    }

    @Test
    void directInsertWithNegativeQuantityIsRejectedAndLeavesNoRow() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> insertProductThroughRawSql(
                productId, workspace.id(), admin.id(), "ACTIVE", -1L))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "ready_made_products_available_quantity_non_negative"));

        assertThat(productCount(productId)).isZero();
    }

    @Test
    void directInsertReferencingAnUnknownWorkspaceOrUserIsRejected() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        UUID unknownWorkspaceProduct = UUID.randomUUID();
        UUID unknownUserProduct = UUID.randomUUID();

        // An unknown Workspace or an unknown Created By User can have no ACTIVE
        // Workspace Membership, so the BEFORE INSERT creation gate necessarily
        // rejects such an INSERT before the foreign keys are validated. The FK
        // declarations themselves, including their RESTRICT delete action, are
        // asserted from catalog metadata in
        // schemaDeclaresExpectedKeysAndConstraints().
        assertThatThrownBy(() -> insertProductThroughRawSql(
                unknownWorkspaceProduct, UUID.randomUUID(), admin.id(), "ACTIVE", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "has no Workspace Membership in Workspace"));
        assertThatThrownBy(() -> insertProductThroughRawSql(
                unknownUserProduct, workspace.id(), UUID.randomUUID(), "ACTIVE", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "has no Workspace Membership in Workspace"));

        assertThat(productCount(unknownWorkspaceProduct)).isZero();
        assertThat(productCount(unknownUserProduct)).isZero();
    }

    @Test
    void directInsertForANonActiveCreatorIsRejectedByPostgreSqlItself() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        userService.changeStatus(admin.id(), UserStatus.SUSPENDED);
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> insertProductThroughRawSql(
                productId, workspace.id(), admin.id(), "ACTIVE", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(failure,
                        "requires an ACTIVE Created By User", "SUSPENDED"));

        assertThat(productCount(productId)).isZero();
    }

    @Test
    void directInsertWithoutAWorkspaceMembershipIsRejectedByPostgreSqlItself() {
        User admin = activeUser();
        User outsider = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> insertProductThroughRawSql(
                productId, workspace.id(), outsider.id(), "ACTIVE", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "has no Workspace Membership in Workspace"));

        assertThat(productCount(productId)).isZero();
    }

    @ParameterizedTest(name = "direct INSERT for {0} Membership with status {1} and scopes [{2}]")
    @CsvSource({
            "EDITOR, INVITED, READY_MADE_PRODUCTS, found Membership status INVITED",
            "EDITOR, SUSPENDED, READY_MADE_PRODUCTS, found Membership status SUSPENDED",
            "ADMIN, INVITED, , found Membership status INVITED",
            "EDITOR, ACTIVE, , requires an explicit READY_MADE_PRODUCTS permission scope grant",
            "EDITOR, ACTIVE, PROJECTS, requires an explicit READY_MADE_PRODUCTS permission scope grant",
            "EDITOR, ACTIVE, LISTINGS, requires an explicit READY_MADE_PRODUCTS permission scope grant",
            "VIEWER, ACTIVE, READY_MADE_PRODUCTS, never grants write access"
    })
    void directInsertWithoutEffectiveWriteAuthorizationIsRejectedByPostgreSqlItself(
            String role, String membershipStatus, String grantedScope, String expectedMessage) {
        User admin = activeUser();
        User creator = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), creator.id(), role, membershipStatus);
        if (grantedScope != null) {
            addScopeThroughRawSql(workspace.id(), creator.id(), grantedScope);
        }
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> insertProductThroughRawSql(
                productId, workspace.id(), creator.id(), "ACTIVE", 1L))
                .satisfies(failure -> assertStructuralCheckViolation(failure, expectedMessage));

        assertThat(productCount(productId)).isZero();
    }

    /**
     * Phase 1 accepts a valid INSERT, then phase 2 rejects authorization that
     * is lost later inside that same transaction. Reaching the end of the
     * transaction body after the mutation proves that the failure is raised by
     * deferred commit-time revalidation rather than the BEFORE INSERT gate.
     */
    @ParameterizedTest(name = "same-transaction authorization loss: {0}")
    @ValueSource(strings = {
            "USER_SUSPEND",
            "MEMBERSHIP_SUSPEND",
            "MEMBERSHIP_VIEWER",
            "SCOPE_DELETE",
            "MEMBERSHIP_DELETE_AFTER_SCOPE"
    })
    void deferredCreationRevalidationRejectsSameTransactionAuthorizationLoss(
            String mutationKind) {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");
        UUID productId = UUID.randomUUID();
        int productsBefore = totalRows("ready_made_products");
        int usersBefore = totalRows("users");
        int membershipsBefore = totalRows("workspace_memberships");
        int scopesBefore = totalRows("workspace_membership_scopes");
        AtomicBoolean insertVisibleBeforeMutation = new AtomicBoolean();
        AtomicBoolean transactionBodyCompletedAfterMutation = new AtomicBoolean();

        Throwable failure = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            insertProductThroughRawSql(
                    productId, workspace.id(), editor.id(), "ACTIVE", 1L);
            insertVisibleBeforeMutation.set(productCount(productId) == 1);

            applySameTransactionAuthorizationMutation(
                    mutationKind, workspace.id(), editor.id());

            assertThat(productCount(productId)).isEqualTo(1);
            transactionBodyCompletedAfterMutation.set(true);
        }));

        assertThat(insertVisibleBeforeMutation).isTrue();
        assertThat(transactionBodyCompletedAfterMutation).isTrue();
        assertStructuralCheckViolation(
                failure, expectedSameTransactionRejectionFragments(mutationKind));
        assertThat(productCount(productId)).isZero();

        // The rejected commit rolls back both the INSERT and the later
        // authorization mutation, while unrelated owner authorization remains
        // unchanged.
        assertThat(userService.findUser(editor.id()).status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT role, status FROM workspace_memberships "
                        + "WHERE workspace_id = ? AND user_id = ?",
                workspace.id(), editor.id()))
                .containsEntry("role", "EDITOR")
                .containsEntry("status", "ACTIVE");
        assertThat(scopeRowCount(workspace.id(), editor.id())).isEqualTo(1);
        assertThat(userService.findUser(admin.id()).status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT role, status FROM workspace_memberships "
                        + "WHERE workspace_id = ? AND user_id = ?",
                workspace.id(), admin.id()))
                .containsEntry("role", "ADMIN")
                .containsEntry("status", "ACTIVE");
        assertThat(scopeRowCount(workspace.id(), admin.id())).isZero();
        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
        assertThat(totalRows("users")).isEqualTo(usersBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
        assertThat(totalRows("workspace_membership_scopes")).isEqualTo(scopesBefore);
    }

    private void applySameTransactionAuthorizationMutation(
            String mutationKind, UUID workspaceId, UUID userId) {
        switch (mutationKind) {
            case "USER_SUSPEND" -> jdbcTemplate.update(
                    "UPDATE users SET status = 'SUSPENDED' WHERE id = ?", userId);
            case "MEMBERSHIP_SUSPEND" -> jdbcTemplate.update(
                    "UPDATE workspace_memberships SET status = 'SUSPENDED' "
                            + "WHERE workspace_id = ? AND user_id = ?",
                    workspaceId, userId);
            case "MEMBERSHIP_VIEWER" -> jdbcTemplate.update(
                    "UPDATE workspace_memberships SET role = 'VIEWER' "
                            + "WHERE workspace_id = ? AND user_id = ?",
                    workspaceId, userId);
            case "SCOPE_DELETE" -> revokeScope(workspaceId, userId);
            case "MEMBERSHIP_DELETE_AFTER_SCOPE" -> {
                revokeScope(workspaceId, userId);
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships "
                                + "WHERE workspace_id = ? AND user_id = ?",
                        workspaceId, userId);
            }
            default -> throw new IllegalArgumentException("Unknown mutation " + mutationKind);
        }
    }

    private String[] expectedSameTransactionRejectionFragments(String mutationKind) {
        return switch (mutationKind) {
            case "USER_SUSPEND" -> new String[] {
                    "requires an ACTIVE Created By User", "SUSPENDED"
            };
            case "MEMBERSHIP_SUSPEND" -> new String[] {
                    "requires an ACTIVE Workspace Membership", "SUSPENDED"
            };
            case "MEMBERSHIP_VIEWER" -> new String[] {
                    "VIEWER Workspace Membership", "never grants write access"
            };
            case "SCOPE_DELETE" -> new String[] {
                    "requires an explicit READY_MADE_PRODUCTS permission scope grant"
            };
            case "MEMBERSHIP_DELETE_AFTER_SCOPE" -> new String[] {
                    "has no Workspace Membership in Workspace"
            };
            default -> throw new IllegalArgumentException("Unknown mutation " + mutationKind);
        };
    }

    // ------------------------------------------------------------------
    // Structural immutability, retention, and independent value columns
    // ------------------------------------------------------------------

    @Test
    void identityWorkspaceAndCreatedByAreImmutable() {
        User admin = activeUser();
        User otherAdmin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        Workspace otherWorkspace = workspaceService.createUserOwnedWorkspace(otherAdmin.id());
        ReadyMadeProduct product =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), admin.id(), 2L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET id = ? WHERE id = ?",
                UUID.randomUUID(), product.id()))
                .satisfies(failure -> assertStructuralCheckViolation(failure, "identity is immutable"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET workspace_id = ? WHERE id = ?",
                otherWorkspace.id(), product.id()))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "Workspace of Ready-Made Product", "is immutable"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET created_by_user_id = ? WHERE id = ?",
                otherAdmin.id(), product.id()))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "Created By User of Ready-Made Product", "is immutable"));

        assertThat(readyMadeProductService.findReadyMadeProduct(product.id())).isEqualTo(product);
    }

    @Test
    void deletionAndTruncationAreRejected() {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        ReadyMadeProduct product =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), admin.id(), 0L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ready_made_products WHERE id = ?", product.id()))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "cannot be deleted", "destructive deletion is unsupported in MVP"));
        // ARCHIVED and stock-free products are protected by the same
        // unconditional rule.
        jdbcTemplate.update(
                "UPDATE ready_made_products SET status = 'ARCHIVED' WHERE id = ?", product.id());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ready_made_products WHERE id = ?", product.id()))
                .satisfies(failure -> assertStructuralCheckViolation(failure, "cannot be deleted"));
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE ready_made_products"))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "TRUNCATE of ready_made_products is not supported"));

        assertThat(productCount(product.id())).isEqualTo(1);
        assertThat(readyMadeProductService.findReadyMadeProduct(product.id()).status())
                .isEqualTo(ReadyMadeProductStatus.ARCHIVED);
    }

    /**
     * Lifecycle state and available quantity remain independent stored values:
     * both recognized lifecycle states and any non-negative quantity are
     * storable in any combination. No application mutation for either value
     * exists in this slice.
     */
    @ParameterizedTest(name = "stored lifecycle {0}")
    @ValueSource(strings = {"ACTIVE", "ARCHIVED"})
    void lifecycleAndQuantityRemainIndependentStoredValues(String storedStatus) {
        User admin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        ReadyMadeProduct product =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), admin.id(), 0L);

        jdbcTemplate.update(
                "UPDATE ready_made_products SET status = ?, available_quantity = 9 WHERE id = ?",
                storedStatus, product.id());
        ReadyMadeProduct withStock = readyMadeProductService.findReadyMadeProduct(product.id());
        jdbcTemplate.update(
                "UPDATE ready_made_products SET available_quantity = 0 WHERE id = ?", product.id());
        ReadyMadeProduct withoutStock = readyMadeProductService.findReadyMadeProduct(product.id());

        assertThat(withStock.status()).isEqualTo(ReadyMadeProductStatus.valueOf(storedStatus));
        assertThat(withStock.availableQuantity()).isEqualTo(9L);
        assertThat(withoutStock.status()).isEqualTo(ReadyMadeProductStatus.valueOf(storedStatus));
        assertThat(withoutStock.availableQuantity()).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET available_quantity = -1 WHERE id = ?", product.id()))
                .satisfies(failure -> assertStructuralCheckViolation(
                        failure, "ready_made_products_available_quantity_non_negative"));
    }

    /**
     * Creation authorization is a creation-time gate only: after a successful
     * creation, suspending the creator and revoking the Membership and its
     * scope grant neither removes the historical Ready-Made Product nor
     * rewrites its immutable Created By.
     */
    @Test
    void laterLossOfCreatorAuthorizationPreservesTheHistoricalProduct() {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");
        ReadyMadeProduct product =
                readyMadeProductService.createReadyMadeProduct(workspace.id(), editor.id(), 4L);

        jdbcTemplate.update(
                "DELETE FROM workspace_membership_scopes WHERE workspace_id = ? AND user_id = ?",
                workspace.id(), editor.id());
        jdbcTemplate.update(
                "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                workspace.id(), editor.id());
        userService.changeStatus(editor.id(), UserStatus.SUSPENDED);

        assertThat(readyMadeProductService.findReadyMadeProduct(product.id())).isEqualTo(product);
        assertThat(userService.findUser(editor.id()).status()).isEqualTo(UserStatus.SUSPENDED);
    }

    // ------------------------------------------------------------------
    // Deterministic creation races (no sleep, bounded timeouts)
    // ------------------------------------------------------------------

    /**
     * Race 1 — creation after an application-level read of an ACTIVE creator
     * versus a concurrent {@code ACTIVE -> SUSPENDED/DEACTIVATED} User status
     * change, with the status change committing first.
     */
    @ParameterizedTest(name = "creation racing creator ACTIVE -> {0}")
    @CsvSource({"SUSPENDED", "DEACTIVATED"})
    @Timeout(120)
    void race1_creationVersusCreatorStatusChangeIsRejected(UserStatus newStatus) throws Exception {
        User creator = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(creator.id());
        Integer productsBefore = totalRows("ready_made_products");

        MutationFirstOutcome outcome = runMutationCommitsFirstRace(
                workspace, creator,
                () -> jdbcTemplate.update(
                        "UPDATE users SET status = ? WHERE id = ?", newStatus.name(), creator.id()));

        assertMutationCommittedFirstAndCreationRejected(
                outcome, "requires an ACTIVE Created By User", newStatus.name());
        assertThat(userService.findUser(creator.id()).status()).isEqualTo(newStatus);
        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
    }

    /**
     * Race 2 — creation by an ACTIVE EDITOR after an application-level read of
     * its READY_MADE_PRODUCTS grant versus concurrent revocation of exactly
     * that grant, with the revocation committing first.
     */
    @Test
    @Timeout(120)
    void race2_creationVersusScopeGrantRevocationIsRejected() throws Exception {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");
        Integer productsBefore = totalRows("ready_made_products");

        MutationFirstOutcome outcome = runMutationCommitsFirstRace(
                workspace, editor, () -> revokeScope(workspace.id(), editor.id()));

        assertMutationCommittedFirstAndCreationRejected(
                outcome, "requires an explicit READY_MADE_PRODUCTS permission scope grant");
        assertThat(scopeRowCount(workspace.id(), editor.id())).isZero();
        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
    }

    /**
     * Race 3 — creation versus a concurrent Workspace Membership mutation that
     * removes the creator's write authority, with the Membership mutation
     * committing first.
     *
     * <p>These are exactly the mutations that additionally fire the already
     * integrated V4 deferred Membership invariant trigger, which requests the
     * parent Workspace row {@code FOR UPDATE} at commit. The BEFORE INSERT
     * authorization phase of V6 must therefore acquire its locks before the
     * INSERT takes any implicit foreign-key row lock on that same Workspace
     * row, otherwise this race deadlocks with SQLSTATE 40P01 instead of
     * producing the required structural rejection.
     */
    @ParameterizedTest(name = "creation racing Membership mutation {0}, mutation commits first")
    @ValueSource(strings = {"SUSPEND", "DOWNGRADE_TO_VIEWER", "DELETE_WITH_SCOPE"})
    @Timeout(120)
    void race3_creationVersusMembershipMutationCommittingFirstIsRejected(String mutationKind)
            throws Exception {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");
        Integer productsBefore = totalRows("ready_made_products");

        MutationFirstOutcome outcome = runMutationCommitsFirstRace(
                workspace, editor,
                () -> applyMembershipMutation(mutationKind, workspace.id(), editor.id()));

        assertMutationCommittedFirstAndCreationRejected(
                outcome, expectedRejectionFragment(mutationKind));
        assertThat(totalRows("ready_made_products")).isEqualTo(productsBefore);
        assertMembershipMutationApplied(mutationKind, workspace.id(), editor.id());
    }

    /**
     * Race 4 — the same Workspace Membership mutations, with the creation
     * acquiring the authorization locks and committing first. The mutation must
     * really wait, the creation must commit without a deadlock, the mutation
     * must then commit, and the historical Ready-Made Product with its
     * immutable Workspace and Created By must survive the later loss of
     * authorization.
     */
    @ParameterizedTest(name = "creation racing Membership mutation {0}, creation commits first")
    @ValueSource(strings = {"SUSPEND", "DOWNGRADE_TO_VIEWER", "DELETE_WITH_SCOPE"})
    @Timeout(120)
    void race4_creationCommittingFirstSurvivesLaterMembershipMutation(String mutationKind)
            throws Exception {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");

        CreationFirstOutcome outcome = runCreationCommitsFirstRace(
                workspace, editor,
                () -> applyMembershipMutation(mutationKind, workspace.id(), editor.id()));

        assertThat(outcome.creationFailure()).isNull();
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.mutationObservedWaitingForALock()).isTrue();
        assertThat(outcome.created()).isNotNull();

        ReadyMadeProduct survived =
                readyMadeProductService.findReadyMadeProduct(outcome.created().id());
        assertThat(survived).isEqualTo(outcome.created());
        assertThat(survived.workspaceId()).isEqualTo(workspace.id());
        assertThat(survived.createdByUserId()).isEqualTo(editor.id());
        assertThat(survived.status()).isEqualTo(ReadyMadeProductStatus.ACTIVE);
        assertMembershipMutationApplied(mutationKind, workspace.id(), editor.id());
    }

    /**
     * Sequence B for the creator User status axis: a creation that commits
     * first survives the concurrent suspension that commits afterwards.
     */
    @Test
    @Timeout(120)
    void race5_creationCommittingFirstSurvivesLaterCreatorSuspension() throws Exception {
        User creator = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(creator.id());

        CreationFirstOutcome outcome = runCreationCommitsFirstRace(
                workspace, creator,
                () -> jdbcTemplate.update(
                        "UPDATE users SET status = 'SUSPENDED' WHERE id = ?", creator.id()));

        assertThat(outcome.creationFailure()).isNull();
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.mutationObservedWaitingForALock()).isTrue();
        assertThat(outcome.created()).isNotNull();
        assertThat(readyMadeProductService.findReadyMadeProduct(outcome.created().id()))
                .isEqualTo(outcome.created());
        assertThat(userService.findUser(creator.id()).status()).isEqualTo(UserStatus.SUSPENDED);
    }

    /**
     * Sequence B for the permission-scope axis: a creation that commits first
     * survives the concurrent revocation that commits afterwards.
     */
    @Test
    @Timeout(120)
    void race6_creationCommittingFirstSurvivesLaterScopeRevocation() throws Exception {
        User admin = activeUser();
        User editor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(admin.id());
        addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE");
        addScopeThroughRawSql(workspace.id(), editor.id(), "READY_MADE_PRODUCTS");

        CreationFirstOutcome outcome = runCreationCommitsFirstRace(
                workspace, editor, () -> revokeScope(workspace.id(), editor.id()));

        assertThat(outcome.creationFailure()).isNull();
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.mutationObservedWaitingForALock()).isTrue();
        assertThat(outcome.created()).isNotNull();
        assertThat(readyMadeProductService.findReadyMadeProduct(outcome.created().id()))
                .isEqualTo(outcome.created());
        assertThat(scopeRowCount(workspace.id(), editor.id())).isZero();
    }

    /**
     * Asserts the required sequence-A semantics: the mutation committed, the
     * stale application-level prevalidation really accepted the creation, the
     * creation INSERT was really attempted and really waited for the mutation,
     * and the creation was rejected by the V6 creation gate with SQLSTATE
     * 23514, never with 40P01, 55P03, a timeout, or an unrelated error.
     */
    private void assertMutationCommittedFirstAndCreationRejected(
            MutationFirstOutcome outcome, String... messageFragments) {
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.stalePrevalidationSucceeded()).isTrue();
        assertThat(outcome.creationInsertAttempted()).isTrue();
        assertThat(outcome.creationObservedWaitingForALock()).isTrue();
        assertThat(outcome.creationFailure()).isNotNull();
        assertStructuralCheckViolation(outcome.creationFailure(), messageFragments);
    }

    private void revokeScope(UUID workspaceId, UUID userId) {
        jdbcTemplate.update(
                "DELETE FROM workspace_membership_scopes "
                        + "WHERE workspace_id = ? AND user_id = ? AND scope = ?",
                workspaceId, userId, "READY_MADE_PRODUCTS");
    }

    private void applyMembershipMutation(String mutationKind, UUID workspaceId, UUID userId) {
        switch (mutationKind) {
            case "SUSPEND" -> jdbcTemplate.update(
                    "UPDATE workspace_memberships SET status = 'SUSPENDED' "
                            + "WHERE workspace_id = ? AND user_id = ?",
                    workspaceId, userId);
            case "DOWNGRADE_TO_VIEWER" -> jdbcTemplate.update(
                    "UPDATE workspace_memberships SET role = 'VIEWER' "
                            + "WHERE workspace_id = ? AND user_id = ?",
                    workspaceId, userId);
            case "DELETE_WITH_SCOPE" -> {
                revokeScope(workspaceId, userId);
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships "
                                + "WHERE workspace_id = ? AND user_id = ?",
                        workspaceId, userId);
            }
            default -> throw new IllegalArgumentException("Unknown mutation " + mutationKind);
        }
    }

    private String expectedRejectionFragment(String mutationKind) {
        return switch (mutationKind) {
            case "SUSPEND" -> "found Membership status SUSPENDED";
            case "DOWNGRADE_TO_VIEWER" -> "VIEWER Workspace Membership";
            case "DELETE_WITH_SCOPE" -> "has no Workspace Membership in Workspace";
            default -> throw new IllegalArgumentException("Unknown mutation " + mutationKind);
        };
    }

    private void assertMembershipMutationApplied(
            String mutationKind, UUID workspaceId, UUID userId) {
        var memberships = jdbcTemplate.queryForList(
                "SELECT role, status FROM workspace_memberships "
                        + "WHERE workspace_id = ? AND user_id = ?",
                workspaceId, userId);
        switch (mutationKind) {
            case "SUSPEND" -> {
                assertThat(memberships).hasSize(1);
                assertThat(memberships.get(0).get("status")).isEqualTo("SUSPENDED");
            }
            case "DOWNGRADE_TO_VIEWER" -> {
                assertThat(memberships).hasSize(1);
                assertThat(memberships.get(0).get("role")).isEqualTo("VIEWER");
            }
            case "DELETE_WITH_SCOPE" -> {
                assertThat(memberships).isEmpty();
                assertThat(scopeRowCount(workspaceId, userId)).isZero();
            }
            default -> throw new IllegalArgumentException("Unknown mutation " + mutationKind);
        }
    }

    /** The outcome of one deterministic "mutation commits first" race. */
    private record MutationFirstOutcome(
            Exception mutationFailure,
            Exception creationFailure,
            boolean stalePrevalidationSucceeded,
            boolean creationInsertAttempted,
            boolean creationObservedWaitingForALock) {
    }

    /** The outcome of one deterministic "creation commits first" race. */
    private record CreationFirstOutcome(
            Exception mutationFailure,
            Exception creationFailure,
            ReadyMadeProduct created,
            boolean mutationObservedWaitingForALock) {
    }

    /**
     * Replicates exactly the application-level prevalidation of the creation
     * service, without inserting anything. A {@code true} result proves that
     * the application layer alone would have accepted the creation.
     */
    private boolean applicationPrevalidationAccepts(UUID workspaceId, UUID creatorUserId) {
        User creator = userService.findUser(creatorUserId);
        if (creator.status() != UserStatus.ACTIVE) {
            return false;
        }
        return workspaceService.findMemberships(workspaceId).stream()
                .filter(membership -> membership.userId().equals(creatorUserId))
                .anyMatch(membership -> membership.allowsWorkspaceLayerWrite(
                        creator.status(), WorkspacePermissionScope.READY_MADE_PRODUCTS));
    }

    /**
     * Sequence A — the authorization mutation commits first.
     *
     * <p>The interleaving is deterministic and uses no sleep: the mutation runs
     * first and keeps its transaction open, so it holds the affected
     * authorization row lock; the creation thread then proves that a stale
     * application-level prevalidation still accepts the creation and attempts
     * the real creation, whose BEFORE INSERT phase blocks on that very row;
     * each worker publishes the PID of its transaction-bound backend, and
     * PostgreSQL lock metadata is polled with a bounded deadline for exactly
     * creation waiter -> mutation blocker; only then is the mutation released
     * to commit first; the creation continues, re-reads the committed
     * authorization state and must be rejected.
     */
    private MutationFirstOutcome runMutationCommitsFirstRace(
            Workspace workspace, User creator, Runnable authorizationMutation) throws Exception {
        CountDownLatch mutationExecuted = new CountDownLatch(1);
        CountDownLatch creationAttempting = new CountDownLatch(1);
        CountDownLatch mutationPidPublished = new CountDownLatch(1);
        CountDownLatch creationPidPublished = new CountDownLatch(1);
        CountDownLatch releaseMutationCommit = new CountDownLatch(1);
        List<Boolean> prevalidation = new ArrayList<>();
        List<Boolean> insertAttempted = new ArrayList<>();
        AtomicReference<Integer> mutationPid = new AtomicReference<>();
        AtomicReference<Integer> creationPid = new AtomicReference<>();

        Callable<Exception> mutationTransaction = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    mutationPid.set(currentTransactionBackendPid());
                    mutationPidPublished.countDown();
                    authorizationMutation.run();
                    // The uncommitted change holds the affected row lock.
                    mutationExecuted.countDown();
                    awaitLatch(releaseMutationCommit, "the blocked creation attempt");
                });
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                mutationPidPublished.countDown();
                mutationExecuted.countDown();
            }
        };

        Callable<Exception> creationTransaction = () -> {
            try {
                awaitLatch(mutationExecuted, "the concurrent authorization mutation");
                // Stale prevalidation: READ COMMITTED still sees the committed
                // authorization, so the application layer would accept.
                prevalidation.add(applicationPrevalidationAccepts(workspace.id(), creator.id()));
                transactionTemplate.executeWithoutResult(status -> {
                    creationPid.set(currentTransactionBackendPid());
                    creationPidPublished.countDown();
                    insertAttempted.add(true);
                    creationAttempting.countDown();
                    readyMadeProductService
                            .createReadyMadeProduct(workspace.id(), creator.id(), 1L);
                });
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                creationPidPublished.countDown();
                creationAttempting.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception mutationOutcome;
        Exception creationOutcome;
        boolean waitObserved;
        try {
            Future<Exception> mutationFuture = executor.submit(mutationTransaction);
            Future<Exception> creationFuture = executor.submit(creationTransaction);
            awaitLatch(creationAttempting, "the creation insert attempt");
            awaitLatch(mutationPidPublished, "the mutation transaction backend PID");
            awaitLatch(creationPidPublished, "the creation transaction backend PID");
            waitObserved = mutationPid.get() != null
                    && creationPid.get() != null
                    && awaitExactBlockedBackend(creationPid.get(), mutationPid.get());
            releaseMutationCommit.countDown();
            mutationOutcome = mutationFuture.get(60, TimeUnit.SECONDS);
            creationOutcome = creationFuture.get(60, TimeUnit.SECONDS);
        } finally {
            releaseMutationCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        return new MutationFirstOutcome(
                mutationOutcome,
                creationOutcome,
                prevalidation.size() == 1 && prevalidation.get(0),
                insertAttempted.size() == 1 && insertAttempted.get(0),
                waitObserved);
    }

    /**
     * Sequence B — the creation acquires the authorization locks first and
     * commits first. Each worker publishes its transaction-bound backend PID,
     * and the exact mutation waiter -> creation blocker pair must be observed.
     */
    private CreationFirstOutcome runCreationCommitsFirstRace(
            Workspace workspace, User creator, Runnable authorizationMutation) throws Exception {
        CountDownLatch creationLocksAcquired = new CountDownLatch(1);
        CountDownLatch mutationAttempting = new CountDownLatch(1);
        CountDownLatch creationPidPublished = new CountDownLatch(1);
        CountDownLatch mutationPidPublished = new CountDownLatch(1);
        CountDownLatch releaseCreationCommit = new CountDownLatch(1);
        List<ReadyMadeProduct> createdProducts = new ArrayList<>();
        AtomicReference<Integer> creationPid = new AtomicReference<>();
        AtomicReference<Integer> mutationPid = new AtomicReference<>();

        Callable<Exception> creationTransaction = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    creationPid.set(currentTransactionBackendPid());
                    creationPidPublished.countDown();
                    createdProducts.add(readyMadeProductService
                            .createReadyMadeProduct(workspace.id(), creator.id(), 5L));
                    // The authorization row locks of the BEFORE INSERT phase are
                    // now held by this open transaction.
                    creationLocksAcquired.countDown();
                    awaitLatch(releaseCreationCommit, "the blocked authorization mutation");
                });
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                creationPidPublished.countDown();
                creationLocksAcquired.countDown();
            }
        };

        Callable<Exception> mutationTransaction = () -> {
            try {
                awaitLatch(creationLocksAcquired, "the creation authorization locks");
                transactionTemplate.executeWithoutResult(status -> {
                    mutationPid.set(currentTransactionBackendPid());
                    mutationPidPublished.countDown();
                    mutationAttempting.countDown();
                    authorizationMutation.run();
                });
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                mutationPidPublished.countDown();
                mutationAttempting.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception mutationOutcome;
        Exception creationOutcome;
        boolean waitObserved;
        try {
            Future<Exception> creationFuture = executor.submit(creationTransaction);
            Future<Exception> mutationFuture = executor.submit(mutationTransaction);
            awaitLatch(mutationAttempting, "the authorization mutation attempt");
            awaitLatch(creationPidPublished, "the creation transaction backend PID");
            awaitLatch(mutationPidPublished, "the mutation transaction backend PID");
            waitObserved = creationPid.get() != null
                    && mutationPid.get() != null
                    && awaitExactBlockedBackend(mutationPid.get(), creationPid.get());
            releaseCreationCommit.countDown();
            creationOutcome = creationFuture.get(60, TimeUnit.SECONDS);
            mutationOutcome = mutationFuture.get(60, TimeUnit.SECONDS);
        } finally {
            releaseCreationCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        return new CreationFirstOutcome(
                mutationOutcome,
                creationOutcome,
                createdProducts.size() == 1 ? createdProducts.get(0) : null,
                waitObserved);
    }

    /**
     * Returns the PostgreSQL backend PID of the current worker transaction.
     * Because this query runs through the transaction-bound JdbcTemplate
     * connection, it identifies the backend that executes the worker's
     * creation or authorization mutation.
     */
    private Integer currentTransactionBackendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /**
     * Bounded polling for one exact directed lock relationship. Both the
     * expected waiter and blocker must exist in {@code pg_stat_activity} for
     * this database, and the blocker PID must occur in
     * {@code pg_blocking_pids(waiterPid)}. An unrelated wait in the same
     * database cannot satisfy this predicate. No sleep is used: each poll is a
     * real catalog query.
     */
    private boolean awaitExactBlockedBackend(int waiterPid, int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Boolean expectedPair = jdbcTemplate.queryForObject(
                    "SELECT EXISTS ("
                            + "SELECT 1 FROM pg_stat_activity waiter "
                            + "JOIN pg_stat_activity blocker ON blocker.pid = ? "
                            + "WHERE waiter.pid = ? "
                            + "AND waiter.datname = current_database() "
                            + "AND blocker.datname = current_database() "
                            + "AND blocker.pid = ANY (pg_blocking_pids(waiter.pid)))",
                    Boolean.class, blockerPid, waiterPid);
            if (Boolean.TRUE.equals(expectedPair)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Metadata helpers, isolated to public.ready_made_products
    // ------------------------------------------------------------------

    /**
     * Resolves the exact relation OID of {@code public.ready_made_products} by
     * explicit relation name and explicit namespace name, so a same-named
     * relation in another schema can neither replace nor disturb the inspected
     * object. All other metadata helpers are anchored to this OID.
     */
    private Long readyMadeProductsRelationOid() {
        return jdbcTemplate.queryForObject(
                "SELECT c.oid::bigint FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relname = 'ready_made_products' "
                        + "AND c.relkind = 'r'",
                Long.class);
    }

    /** Resolves one constraint OID belonging to that exact relation OID. */
    private Long constraintOidOf(String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT oid::bigint FROM pg_constraint WHERE conrelid = ? AND conname = ?",
                Long.class, readyMadeProductsRelationOid(), constraintName);
    }

    private String constraintDefinitionOf(String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE oid = ?",
                String.class, constraintOidOf(constraintName));
    }

    /**
     * Reads the source of the function actually bound to the named trigger of
     * that exact relation, resolved through {@code pg_trigger.tgfoid}, not by
     * function name. A same-named function in another schema is therefore never
     * inspected instead.
     */
    private String triggerFunctionSource(String triggerName) {
        return jdbcTemplate.queryForObject(
                "SELECT p.prosrc FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid "
                        + "WHERE t.tgrelid = ? AND t.tgname = ? AND NOT t.tgisinternal",
                String.class, readyMadeProductsRelationOid(), triggerName);
    }

    private String functionSourceByOid(Long functionOid) {
        return jdbcTemplate.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE oid = ?", String.class, functionOid);
    }

    private Integer totalRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Integer productCount(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ready_made_products WHERE id = ?", Integer.class, productId);
    }

    private Integer scopeRowCount(UUID workspaceId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_membership_scopes "
                        + "WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspaceId, userId);
    }

    private User activeUser() {
        return userService.createUser();
    }

    private void addMembershipThroughRawSql(UUID workspaceId, UUID userId, String role, String status) {
        jdbcTemplate.update(
                "INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                        + "VALUES (?, ?, ?, ?)",
                workspaceId, userId, role, status);
    }

    private void addScopeThroughRawSql(UUID workspaceId, UUID userId, String scope) {
        jdbcTemplate.update(
                "INSERT INTO workspace_membership_scopes (workspace_id, user_id, scope) "
                        + "VALUES (?, ?, ?)",
                workspaceId, userId, scope);
    }

    private void insertProductThroughRawSql(
            UUID productId, UUID workspaceId, UUID createdByUserId, String status, long quantity) {
        jdbcTemplate.update(
                "INSERT INTO ready_made_products "
                        + "(id, workspace_id, created_by_user_id, status, available_quantity) "
                        + "VALUES (?, ?, ?, ?, ?)",
                productId, workspaceId, createdByUserId, status, quantity);
    }

    /**
     * Asserts that a rejection came from a structural PostgreSQL invariant
     * (SQLSTATE 23514) with the expected meaning, and not from a deadlock
     * (40P01), a latch timeout, an unrelated SQL error, or a generic
     * application exception.
     */
    private void assertStructuralCheckViolation(Throwable failure, String... messageFragments) {
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
        for (String fragment : messageFragments) {
            assertThat(failure).rootCause().hasMessageContaining(fragment);
        }
    }

    private Map<String, Object> trigger(List<Map<String, Object>> triggers, String name) {
        return triggers.stream()
                .filter(row -> name.equals(row.get("tgname")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing trigger " + name));
    }

    private String definitionOf(Map<String, Object> trigger) {
        return (String) trigger.get("definition");
    }


    private void awaitLatch(CountDownLatch latch, String what) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
