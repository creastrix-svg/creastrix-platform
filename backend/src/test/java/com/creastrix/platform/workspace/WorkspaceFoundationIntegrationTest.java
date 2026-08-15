package com.creastrix.platform.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationNotFoundException;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserNotFoundException;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.application.port.WorkspaceRepository;
import com.creastrix.platform.workspace.domain.Workspace;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotActiveException;
import com.creastrix.platform.workspace.domain.WorkspaceCreatorNotOrganizationOwnerException;
import com.creastrix.platform.workspace.domain.WorkspaceMembership;
import com.creastrix.platform.workspace.domain.WorkspaceMembershipStatus;
import com.creastrix.platform.workspace.domain.WorkspaceNotFoundException;
import com.creastrix.platform.workspace.domain.WorkspaceOwnerType;
import com.creastrix.platform.workspace.domain.WorkspacePermissionScope;
import com.creastrix.platform.workspace.domain.WorkspaceRole;
import com.creastrix.platform.workspace.persistence.JdbcWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/**
 * Integration tests for the Workspace foundation against a real PostgreSQL
 * instance.
 *
 * <p>Database invariants are proven with raw SQL so they are shown to hold
 * independently from the Java application layer. Authentication and external
 * caller identity proof are not implemented and are not tested here.
 */
@SpringBootTest
@Testcontainers
class WorkspaceFoundationIntegrationTest {

    private static final String CHECK_VIOLATION_SQL_STATE = "23514";

    private static final String OWNER_ORGANIZATION_INDEX_NAME =
            "workspaces_owner_organization_id_not_null_idx";

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
    private WorkspaceService workspaceService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

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
    void migrationHistoryIsExactlyV1ThroughV7InOrder() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        // The exact ordered history is asserted, including the V6 foundation
        // and V7 lifecycle migrations. No earlier assertion is weakened.
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    /**
     * Resolves the metadata of the approved lookup index of {@code
     * public.workspaces}, strictly qualified by schema, table, and index name.
     */
    private Map<String, Object> ownerOrganizationIndexMetadata() {
        return jdbcTemplate.queryForMap(
                "SELECT i.indexrelid AS index_oid, "
                        + "c.relname AS index_name, am.amname AS access_method, "
                        + "i.indisunique, i.indisvalid, i.indisready, "
                        + "i.indnatts, i.indnkeyatts, "
                        + "pg_get_expr(i.indpred, i.indrelid) AS predicate, "
                        + "pg_get_indexdef(i.indexrelid) AS definition "
                        + "FROM pg_index i "
                        + "JOIN pg_class c ON c.oid = i.indexrelid "
                        + "JOIN pg_class t ON t.oid = i.indrelid "
                        + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                        + "JOIN pg_am am ON am.oid = c.relam "
                        + "WHERE n.nspname = 'public' AND t.relname = 'workspaces' "
                        + "AND c.relname = ?",
                OWNER_ORGANIZATION_INDEX_NAME);
    }

    /**
     * Reads the columns of exactly the index identified by {@code indexOid}, so
     * an identically named index in another schema cannot contribute a row.
     */
    private List<String> indexColumns(Object indexOid) {
        return jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_attribute a "
                        + "WHERE a.attrelid = CAST(? AS oid) AND a.attnum > 0 "
                        + "ORDER BY a.attnum",
                String.class,
                indexOid);
    }

    @Test
    void v5AddsExactlyTheApprovedOwnerOrganizationLookupIndex() {
        var index = ownerOrganizationIndexMetadata();

        assertThat(index.get("index_name")).isEqualTo(OWNER_ORGANIZATION_INDEX_NAME);
        assertThat(index.get("access_method")).isEqualTo("btree");
        assertThat(index.get("indisunique")).isEqualTo(false);
        assertThat(index.get("indisvalid")).isEqualTo(true);
        assertThat(index.get("indisready")).isEqualTo(true);
        // Exactly one key column and no additional included column.
        assertThat(index.get("indnkeyatts")).isEqualTo(1);
        assertThat(index.get("indnatts")).isEqualTo(1);
        assertThat(index.get("predicate")).isEqualTo("(owner_organization_id IS NOT NULL)");
        assertThat(index.get("definition")).isEqualTo(
                "CREATE INDEX workspaces_owner_organization_id_not_null_idx "
                        + "ON public.workspaces USING btree (owner_organization_id) "
                        + "WHERE (owner_organization_id IS NOT NULL)");

        assertThat(indexColumns(index.get("index_oid")))
                .containsExactly("owner_organization_id");
    }

    /**
     * An identically named index in a different schema must not disturb the
     * schema proof above: the metadata path is bound to the exact index OID of
     * public.workspaces.
     */
    @Test
    void ownerOrganizationIndexMetadataIgnoresIdenticallyNamedIndexInAnotherSchema() {
        var decoySchema = "decoy_" + UUID.randomUUID().toString().replace("-", "");
        var expectedOid = ownerOrganizationIndexMetadata().get("index_oid");
        try {
            jdbcTemplate.execute("CREATE SCHEMA " + decoySchema);
            jdbcTemplate.execute("CREATE TABLE " + decoySchema
                    + ".workspaces (id uuid NOT NULL, owner_organization_id uuid, other_column uuid)");
            jdbcTemplate.execute("CREATE INDEX workspaces_owner_organization_id_not_null_idx ON "
                    + decoySchema + ".workspaces (other_column) "
                    + "WHERE other_column IS NOT NULL");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE c.relname = ? AND c.relkind = 'i'",
                    Integer.class,
                    OWNER_ORGANIZATION_INDEX_NAME))
                    .isEqualTo(2);

            var index = ownerOrganizationIndexMetadata();
            assertThat(index.get("index_oid")).isEqualTo(expectedOid);
            assertThat(indexColumns(index.get("index_oid")))
                    .containsExactly("owner_organization_id");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + decoySchema + " CASCADE");
        }
    }

    @Test
    void v4AddsExactlyTheWorkspaceTables() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'workspace%' "
                        + "ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly(
                "workspace_membership_scopes", "workspace_memberships", "workspaces");
    }

    @Test
    void schemaDeclaresExpectedColumnsAndTypes() {
        var workspaceColumns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'workspaces' ORDER BY column_name");
        assertThat(workspaceColumns)
                .extracting(row -> row.get("column_name"))
                .containsExactly("id", "owner_organization_id", "owner_type", "owner_user_id");
        assertThat(workspaceColumns)
                .extracting(row -> row.get("data_type"))
                .containsExactly("uuid", "uuid", "text", "uuid");
        assertThat(workspaceColumns)
                .extracting(row -> row.get("is_nullable"))
                .containsExactly("NO", "YES", "NO", "YES");
        assertThat(workspaceColumns)
                .allSatisfy(row -> assertThat(row.get("column_default")).isNull());

        var membershipColumns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'workspace_memberships' ORDER BY column_name");
        assertThat(membershipColumns)
                .extracting(row -> row.get("column_name"))
                .containsExactly("role", "status", "user_id", "workspace_id");
        assertThat(membershipColumns)
                .extracting(row -> row.get("data_type"))
                .containsExactly("text", "text", "uuid", "uuid");
        assertThat(membershipColumns)
                .allSatisfy(row -> assertThat(row.get("is_nullable")).isEqualTo("NO"));
        assertThat(membershipColumns)
                .allSatisfy(row -> assertThat(row.get("column_default")).isNull());

        var scopeColumns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'workspace_membership_scopes' ORDER BY column_name");
        assertThat(scopeColumns)
                .extracting(row -> row.get("column_name"))
                .containsExactly("scope", "user_id", "workspace_id");
        assertThat(scopeColumns)
                .extracting(row -> row.get("data_type"))
                .containsExactly("text", "uuid", "uuid");
        assertThat(scopeColumns)
                .allSatisfy(row -> assertThat(row.get("is_nullable")).isEqualTo("NO"));
        assertThat(scopeColumns)
                .allSatisfy(row -> assertThat(row.get("column_default")).isNull());
    }

    @Test
    void schemaDeclaresExpectedKeysAndConstraints() {
        var constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid IN ('workspaces'::regclass, "
                        + "'workspace_memberships'::regclass, "
                        + "'workspace_membership_scopes'::regclass) "
                        + "AND contype IN ('p', 'c', 'f') ORDER BY conname",
                String.class);
        assertThat(constraints).containsExactlyInAnyOrder(
                "workspaces_pk",
                "workspaces_owner_user_fk",
                "workspaces_owner_organization_fk",
                "workspaces_owner_type_allowed",
                "workspaces_owner_shape",
                "workspace_memberships_pk",
                "workspace_memberships_workspace_fk",
                "workspace_memberships_user_fk",
                "workspace_memberships_role_allowed",
                "workspace_memberships_status_allowed",
                "workspace_membership_scopes_pk",
                "workspace_membership_scopes_membership_fk",
                "workspace_membership_scopes_scope_allowed");

        var membershipPkColumns = jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid "
                        + "AND a.attnum = ANY (c.conkey) "
                        + "WHERE c.conname = 'workspace_memberships_pk' ORDER BY a.attname",
                String.class);
        assertThat(membershipPkColumns).containsExactly("user_id", "workspace_id");

        var scopePkColumns = jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid "
                        + "AND a.attnum = ANY (c.conkey) "
                        + "WHERE c.conname = 'workspace_membership_scopes_pk' ORDER BY a.attname",
                String.class);
        assertThat(scopePkColumns).containsExactly("scope", "user_id", "workspace_id");

        var foreignKeys = jdbcTemplate.queryForList(
                "SELECT conname, confdeltype, confrelid::regclass::text AS referenced "
                        + "FROM pg_constraint WHERE contype = 'f' "
                        + "AND conrelid IN ('workspaces'::regclass, "
                        + "'workspace_memberships'::regclass, "
                        + "'workspace_membership_scopes'::regclass) ORDER BY conname");
        // Every Workspace foreign key uses RESTRICT ('r').
        assertThat(foreignKeys).hasSize(5);
        assertThat(foreignKeys)
                .allSatisfy(row -> assertThat(row.get("confdeltype")).isEqualTo("r"));

        // Every closed enum-like V4 CHECK is asserted against the exact canonical
        // pg_get_constraintdef output of PostgreSQL 18.4, so adding any further
        // owner type, role, status, or scope value fails this test.
        assertThat(constraintDefinitionOf("workspaces_owner_type_allowed")).isEqualTo(
                "CHECK ((owner_type = ANY (ARRAY['USER'::text, 'ORGANIZATION'::text])))");
        assertThat(constraintDefinitionOf("workspace_memberships_role_allowed")).isEqualTo(
                "CHECK ((role = ANY (ARRAY['ADMIN'::text, 'EDITOR'::text, 'VIEWER'::text])))");
        assertThat(constraintDefinitionOf("workspace_memberships_status_allowed")).isEqualTo(
                "CHECK ((status = ANY (ARRAY['INVITED'::text, 'ACTIVE'::text, 'SUSPENDED'::text])))");
        assertThat(constraintDefinitionOf("workspace_membership_scopes_scope_allowed")).isEqualTo(
                "CHECK ((scope = ANY (ARRAY['PROJECTS'::text, 'READY_MADE_PRODUCTS'::text, "
                        + "'LISTINGS'::text])))");

        // The owner-shape constraint is not an enum-like value list, so it keeps
        // its structural assertion: each owner type requires exactly its own
        // owner column and forbids the other one.
        String ownerShapeCheck = constraintDefinitionOf("workspaces_owner_shape");
        assertThat(ownerShapeCheck)
                .contains("owner_type = 'USER'::text")
                .contains("owner_type = 'ORGANIZATION'::text")
                .contains("owner_user_id IS NOT NULL")
                .contains("owner_user_id IS NULL")
                .contains("owner_organization_id IS NOT NULL")
                .contains("owner_organization_id IS NULL");
    }

    @Test
    void workspaceTriggersAreDeclaredWithTheExpectedSemantics() {
        var triggers = jdbcTemplate.queryForList(
                "SELECT tgname, tgrelid::regclass::text AS relation, "
                        + "tgconstraint <> 0 AS is_constraint, tgdeferrable, tginitdeferred, "
                        + "pg_get_triggerdef(oid) AS definition "
                        + "FROM pg_trigger "
                        + "WHERE NOT tgisinternal "
                        + "AND tgrelid IN ('workspaces'::regclass, "
                        + "'workspace_memberships'::regclass, "
                        + "'organization_memberships'::regclass) "
                        + "ORDER BY tgname");

        var initialFoundation = trigger(triggers, "workspaces_require_initial_foundation");
        assertThat(initialFoundation.get("relation")).isEqualTo("workspaces");
        assertThat(initialFoundation.get("tgdeferrable")).isEqualTo(true);
        assertThat(initialFoundation.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(initialFoundation))
                .contains("CONSTRAINT TRIGGER")
                .contains("AFTER INSERT ON public.workspaces")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("FOR EACH ROW");

        var preserveFoundation = trigger(triggers, "workspace_memberships_preserve_foundation");
        assertThat(preserveFoundation.get("relation")).isEqualTo("workspace_memberships");
        assertThat(preserveFoundation.get("tgdeferrable")).isEqualTo(true);
        assertThat(preserveFoundation.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(preserveFoundation))
                .contains("CONSTRAINT TRIGGER")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("UPDATE")
                .contains("DELETE");

        var orgPreserve = trigger(triggers, "organization_memberships_preserve_workspace_foundation");
        assertThat(orgPreserve.get("relation")).isEqualTo("organization_memberships");
        assertThat(orgPreserve.get("tgdeferrable")).isEqualTo(true);
        assertThat(orgPreserve.get("tginitdeferred")).isEqualTo(true);

        var ownerChange = trigger(triggers, "workspaces_forbid_owner_change");
        assertThat(definitionOf(ownerChange)).contains("BEFORE UPDATE ON public.workspaces");

        var forbidDelete = trigger(triggers, "workspaces_forbid_delete");
        assertThat(definitionOf(forbidDelete)).contains("BEFORE DELETE ON public.workspaces");

        var forbidTruncate = trigger(triggers, "workspaces_forbid_truncate");
        assertThat(definitionOf(forbidTruncate))
                .contains("AFTER TRUNCATE ON public.workspaces")
                .contains("FOR EACH STATEMENT");

        var membershipTruncate =
                trigger(triggers, "workspace_memberships_preserve_foundation_on_truncate");
        assertThat(definitionOf(membershipTruncate))
                .contains("AFTER TRUNCATE ON public.workspace_memberships")
                .contains("FOR EACH STATEMENT");

        var orgTruncate = trigger(triggers, "organization_memberships_preserve_workspaces_on_truncate");
        assertThat(definitionOf(orgTruncate))
                .contains("AFTER TRUNCATE ON public.organization_memberships")
                .contains("FOR EACH STATEMENT");

        // The lock-bearing trigger functions actually contain the row locking.
        assertThat(functionSource("workspaces_require_initial_foundation")).contains("FOR UPDATE");
        assertThat(functionSource("workspace_memberships_preserve_foundation")).contains("FOR UPDATE");
        assertThat(functionSource("organization_memberships_preserve_workspace_foundation"))
                .contains("FOR UPDATE")
                .contains("ORDER BY id");
    }

    @Test
    void workspaceServiceIsWiredToJdbcWorkspaceRepository() {
        assertThat(workspaceService).isNotNull();
        assertThat(workspaceRepository).isInstanceOf(JdbcWorkspaceRepository.class);
    }

    // ------------------------------------------------------------------
    // Application creation and reads
    // ------------------------------------------------------------------

    @Test
    void activeUserCreatesUserOwnedWorkspaceAtomically() {
        User owner = activeUser();

        Workspace created = workspaceService.createUserOwnedWorkspace(owner.id());

        assertThat(created.id()).isNotNull();
        assertThat(created.ownerType()).isEqualTo(WorkspaceOwnerType.USER);
        assertThat(created.ownerId()).isEqualTo(owner.id());
        assertThat(workspaceCount(created.id())).isEqualTo(1);
        assertThat(membershipCount(created.id())).isEqualTo(1);
        // Initial ADMIN has no stored scope rows: ADMIN access comes from role.
        assertThat(scopeRowCount(created.id())).isZero();

        var membership = jdbcTemplate.queryForMap(
                "SELECT workspace_id, user_id, role, status FROM workspace_memberships "
                        + "WHERE workspace_id = ?",
                created.id());
        assertThat(membership.get("workspace_id")).isEqualTo(created.id());
        assertThat(membership.get("user_id")).isEqualTo(owner.id());
        assertThat(membership.get("role")).isEqualTo("ADMIN");
        assertThat(membership.get("status")).isEqualTo("ACTIVE");

        assertThat(workspaceService.findWorkspace(created.id())).isEqualTo(created);
        assertThat(workspaceService.findMemberships(created.id())).containsExactly(
                new WorkspaceMembership(created.id(), owner.id(),
                        WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()));
    }

    @Test
    void findWorkspaceFailsForUnknownIdentity() {
        UUID unknown = UUID.randomUUID();
        assertThatExceptionOfType(WorkspaceNotFoundException.class)
                .isThrownBy(() -> workspaceService.findWorkspace(unknown))
                .satisfies(exception -> assertThat(exception.workspaceId()).isEqualTo(unknown));
    }

    @Test
    void unknownUserOwnedCreatorIsRejectedAndLeavesNoRows() {
        UUID unknown = UUID.randomUUID();
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");

        assertThatThrownBy(() -> workspaceService.createUserOwnedWorkspace(unknown))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void suspendedUserOwnedCreatorIsRejectedAndLeavesNoRows() {
        User owner = activeUser();
        userService.changeStatus(owner.id(), UserStatus.SUSPENDED);
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");

        assertThatExceptionOfType(WorkspaceCreatorNotActiveException.class)
                .isThrownBy(() -> workspaceService.createUserOwnedWorkspace(owner.id()))
                .satisfies(exception -> {
                    assertThat(exception.creatorUserId()).isEqualTo(owner.id());
                    assertThat(exception.creatorStatus()).isEqualTo(UserStatus.SUSPENDED);
                });

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void deactivatedUserOwnedCreatorIsRejectedAndLeavesNoRows() {
        User owner = activeUser();
        userService.changeStatus(owner.id(), UserStatus.DEACTIVATED);
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");

        assertThatExceptionOfType(WorkspaceCreatorNotActiveException.class)
                .isThrownBy(() -> workspaceService.createUserOwnedWorkspace(owner.id()))
                .satisfies(exception -> {
                    assertThat(exception.creatorUserId()).isEqualTo(owner.id());
                    assertThat(exception.creatorStatus()).isEqualTo(UserStatus.DEACTIVATED);
                });

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void activeOrganizationOwnerCreatesOrganizationOwnedWorkspaceAtomically() {
        User creator = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());

        Workspace created =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), creator.id());

        assertThat(created.ownerType()).isEqualTo(WorkspaceOwnerType.ORGANIZATION);
        assertThat(created.ownerId()).isEqualTo(organization.id());
        assertThat(workspaceCount(created.id())).isEqualTo(1);
        assertThat(membershipCount(created.id())).isEqualTo(1);
        assertThat(scopeRowCount(created.id())).isZero();

        assertThat(workspaceService.findWorkspace(created.id())).isEqualTo(created);
        assertThat(workspaceService.findMemberships(created.id())).containsExactly(
                new WorkspaceMembership(created.id(), creator.id(),
                        WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()));
    }

    @Test
    void unknownOrganizationIsRejectedForOrganizationOwnedCreation() {
        User creator = activeUser();
        UUID unknownOrganization = UUID.randomUUID();
        Integer workspacesBefore = totalRows("workspaces");

        assertThatThrownBy(() -> workspaceService.createOrganizationOwnedWorkspace(
                unknownOrganization, creator.id()))
                .isInstanceOf(OrganizationNotFoundException.class);

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
    }

    @Test
    void nonOwnerCreatorIsRejectedForOrganizationOwnedCreation() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        User outsider = activeUser();
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");

        assertThatExceptionOfType(WorkspaceCreatorNotOrganizationOwnerException.class)
                .isThrownBy(() -> workspaceService.createOrganizationOwnedWorkspace(
                        organization.id(), outsider.id()))
                .satisfies(exception -> {
                    assertThat(exception.organizationId()).isEqualTo(organization.id());
                    assertThat(exception.creatorUserId()).isEqualTo(outsider.id());
                });

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void ownerOfAnotherOrganizationIsRejectedForOrganizationOwnedCreation() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        User otherOwner = activeUser();
        organizationService.createOrganization(otherOwner.id());
        Integer workspacesBefore = totalRows("workspaces");

        assertThatExceptionOfType(WorkspaceCreatorNotOrganizationOwnerException.class)
                .isThrownBy(() -> workspaceService.createOrganizationOwnedWorkspace(
                        organization.id(), otherOwner.id()));

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
    }

    @Test
    void inactiveOrganizationOwnerCreatorIsRejectedAndLeavesNoRows() {
        User creator = activeUser();
        User secondOwner = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());
        addOrganizationOwnerThroughRawSql(organization.id(), secondOwner.id());
        userService.changeStatus(creator.id(), UserStatus.SUSPENDED);
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");
        Integer scopesBefore = totalRows("workspace_membership_scopes");

        assertThatExceptionOfType(WorkspaceCreatorNotActiveException.class)
                .isThrownBy(() -> workspaceService.createOrganizationOwnedWorkspace(
                        organization.id(), creator.id()))
                .satisfies(exception -> {
                    assertThat(exception.creatorUserId()).isEqualTo(creator.id());
                    assertThat(exception.creatorStatus()).isEqualTo(UserStatus.SUSPENDED);
                });

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
        assertThat(totalRows("workspace_membership_scopes")).isEqualTo(scopesBefore);
    }

    @Test
    void findOperationsReconstructBothOwnerTypesAndScopeSets() {
        User owner = activeUser();
        Workspace userOwned = workspaceService.createUserOwnedWorkspace(owner.id());

        User orgOwner = activeUser();
        Organization organization = organizationService.createOrganization(orgOwner.id());
        Workspace organizationOwned =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), orgOwner.id());

        // Add an EDITOR with explicit scope grants through raw SQL to prove
        // reconstruction of the immutable scope set.
        User editor = activeUser();
        transactionTemplate.executeWithoutResult(status -> {
            addMembershipThroughRawSql(userOwned.id(), editor.id(), "EDITOR", "ACTIVE");
            jdbcTemplate.update(
                    "INSERT INTO workspace_membership_scopes (workspace_id, user_id, scope) "
                            + "VALUES (?, ?, 'PROJECTS'), (?, ?, 'LISTINGS')",
                    userOwned.id(), editor.id(), userOwned.id(), editor.id());
        });

        assertThat(workspaceService.findWorkspace(userOwned.id()))
                .isEqualTo(new Workspace(userOwned.id(), WorkspaceOwnerType.USER, owner.id()));
        assertThat(workspaceService.findWorkspace(organizationOwned.id()))
                .isEqualTo(new Workspace(
                        organizationOwned.id(), WorkspaceOwnerType.ORGANIZATION, organization.id()));

        List<WorkspaceMembership> memberships = workspaceService.findMemberships(userOwned.id());
        assertThat(memberships).containsExactlyInAnyOrder(
                new WorkspaceMembership(userOwned.id(), owner.id(),
                        WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()),
                new WorkspaceMembership(userOwned.id(), editor.id(),
                        WorkspaceRole.EDITOR, WorkspaceMembershipStatus.ACTIVE,
                        Set.of(WorkspacePermissionScope.PROJECTS, WorkspacePermissionScope.LISTINGS)));
        // Deterministic ordering by user_id, matching PostgreSQL's uuid order
        // (which differs from Java's signed UUID comparison).
        List<UUID> databaseOrder = jdbcTemplate.queryForList(
                "SELECT user_id FROM workspace_memberships WHERE workspace_id = ? ORDER BY user_id",
                UUID.class, userOwned.id());
        assertThat(memberships)
                .extracting(WorkspaceMembership::userId)
                .containsExactlyElementsOf(databaseOrder);
    }

    // ------------------------------------------------------------------
    // Raw SQL structural tests: owner shape
    // ------------------------------------------------------------------

    @Test
    void bothOwnerColumnsSetIsRejected() {
        User user = activeUser();
        Organization organization = organizationService.createOrganization(user.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                                + "VALUES (?, 'USER', ?, ?)",
                        UUID.randomUUID(), user.id(), organization.id())))
                .hasStackTraceContaining("workspaces_owner_shape");
    }

    @Test
    void neitherOwnerColumnSetIsRejected() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                                + "VALUES (?, 'USER', NULL, NULL)",
                        UUID.randomUUID())))
                .hasStackTraceContaining("workspaces_owner_shape");
    }

    @Test
    void ownerTypeAndColumnMismatchIsRejected() {
        Organization organization = organizationService.createOrganization(activeUser().id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                                + "VALUES (?, 'USER', NULL, ?)",
                        UUID.randomUUID(), organization.id())))
                .hasStackTraceContaining("workspaces_owner_shape");
    }

    @Test
    void unknownOwnerTypeIsRejected() {
        User user = activeUser();

        // An unknown owner type violates both the owner-type check and the
        // owner-shape check (whose USER/ORGANIZATION arms cannot match), so
        // PostgreSQL may report either constraint. Both are SQLSTATE 23514.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                                + "VALUES (?, 'TEAM', ?, NULL)",
                        UUID.randomUUID(), user.id())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("workspaces_owner")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    @Test
    void unknownUserOrOrganizationOwnerIsRejected() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                                + "VALUES (?, 'USER', ?, NULL)",
                        UUID.randomUUID(), UUID.randomUUID())))
                .hasStackTraceContaining("workspaces_owner_user_fk");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                                + "VALUES (?, 'ORGANIZATION', NULL, ?)",
                        UUID.randomUUID(), UUID.randomUUID())))
                .hasStackTraceContaining("workspaces_owner_organization_fk");
    }

    // ------------------------------------------------------------------
    // Raw SQL structural tests: creation foundation
    // ------------------------------------------------------------------

    @Test
    void workspaceWithoutValidInitialMembershipIsRejectedAtCommit() {
        User user = activeUser();
        UUID orphan = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                insertUserOwnedWorkspaceThroughRawSql(orphan, user.id())))
                .hasStackTraceContaining("ACTIVE ADMIN Workspace Membership");

        assertThat(workspaceCount(orphan)).isZero();
    }

    @Test
    void userOwnedWorkspaceWithAnotherUserAsInitialAdminIsRejected() {
        User owner = activeUser();
        User other = activeUser();
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertUserOwnedWorkspaceThroughRawSql(workspaceId, owner.id());
            addMembershipThroughRawSql(workspaceId, other.id(), "ADMIN", "ACTIVE");
        }))
                .hasStackTraceContaining("must retain its owner User");

        assertThat(workspaceCount(workspaceId)).isZero();
    }

    @Test
    void organizationOwnedWorkspaceWhoseAdminIsNotActiveOrganizationOwnerIsRejected() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        User outsider = activeUser();
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertOrganizationOwnedWorkspaceThroughRawSql(workspaceId, organization.id());
            addMembershipThroughRawSql(workspaceId, outsider.id(), "ADMIN", "ACTIVE");
        }))
                .hasStackTraceContaining("ACTIVE OWNER");

        assertThat(workspaceCount(workspaceId)).isZero();
    }

    /**
     * Direct-SQL proof that the creation-time ACTIVE owner requirement of a
     * User-owned Workspace is enforced by PostgreSQL itself, and that
     * WorkspaceService prevalidation is not the sole enforcement mechanism.
     * The whole shape is written with raw SQL, bypassing the application
     * creation path entirely.
     */
    @Test
    void userOwnedWorkspaceCreatedForASuspendedOwnerIsRejectedByPostgreSqlItself() {
        User owner = activeUser();
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", owner.id());
            insertUserOwnedWorkspaceThroughRawSql(workspaceId, owner.id());
            addMembershipThroughRawSql(workspaceId, owner.id(), "ADMIN", "ACTIVE");
        }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("requires an ACTIVE owner User")
                .hasMessageContaining("SUSPENDED")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(workspaceCount(workspaceId)).isZero();
        assertThat(membershipCount(workspaceId)).isZero();
        assertThat(scopeRowCount(workspaceId)).isZero();
        assertThat(userService.findUser(owner.id()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * Direct-SQL proof that a structurally ACTIVE OWNER Organization
     * Membership alone is not sufficient at creation: the creator's User
     * account must additionally be ACTIVE at the linearized creation point.
     * The whole shape is written with raw SQL, bypassing WorkspaceService.
     */
    @Test
    void organizationOwnedWorkspaceCreatedByADeactivatedCreatorIsRejectedByPostgreSqlItself() {
        User creator = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            // The structurally ACTIVE OWNER Organization Membership is left
            // untouched; only the User account status becomes non-ACTIVE.
            jdbcTemplate.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", creator.id());
            insertOrganizationOwnedWorkspaceThroughRawSql(workspaceId, organization.id());
            addMembershipThroughRawSql(workspaceId, creator.id(), "ADMIN", "ACTIVE");
        }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("requires a creator who is an ACTIVE User")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(workspaceCount(workspaceId)).isZero();
        assertThat(membershipCount(workspaceId)).isZero();
        assertThat(scopeRowCount(workspaceId)).isZero();
        assertThat(organizationService.findOrganization(organization.id())).isEqualTo(organization);
        Integer activeOwnerMemberships = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_memberships "
                        + "WHERE organization_id = ? AND user_id = ? "
                        + "AND role = 'OWNER' AND status = 'ACTIVE'",
                Integer.class, organization.id(), creator.id());
        assertThat(activeOwnerMemberships).isEqualTo(1);
        assertThat(userService.findUser(creator.id()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // Raw SQL structural tests: Membership and scope constraints
    // ------------------------------------------------------------------

    @Test
    void membershipWithoutWorkspaceOrUserIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        User user = activeUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(UUID.randomUUID(), user.id(), "EDITOR", "ACTIVE")))
                .hasStackTraceContaining("workspace_memberships_workspace_fk");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), UUID.randomUUID(), "EDITOR", "ACTIVE")))
                .hasStackTraceContaining("workspace_memberships_user_fk");
    }

    @Test
    void duplicateMembershipForSameUserAndWorkspaceIsRejected() {
        User owner = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(owner.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), owner.id(), "EDITOR", "ACTIVE")))
                .hasStackTraceContaining("workspace_memberships_pk");

        assertThat(membershipCount(workspace.id())).isEqualTo(1);
    }

    @Test
    void unknownRoleIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        User other = activeUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), other.id(), "OWNER", "ACTIVE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("workspace_memberships_role_allowed")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    @Test
    void unknownStatusIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        User other = activeUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), other.id(), "EDITOR", "DISABLED")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("workspace_memberships_status_allowed")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    @Test
    void duplicateScopeIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        User editor = activeUser();
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE"));
        addScopeThroughRawSql(workspace.id(), editor.id(), "PROJECTS");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addScopeThroughRawSql(workspace.id(), editor.id(), "PROJECTS")))
                .hasStackTraceContaining("workspace_membership_scopes_pk");
    }

    @Test
    void unknownScopeIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        User editor = activeUser();
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), editor.id(), "EDITOR", "ACTIVE"));

        // Unknown and not-yet-introduced future scopes cannot be stored or
        // pre-granted.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addScopeThroughRawSql(workspace.id(), editor.id(), "ORDERS")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("workspace_membership_scopes_scope_allowed")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    @Test
    void scopeWithoutMembershipIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        User stranger = activeUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addScopeThroughRawSql(workspace.id(), stranger.id(), "PROJECTS")))
                .hasStackTraceContaining("workspace_membership_scopes_membership_fk");
    }

    // ------------------------------------------------------------------
    // Raw SQL structural tests: immutability, deletion, TRUNCATE
    // ------------------------------------------------------------------

    @Test
    void workspaceOwnerChangeIsRejected() {
        User owner = activeUser();
        User other = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(owner.id());
        Organization organization = organizationService.createOrganization(other.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE workspaces SET owner_user_id = ? WHERE id = ?",
                        other.id(), workspace.id())))
                .hasStackTraceContaining("immutable");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE workspaces SET owner_type = 'ORGANIZATION', owner_user_id = NULL, "
                                + "owner_organization_id = ? WHERE id = ?",
                        organization.id(), workspace.id())))
                .hasStackTraceContaining("immutable");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE workspaces SET id = ? WHERE id = ?",
                        UUID.randomUUID(), workspace.id())))
                .hasStackTraceContaining("immutable");

        assertThat(workspaceService.findWorkspace(workspace.id())).isEqualTo(workspace);
    }

    @Test
    void workspaceDeletionIsRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspace.id())))
                .hasStackTraceContaining("cannot be deleted");

        assertThat(workspaceCount(workspace.id())).isEqualTo(1);
    }

    @Test
    void workspaceTruncateAndCascadeBypassesAreRejected() {
        Workspace workspace = workspaceService.createUserOwnedWorkspace(activeUser().id());
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE workspaces CASCADE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("TRUNCATE of workspaces is not supported")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        // A CASCADE started from a referenced table cannot bypass the guard
        // either: it truncates workspaces too, and the statement trigger fires.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE users CASCADE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
        assertThat(workspaceCount(workspace.id())).isEqualTo(1);
    }

    @Test
    void membershipTruncateProtectionsRemainEffective() {
        workspaceService.createUserOwnedWorkspace(activeUser().id());
        Integer membershipsBefore = totalRows("workspace_memberships");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE workspace_memberships CASCADE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("TRUNCATE of workspace_memberships would leave an existing Workspace")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void organizationMembershipTruncateProtectionsRemainEffective() {
        User owner = activeUser();
        Organization organization = organizationService.createOrganization(owner.id());
        workspaceService.createOrganizationOwnedWorkspace(organization.id(), owner.id());
        Integer organizationMembershipsBefore = totalRows("organization_memberships");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE organization_memberships")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(totalRows("organization_memberships")).isEqualTo(organizationMembershipsBefore);
    }

    // ------------------------------------------------------------------
    // Raw SQL structural tests: permanent foundation
    // ------------------------------------------------------------------

    @Test
    void removingSuspendingOrDemotingTheUserOwnersMembershipIsRejected() {
        User owner = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(owner.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), owner.id())))
                .hasStackTraceContaining("must retain its owner User");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE workspace_memberships SET status = 'SUSPENDED' "
                                + "WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), owner.id())))
                .hasStackTraceContaining("must retain its owner User");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE workspace_memberships SET role = 'EDITOR' "
                                + "WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), owner.id())))
                .hasStackTraceContaining("must retain its owner User");

        assertThat(activeAdminCount(workspace.id())).isEqualTo(1);
    }

    @Test
    void extraExternalAdminDoesNotPermitRemovingTheUserOwnersAdminMembership() {
        User owner = activeUser();
        User externalAdmin = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(owner.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), externalAdmin.id(), "ADMIN", "ACTIVE"));
        assertThat(activeAdminCount(workspace.id())).isEqualTo(2);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), owner.id())))
                .hasStackTraceContaining("must retain its owner User");

        assertThat(activeAdminCount(workspace.id())).isEqualTo(2);
    }

    @Test
    void removingTheLastActiveAdminIsRejected() {
        User creator = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());
        Workspace workspace =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), creator.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), creator.id())))
                .hasStackTraceContaining("ACTIVE ADMIN");

        assertThat(activeAdminCount(workspace.id())).isEqualTo(1);
    }

    @Test
    void removingOneOfSeveralEligibleAdminsIsAllowed() {
        User ownerA = activeUser();
        User ownerB = activeUser();
        Organization organization = organizationService.createOrganization(ownerA.id());
        addOrganizationOwnerThroughRawSql(organization.id(), ownerB.id());
        Workspace workspace =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), ownerA.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), ownerB.id(), "ADMIN", "ACTIVE"));
        assertThat(activeAdminCount(workspace.id())).isEqualTo(2);

        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), ownerA.id()));

        assertThat(activeAdminCount(workspace.id())).isEqualTo(1);
    }

    @Test
    void externalAdminAloneCannotReplaceTheLastDualQualifiedOrganizationOwnerAdmin() {
        User dualQualified = activeUser();
        User externalAdmin = activeUser();
        Organization organization = organizationService.createOrganization(dualQualified.id());
        Workspace workspace = workspaceService.createOrganizationOwnedWorkspace(
                organization.id(), dualQualified.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), externalAdmin.id(), "ADMIN", "ACTIVE"));
        assertThat(activeAdminCount(workspace.id())).isEqualTo(2);

        // The external ADMIN is not an Organization OWNER, so it cannot be the
        // only remaining administrative representation of the owning Organization.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), dualQualified.id())))
                .hasStackTraceContaining("ACTIVE OWNER");

        assertThat(activeAdminCount(workspace.id())).isEqualTo(2);
    }

    @Test
    void ownerWithoutAdminPlusAdminWithoutOwnerDoesNotSatisfyTheIntersection() {
        User dualQualified = activeUser();
        User ownerOnly = activeUser();
        User adminOnly = activeUser();
        Organization organization = organizationService.createOrganization(dualQualified.id());
        addOrganizationOwnerThroughRawSql(organization.id(), ownerOnly.id());
        Workspace workspace = workspaceService.createOrganizationOwnedWorkspace(
                organization.id(), dualQualified.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), adminOnly.id(), "ADMIN", "ACTIVE"));

        // ownerOnly is OWNER without Workspace ADMIN; adminOnly is Workspace
        // ADMIN without Organization OWNER. Their sum must not satisfy the
        // same-User intersection.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), dualQualified.id())))
                .hasStackTraceContaining("ACTIVE OWNER");
    }

    @Test
    void aRealReplacementUserSatisfyingBothSidesAllowsReplacement() {
        User original = activeUser();
        User replacement = activeUser();
        Organization organization = organizationService.createOrganization(original.id());
        addOrganizationOwnerThroughRawSql(organization.id(), replacement.id());
        Workspace workspace = workspaceService.createOrganizationOwnedWorkspace(
                organization.id(), original.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), replacement.id(), "ADMIN", "ACTIVE"));

        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                        workspace.id(), original.id()));

        assertThat(activeAdminCount(workspace.id())).isEqualTo(1);
        assertThat(dualQualifiedCount(workspace.id(), organization.id())).isEqualTo(1);
    }

    @Test
    void organizationMembershipChangesPreserveEveryAffectedWorkspaceIndependently() {
        User ownerA = activeUser();
        User ownerB = activeUser();
        Organization organization = organizationService.createOrganization(ownerA.id());
        addOrganizationOwnerThroughRawSql(organization.id(), ownerB.id());
        Workspace workspaceA = workspaceService.createOrganizationOwnedWorkspace(
                organization.id(), ownerA.id());
        Workspace workspaceB = workspaceService.createOrganizationOwnedWorkspace(
                organization.id(), ownerB.id());

        // ownerA is the only dual-qualified User of workspaceA: removing A's
        // Organization OWNER Membership must fail because workspaceA would be
        // orphaned, even though workspaceB remains fine through ownerB.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM organization_memberships "
                                + "WHERE organization_id = ? AND user_id = ?",
                        organization.id(), ownerA.id())))
                .hasStackTraceContaining("ACTIVE OWNER");

        // With B added as ADMIN of workspaceA, each Workspace can rely on a
        // different replacement User, so removing A's OWNER Membership succeeds.
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspaceA.id(), ownerB.id(), "ADMIN", "ACTIVE"));
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM organization_memberships "
                                + "WHERE organization_id = ? AND user_id = ?",
                        organization.id(), ownerA.id()));

        assertThat(dualQualifiedCount(workspaceA.id(), organization.id())).isEqualTo(1);
        assertThat(dualQualifiedCount(workspaceB.id(), organization.id())).isEqualTo(1);
    }

    @Test
    void userStatusChangeNeverMutatesWorkspaceMembershipOrBreaksStructuralCounts() {
        User owner = activeUser();
        Workspace userOwned = workspaceService.createUserOwnedWorkspace(owner.id());

        User orgOwner = activeUser();
        Organization organization = organizationService.createOrganization(orgOwner.id());
        Workspace organizationOwned = workspaceService.createOrganizationOwnedWorkspace(
                organization.id(), orgOwner.id());

        // All structural Users may become non-actionable without rewriting
        // Memberships or breaking the structural counts.
        userService.changeStatus(owner.id(), UserStatus.SUSPENDED);
        userService.changeStatus(owner.id(), UserStatus.DEACTIVATED);
        userService.changeStatus(orgOwner.id(), UserStatus.SUSPENDED);
        userService.changeStatus(orgOwner.id(), UserStatus.DEACTIVATED);

        assertThat(workspaceService.findMemberships(userOwned.id())).containsExactly(
                new WorkspaceMembership(userOwned.id(), owner.id(),
                        WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()));
        assertThat(workspaceService.findMemberships(organizationOwned.id())).containsExactly(
                new WorkspaceMembership(organizationOwned.id(), orgOwner.id(),
                        WorkspaceRole.ADMIN, WorkspaceMembershipStatus.ACTIVE, Set.of()));
        assertThat(activeAdminCount(userOwned.id())).isEqualTo(1);
        assertThat(activeAdminCount(organizationOwned.id())).isEqualTo(1);
        assertThat(dualQualifiedCount(organizationOwned.id(), organization.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Real concurrency races
    // ------------------------------------------------------------------

    /**
     * Race A — last ACTIVE ADMIN: two concurrent real transactions each remove
     * a different ACTIVE ADMIN of the same Workspace. The deferred foundation
     * trigger serializes both commits through a FOR UPDATE lock on the parent
     * Workspace row, so exactly one removal may commit and the other must be
     * rejected, leaving one ACTIVE ADMIN.
     */
    @Test
    @Timeout(60)
    void raceA_concurrentRemovalOfBothActiveAdminsCannotCommitTwice() throws Exception {
        User ownerA = activeUser();
        User ownerB = activeUser();
        Organization organization = organizationService.createOrganization(ownerA.id());
        addOrganizationOwnerThroughRawSql(organization.id(), ownerB.id());
        Workspace workspace =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), ownerA.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), ownerB.id(), "ADMIN", "ACTIVE"));
        assertThat(activeAdminCount(workspace.id())).isEqualTo(2);

        CountDownLatch bothChangesExecuted = new CountDownLatch(2);
        List<Exception> failures = runConcurrently(
                synchronizedTransaction(bothChangesExecuted, () ->
                        jdbcTemplate.update(
                                "DELETE FROM workspace_memberships "
                                        + "WHERE workspace_id = ? AND user_id = ?",
                                workspace.id(), ownerA.id())),
                synchronizedTransaction(bothChangesExecuted, () ->
                        jdbcTemplate.update(
                                "DELETE FROM workspace_memberships "
                                        + "WHERE workspace_id = ? AND user_id = ?",
                                workspace.id(), ownerB.id())));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).hasStackTraceContaining("ACTIVE");
        assertThat(activeAdminCount(workspace.id())).isEqualTo(1);
        assertThat(workspaceCount(workspace.id())).isEqualTo(1);
    }

    /**
     * Race B — cross-table OWNER/ADMIN write skew: Users A and B are both
     * dual-qualified (ACTIVE Organization OWNER + ACTIVE Workspace ADMIN). One
     * transaction removes A's Workspace ADMIN qualification while another
     * concurrently removes B's Organization OWNER qualification. Serialization
     * across the two Membership tables happens through the Workspace row lock
     * taken by both triggers, so exactly one transaction must fail and at
     * least one dual-qualified User must survive.
     */
    @Test
    @Timeout(60)
    void raceB_crossTableOwnerAdminWriteSkewCannotCommitTwice() throws Exception {
        User userA = activeUser();
        User userB = activeUser();
        Organization organization = organizationService.createOrganization(userA.id());
        addOrganizationOwnerThroughRawSql(organization.id(), userB.id());
        Workspace workspace =
                workspaceService.createOrganizationOwnedWorkspace(organization.id(), userA.id());
        transactionTemplate.executeWithoutResult(status ->
                addMembershipThroughRawSql(workspace.id(), userB.id(), "ADMIN", "ACTIVE"));
        assertThat(dualQualifiedCount(workspace.id(), organization.id())).isEqualTo(2);

        CountDownLatch bothChangesExecuted = new CountDownLatch(2);
        List<Exception> failures = runConcurrently(
                synchronizedTransaction(bothChangesExecuted, () ->
                        jdbcTemplate.update(
                                "DELETE FROM workspace_memberships "
                                        + "WHERE workspace_id = ? AND user_id = ?",
                                workspace.id(), userA.id())),
                synchronizedTransaction(bothChangesExecuted, () ->
                        jdbcTemplate.update(
                                "DELETE FROM organization_memberships "
                                        + "WHERE organization_id = ? AND user_id = ?",
                                organization.id(), userB.id())));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).hasStackTraceContaining("ACTIVE OWNER");
        assertThat(dualQualifiedCount(workspace.id(), organization.id()))
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * Race C — creation versus creator OWNER removal: A creates an
     * Organization-owned Workspace with A as initial ACTIVE ADMIN while
     * another transaction concurrently removes A's Organization OWNER
     * Membership (B remains an ACTIVE OWNER, so the Organization itself stays
     * valid). Both SQL changes occur before either transaction is released
     * toward commit; the owning Organization row lock serializes them, so
     * exactly one transaction may succeed.
     */
    @Test
    @Timeout(60)
    void raceC_creationVersusCreatorOwnerRemovalCannotBothCommit() throws Exception {
        User userA = activeUser();
        User userB = activeUser();
        Organization organization = organizationService.createOrganization(userA.id());
        addOrganizationOwnerThroughRawSql(organization.id(), userB.id());
        Integer workspacesBefore = totalRows("workspaces");

        List<UUID> createdWorkspaceId = new ArrayList<>();
        CountDownLatch bothChangesExecuted = new CountDownLatch(2);
        List<Exception> failures = runConcurrently(
                synchronizedTransaction(bothChangesExecuted, () -> {
                    // Joins the surrounding transaction (REQUIRED propagation),
                    // so the creation commits only after both changes executed.
                    Workspace created = workspaceService.createOrganizationOwnedWorkspace(
                            organization.id(), userA.id());
                    createdWorkspaceId.add(created.id());
                }),
                synchronizedTransaction(bothChangesExecuted, () ->
                        jdbcTemplate.update(
                                "DELETE FROM organization_memberships "
                                        + "WHERE organization_id = ? AND user_id = ?",
                                organization.id(), userA.id())));

        assertThat(failures).hasSize(1);
        // The loser must fail because of the intended structural foundation
        // rule, not because of a deadlock (40P01), a latch timeout, an
        // unrelated SQL error, or a generic application exception. Either the
        // creation-time or the preservation-time message identifies the
        // ACTIVE OWNER / ACTIVE ADMIN requirement.
        assertStructuralCheckViolation(failures.get(0),
                "ACTIVE OWNER of Organization", "ACTIVE ADMIN");

        Integer remainingOwnerAMemberships = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_memberships "
                        + "WHERE organization_id = ? AND user_id = ?",
                Integer.class, organization.id(), userA.id());
        Integer workspacesAfter = totalRows("workspaces");

        if (remainingOwnerAMemberships == 1) {
            // Workspace creation won: A's OWNER Membership remains and the new
            // Workspace is committed and valid.
            assertThat(workspacesAfter).isEqualTo(workspacesBefore + 1);
            UUID workspaceId = createdWorkspaceId.get(0);
            assertThat(workspaceCount(workspaceId)).isEqualTo(1);
            assertThat(dualQualifiedCount(workspaceId, organization.id())).isEqualTo(1);
        } else {
            // OWNER removal won: creation failed and left no partial Workspace.
            assertThat(workspacesAfter).isEqualTo(workspacesBefore);
        }

        // The Organization retains B as ACTIVE OWNER either way.
        Integer remainingOwnerB = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_memberships "
                        + "WHERE organization_id = ? AND user_id = ? "
                        + "AND role = 'OWNER' AND status = 'ACTIVE'",
                Integer.class, organization.id(), userB.id());
        assertThat(remainingOwnerB).isEqualTo(1);

        // No committed Organization-owned Workspace lacks the dual-qualified
        // representation.
        Integer orphanedOrganizationWorkspaces = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspaces w "
                        + "WHERE w.owner_type = 'ORGANIZATION' AND NOT EXISTS ("
                        + "SELECT 1 FROM workspace_memberships wm "
                        + "JOIN organization_memberships om ON om.user_id = wm.user_id "
                        + "WHERE wm.workspace_id = w.id AND wm.role = 'ADMIN' "
                        + "AND wm.status = 'ACTIVE' "
                        + "AND om.organization_id = w.owner_organization_id "
                        + "AND om.role = 'OWNER' AND om.status = 'ACTIVE')",
                Integer.class);
        assertThat(orphanedOrganizationWorkspaces).isZero();
    }

    /**
     * Race D — Workspace creation versus a concurrent User
     * {@code ACTIVE -> non-ACTIVE} status change, for both creation paths.
     *
     * <p>The interleaving is fully deterministic through three latches and
     * without any sleep: the status transaction updates the User row first and
     * keeps its transaction open, so it holds the User row lock; the creation
     * transaction then performs application prevalidation, which under READ
     * COMMITTED still observes the committed ACTIVE version, and inserts the
     * Workspace and its initial Membership; only after both SQL changes have
     * happened is the status transaction released to commit first; the creation
     * transaction is released afterwards, so its deferred trigger acquires the
     * User lock, observes the committed non-ACTIVE status, and must reject the
     * creation. Application prevalidation therefore cannot be the sole
     * enforcement mechanism.
     */
    @ParameterizedTest(name = "{0}-owned creation racing User ACTIVE -> {1}")
    @CsvSource({"USER, SUSPENDED", "ORGANIZATION, DEACTIVATED"})
    @Timeout(60)
    void raceD_creationVersusUserStatusChangeIsRejectedAtCommit(
            WorkspaceOwnerType ownerType, UserStatus newStatus) throws Exception {
        User creator = activeUser();
        Organization organization = ownerType == WorkspaceOwnerType.ORGANIZATION
                ? organizationService.createOrganization(creator.id())
                : null;
        Integer workspacesBefore = totalRows("workspaces");
        Integer membershipsBefore = totalRows("workspace_memberships");
        Integer scopesBefore = totalRows("workspace_membership_scopes");

        CountDownLatch statusUpdated = new CountDownLatch(1);
        CountDownLatch creationInserted = new CountDownLatch(1);
        CountDownLatch statusCommitted = new CountDownLatch(1);

        Callable<Exception> statusTransaction = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            "UPDATE users SET status = ? WHERE id = ?",
                            newStatus.name(), creator.id());
                    // The uncommitted UPDATE holds the User row lock.
                    statusUpdated.countDown();
                    awaitLatch(creationInserted, "the concurrent creation insert");
                });
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                statusCommitted.countDown();
            }
        };

        Callable<Exception> creationTransaction = () -> {
            try {
                awaitLatch(statusUpdated, "the concurrent User status update");
                transactionTemplate.executeWithoutResult(status -> {
                    // Prevalidation reads the still-committed ACTIVE version.
                    if (organization == null) {
                        workspaceService.createUserOwnedWorkspace(creator.id());
                    } else {
                        workspaceService.createOrganizationOwnedWorkspace(
                                organization.id(), creator.id());
                    }
                    creationInserted.countDown();
                    awaitLatch(statusCommitted, "the concurrent status commit");
                });
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                creationInserted.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception statusOutcome;
        Exception creationOutcome;
        try {
            Future<Exception> statusFuture = executor.submit(statusTransaction);
            Future<Exception> creationFuture = executor.submit(creationTransaction);
            statusOutcome = statusFuture.get(30, TimeUnit.SECONDS);
            creationOutcome = creationFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(statusOutcome).isNull();
        assertThat(creationOutcome).isNotNull();
        assertStructuralCheckViolation(creationOutcome, ownerType == WorkspaceOwnerType.USER
                ? "requires an ACTIVE owner User"
                : "requires a creator who is an ACTIVE User");

        assertThat(userService.findUser(creator.id()).status()).isEqualTo(newStatus);
        assertThat(totalRows("workspaces")).isEqualTo(workspacesBefore);
        assertThat(totalRows("workspace_memberships")).isEqualTo(membershipsBefore);
        assertThat(totalRows("workspace_membership_scopes")).isEqualTo(scopesBefore);

        if (organization != null) {
            assertThat(organizationService.findOrganization(organization.id()))
                    .isEqualTo(organization);
            Integer activeOwnerMemberships = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM organization_memberships "
                            + "WHERE organization_id = ? AND user_id = ? "
                            + "AND role = 'OWNER' AND status = 'ACTIVE'",
                    Integer.class, organization.id(), creator.id());
            assertThat(activeOwnerMemberships).isEqualTo(1);
        }
    }

    /**
     * Asserts that a concurrent transaction failed because of a structural
     * PostgreSQL invariant (SQLSTATE 23514), and not because of a deadlock
     * (40P01), a latch timeout, an unrelated SQL error, or a generic
     * application exception.
     */
    private void assertStructuralCheckViolation(Exception failure, String... messageFragments) {
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

    private String constraintDefinitionOf(String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, constraintName);
    }

    /**
     * Runs both actions in separate real transactions on separate connections
     * and returns the commit-time failures (empty if both committed).
     */
    private List<Exception> runConcurrently(Callable<Exception> first, Callable<Exception> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Exception> t1 = executor.submit(first);
            Future<Exception> t2 = executor.submit(second);
            Exception outcome1 = t1.get(30, TimeUnit.SECONDS);
            Exception outcome2 = t2.get(30, TimeUnit.SECONDS);
            List<Exception> failures = new ArrayList<>();
            if (outcome1 != null) {
                failures.add(outcome1);
            }
            if (outcome2 != null) {
                failures.add(outcome2);
            }
            return failures;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Executes the change in its own real transaction. Both concurrent changes
     * are synchronized through the latch to occur before either transaction is
     * released toward commit. Returns null on successful commit, or the
     * commit-time exception.
     */
    private Callable<Exception> synchronizedTransaction(CountDownLatch bothChangesExecuted, Runnable change) {
        return () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    change.run();
                    bothChangesExecuted.countDown();
                    try {
                        if (!bothChangesExecuted.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Timed out waiting for the concurrent change");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
                return null;
            } catch (Exception e) {
                return e;
            }
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Integer dualQualifiedCount(UUID workspaceId, UUID organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_memberships wm "
                        + "JOIN organization_memberships om ON om.user_id = wm.user_id "
                        + "WHERE wm.workspace_id = ? AND wm.role = 'ADMIN' AND wm.status = 'ACTIVE' "
                        + "AND om.organization_id = ? AND om.role = 'OWNER' AND om.status = 'ACTIVE'",
                Integer.class, workspaceId, organizationId);
    }

    private void addScopeThroughRawSql(UUID workspaceId, UUID userId, String scope) {
        jdbcTemplate.update(
                "INSERT INTO workspace_membership_scopes (workspace_id, user_id, scope) "
                        + "VALUES (?, ?, ?)",
                workspaceId, userId, scope);
    }

    private void insertUserOwnedWorkspaceThroughRawSql(UUID workspaceId, UUID ownerUserId) {
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                        + "VALUES (?, 'USER', ?, NULL)",
                workspaceId, ownerUserId);
    }

    private void insertOrganizationOwnedWorkspaceThroughRawSql(UUID workspaceId, UUID organizationId) {
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, owner_type, owner_user_id, owner_organization_id) "
                        + "VALUES (?, 'ORGANIZATION', NULL, ?)",
                workspaceId, organizationId);
    }

    private String functionSource(String functionName) {
        return jdbcTemplate.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE proname = ?", String.class, functionName);
    }

    private Integer totalRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private User activeUser() {
        return userService.createUser();
    }

    private Integer workspaceCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspaces WHERE id = ?", Integer.class, workspaceId);
    }

    private Integer membershipCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_memberships WHERE workspace_id = ?",
                Integer.class, workspaceId);
    }

    private Integer scopeRowCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_membership_scopes WHERE workspace_id = ?",
                Integer.class, workspaceId);
    }

    private Integer activeAdminCount(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_memberships WHERE workspace_id = ? "
                        + "AND role = 'ADMIN' AND status = 'ACTIVE'",
                Integer.class, workspaceId);
    }

    private void addMembershipThroughRawSql(UUID workspaceId, UUID userId, String role, String status) {
        jdbcTemplate.update(
                "INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                        + "VALUES (?, ?, ?, ?)",
                workspaceId, userId, role, status);
    }

    private void addOrganizationOwnerThroughRawSql(UUID organizationId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO organization_memberships (organization_id, user_id, role, status) "
                        + "VALUES (?, ?, 'OWNER', 'ACTIVE')",
                organizationId, userId);
    }

    private static Map<String, Object> trigger(List<Map<String, Object>> triggers, String name) {
        return triggers.stream()
                .filter(row -> name.equals(row.get("tgname")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Trigger not found: " + name));
    }

    private static String definitionOf(Map<String, Object> trigger) {
        return String.valueOf(trigger.get("definition"));
    }
}
