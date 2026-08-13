package com.creastrix.platform.organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.organization.application.port.OrganizationRepository;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationCreatorNotActiveException;
import com.creastrix.platform.organization.domain.OrganizationMembership;
import com.creastrix.platform.organization.domain.OrganizationMembershipStatus;
import com.creastrix.platform.organization.domain.OrganizationNotFoundException;
import com.creastrix.platform.organization.domain.OrganizationRole;
import com.creastrix.platform.organization.persistence.JdbcOrganizationRepository;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserNotFoundException;
import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
 * Integration tests for the Organization foundation against a real PostgreSQL
 * instance.
 *
 * <p>Database invariants are proven with raw SQL so they are shown to hold
 * independently from the Java application layer.
 */
@SpringBootTest
@Testcontainers
class OrganizationFoundationIntegrationTest {

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
    private OrganizationService organizationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void realPostgresIsUsed() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("18.4");
        }
    }

    @Test
    void v3MigrationIsAppliedAfterV1AndV2() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        // Later migrations (V4+) may follow; this test only verifies that V3 is
        // applied after V1 and V2 in order. The Workspace foundation test owns
        // the full V1..V4 history assertion.
        assertThat(versions).startsWith("1", "2", "3");
    }

    @Test
    void v3AddsOrganizationsAndOrganizationMembershipsTables() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name IN ('organizations', 'organization_memberships') "
                        + "ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly("organization_memberships", "organizations");
    }

    @Test
    void schemaDeclaresExpectedColumnsAndTypes() {
        var organizationColumns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'organizations' ORDER BY column_name");
        assertThat(organizationColumns).hasSize(1);
        assertThat(organizationColumns.get(0))
                .containsEntry("column_name", "id")
                .containsEntry("data_type", "uuid")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", null);

        var membershipColumns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'organization_memberships' ORDER BY column_name");
        assertThat(membershipColumns)
                .extracting(row -> row.get("column_name"))
                .containsExactly("organization_id", "role", "status", "user_id");
        assertThat(membershipColumns)
                .extracting(row -> row.get("data_type"))
                .containsExactly("uuid", "text", "text", "uuid");
        assertThat(membershipColumns)
                .allSatisfy(row -> assertThat(row.get("is_nullable")).isEqualTo("NO"));
        // No database defaults exist in V3: every value is written explicitly.
        assertThat(membershipColumns)
                .allSatisfy(row -> assertThat(row.get("column_default")).isNull());
    }

    @Test
    void schemaDeclaresExpectedKeysAndConstraints() {
        var constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid IN ('organizations'::regclass, "
                        + "'organization_memberships'::regclass) "
                        + "AND contype IN ('p', 'c', 'f') ORDER BY conname",
                String.class);
        assertThat(constraints).containsExactlyInAnyOrder(
                "organizations_pk",
                "organization_memberships_pk",
                "organization_memberships_organization_fk",
                "organization_memberships_user_fk",
                "organization_memberships_role_allowed",
                "organization_memberships_status_allowed");

        var organizationPkColumns = jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid "
                        + "AND a.attnum = ANY (c.conkey) "
                        + "WHERE c.conname = 'organizations_pk'",
                String.class);
        assertThat(organizationPkColumns).containsExactly("id");

        var membershipPkColumns = jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_constraint c "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid "
                        + "AND a.attnum = ANY (c.conkey) "
                        + "WHERE c.conname = 'organization_memberships_pk' "
                        + "ORDER BY a.attname",
                String.class);
        assertThat(membershipPkColumns).containsExactly("organization_id", "user_id");

        var foreignKeyActions = jdbcTemplate.queryForList(
                "SELECT conname, confdeltype, confrelid::regclass::text AS referenced "
                        + "FROM pg_constraint WHERE contype = 'f' "
                        + "AND conrelid = 'organization_memberships'::regclass ORDER BY conname");
        assertThat(foreignKeyActions).hasSize(2);
        var organizationFk = foreignKeyActions.get(0);
        assertThat(organizationFk.get("conname")).isEqualTo("organization_memberships_organization_fk");
        assertThat(organizationFk.get("confdeltype")).isEqualTo("r");
        assertThat(organizationFk.get("referenced")).isEqualTo("organizations");
        var userFk = foreignKeyActions.get(1);
        assertThat(userFk.get("conname")).isEqualTo("organization_memberships_user_fk");
        assertThat(userFk.get("confdeltype")).isEqualTo("r");
        assertThat(userFk.get("referenced")).isEqualTo("users");

        String roleCheck = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'organization_memberships_role_allowed'",
                String.class);
        // Exact canonical PostgreSQL 18.4 definition: OWNER is the only allowed role.
        assertThat(roleCheck).isEqualTo("CHECK ((role = 'OWNER'::text))");

        String statusCheck = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'organization_memberships_status_allowed'",
                String.class);
        // Exact canonical PostgreSQL 18.4 definition: ACTIVE is the only allowed status.
        assertThat(statusCheck).isEqualTo("CHECK ((status = 'ACTIVE'::text))");
    }

    @Test
    void organizationTriggersAreDeclaredWithTheExpectedSemantics() {
        var triggers = jdbcTemplate.queryForList(
                "SELECT tgname, tgrelid::regclass::text AS relation, "
                        + "tgconstraint <> 0 AS is_constraint, tgdeferrable, tginitdeferred, "
                        + "pg_get_triggerdef(oid) AS definition "
                        + "FROM pg_trigger "
                        + "WHERE NOT tgisinternal "
                        + "AND tgrelid IN ('organizations'::regclass, "
                        + "'organization_memberships'::regclass) "
                        + "ORDER BY tgname");

        // V3 triggers plus the V4 Workspace-foundation triggers added on the
        // existing organization_memberships table (V3 itself is unchanged).
        // The V4 triggers are verified in detail by the Workspace tests.
        assertThat(triggers).extracting(row -> row.get("tgname")).containsExactlyInAnyOrder(
                "organizations_require_active_owner",
                "organization_memberships_preserve_active_owner",
                "organization_memberships_preserve_active_owner_on_truncate",
                "organization_memberships_preserve_workspace_foundation",
                "organization_memberships_preserve_workspaces_on_truncate");

        var requireOwner = trigger(triggers, "organizations_require_active_owner");
        assertThat(requireOwner.get("relation")).isEqualTo("organizations");
        assertThat(requireOwner.get("is_constraint")).isEqualTo(true);
        assertThat(requireOwner.get("tgdeferrable")).isEqualTo(true);
        assertThat(requireOwner.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(requireOwner))
                .contains("CONSTRAINT TRIGGER")
                .contains("AFTER INSERT ON public.organizations")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("FOR EACH ROW");

        var preserveOwner = trigger(triggers, "organization_memberships_preserve_active_owner");
        assertThat(preserveOwner.get("relation")).isEqualTo("organization_memberships");
        assertThat(preserveOwner.get("is_constraint")).isEqualTo(true);
        assertThat(preserveOwner.get("tgdeferrable")).isEqualTo(true);
        assertThat(preserveOwner.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(preserveOwner))
                .contains("CONSTRAINT TRIGGER")
                .contains("ON public.organization_memberships")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("FOR EACH ROW")
                .contains("UPDATE")
                .contains("DELETE");
        String preserveOwnerSource = jdbcTemplate.queryForObject(
                "SELECT prosrc FROM pg_proc "
                        + "WHERE proname = 'organization_memberships_preserve_active_owner'",
                String.class);
        assertThat(preserveOwnerSource).contains("FOR UPDATE");

        var truncateGuard =
                trigger(triggers, "organization_memberships_preserve_active_owner_on_truncate");
        assertThat(truncateGuard.get("relation")).isEqualTo("organization_memberships");
        assertThat(truncateGuard.get("is_constraint")).isEqualTo(false);
        assertThat(truncateGuard.get("tgdeferrable")).isEqualTo(false);
        assertThat(truncateGuard.get("tginitdeferred")).isEqualTo(false);
        assertThat(definitionOf(truncateGuard))
                .contains("AFTER TRUNCATE ON public.organization_memberships")
                .contains("FOR EACH STATEMENT")
                .doesNotContain("DEFERRABLE");
    }

    @Test
    void organizationServiceIsWiredToJdbcOrganizationRepository() {
        assertThat(organizationService).isNotNull();
        assertThat(organizationRepository).isInstanceOf(JdbcOrganizationRepository.class);
    }

    @Test
    void creatingOrganizationWithActiveUserSucceedsAtomically() {
        User creator = activeUser();

        Organization created = organizationService.createOrganization(creator.id());

        assertThat(created.id()).isNotNull();
        assertThat(organizationCount(created.id())).isEqualTo(1);
        assertThat(membershipCount(created.id())).isEqualTo(1);

        var membership = jdbcTemplate.queryForMap(
                "SELECT organization_id, user_id, role, status FROM organization_memberships "
                        + "WHERE organization_id = ?",
                created.id());
        assertThat(membership.get("organization_id")).isEqualTo(created.id());
        assertThat(membership.get("user_id")).isEqualTo(creator.id());
        assertThat(membership.get("role")).isEqualTo("OWNER");
        assertThat(membership.get("status")).isEqualTo("ACTIVE");

        assertThat(organizationService.findOrganization(created.id())).isEqualTo(created);
        assertThat(organizationService.findMemberships(created.id())).containsExactly(
                new OrganizationMembership(created.id(), creator.id(),
                        OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE));
    }

    @Test
    void findOrganizationFailsForUnknownIdentity() {
        UUID unknown = UUID.randomUUID();
        assertThatExceptionOfType(OrganizationNotFoundException.class)
                .isThrownBy(() -> organizationService.findOrganization(unknown))
                .satisfies(exception -> assertThat(exception.organizationId()).isEqualTo(unknown));
    }

    @Test
    void unknownCreatorIsRejectedAndLeavesNoRows() {
        UUID unknown = UUID.randomUUID();
        Integer organizationsBefore = totalRows("organizations");
        Integer membershipsBefore = totalRows("organization_memberships");

        assertThatThrownBy(() -> organizationService.createOrganization(unknown))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(totalRows("organizations")).isEqualTo(organizationsBefore);
        assertThat(totalRows("organization_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void suspendedCreatorIsRejectedAndLeavesNoRows() {
        User creator = activeUser();
        userService.changeStatus(creator.id(), UserStatus.SUSPENDED);
        Integer organizationsBefore = totalRows("organizations");
        Integer membershipsBefore = totalRows("organization_memberships");

        assertThatExceptionOfType(OrganizationCreatorNotActiveException.class)
                .isThrownBy(() -> organizationService.createOrganization(creator.id()))
                .satisfies(exception -> {
                    assertThat(exception.creatorUserId()).isEqualTo(creator.id());
                    assertThat(exception.creatorStatus()).isEqualTo(UserStatus.SUSPENDED);
                })
                .withMessageContaining("SUSPENDED");

        assertThat(totalRows("organizations")).isEqualTo(organizationsBefore);
        assertThat(totalRows("organization_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void deactivatedCreatorIsRejectedAndLeavesNoRows() {
        User creator = activeUser();
        userService.changeStatus(creator.id(), UserStatus.DEACTIVATED);
        Integer organizationsBefore = totalRows("organizations");
        Integer membershipsBefore = totalRows("organization_memberships");

        assertThatExceptionOfType(OrganizationCreatorNotActiveException.class)
                .isThrownBy(() -> organizationService.createOrganization(creator.id()))
                .satisfies(exception -> {
                    assertThat(exception.creatorUserId()).isEqualTo(creator.id());
                    assertThat(exception.creatorStatus()).isEqualTo(UserStatus.DEACTIVATED);
                })
                .withMessageContaining("DEACTIVATED");

        assertThat(totalRows("organizations")).isEqualTo(organizationsBefore);
        assertThat(totalRows("organization_memberships")).isEqualTo(membershipsBefore);
    }

    @Test
    void committingOrganizationWithoutActiveOwnerIsRejected() {
        UUID orphan = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO organizations (id) VALUES (?)", orphan)))
                .hasStackTraceContaining("ACTIVE OWNER Organization Membership");

        assertThat(organizationCount(orphan)).isZero();
    }

    @Test
    void membershipWithoutOrganizationIsRejected() {
        User user = activeUser();
        UUID unknownOrganization = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addOwnerThroughRawSql(unknownOrganization, user.id())))
                .hasStackTraceContaining("organization_memberships_organization_fk");
    }

    @Test
    void membershipWithoutUserIsRejected() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        UUID unknownUser = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addOwnerThroughRawSql(organization.id(), unknownUser)))
                .hasStackTraceContaining("organization_memberships_user_fk");
    }

    @Test
    void duplicateMembershipForSameUserAndOrganizationIsRejected() {
        User creator = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                addOwnerThroughRawSql(organization.id(), creator.id())))
                .hasStackTraceContaining("organization_memberships_pk");

        assertThat(membershipCount(organization.id())).isEqualTo(1);
    }

    @Test
    void unknownRoleIsRejected() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        User other = activeUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO organization_memberships "
                                + "(organization_id, user_id, role, status) "
                                + "VALUES (?, ?, 'ADMIN', 'ACTIVE')",
                        organization.id(), other.id())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("organization_memberships_role_allowed")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    @Test
    void unknownStatusIsRejected() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        User other = activeUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "INSERT INTO organization_memberships "
                                + "(organization_id, user_id, role, status) "
                                + "VALUES (?, ?, 'OWNER', 'SUSPENDED')",
                        organization.id(), other.id())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("organization_memberships_status_allowed")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    @Test
    void deletingTheOnlyActiveOwnerIsRejectedAtCommit() {
        User creator = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM organization_memberships "
                                + "WHERE organization_id = ? AND user_id = ?",
                        organization.id(), creator.id())))
                .hasStackTraceContaining("ACTIVE OWNER Organization Membership");

        assertThat(activeOwnerCount(organization.id())).isEqualTo(1);
    }

    @Test
    void deletingOneOfTwoActiveOwnersIsAllowed() {
        User ownerA = activeUser();
        User ownerB = activeUser();
        Organization organization = organizationService.createOrganization(ownerA.id());
        addOwnerThroughRawSql(organization.id(), ownerB.id());
        assertThat(activeOwnerCount(organization.id())).isEqualTo(2);

        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM organization_memberships "
                                + "WHERE organization_id = ? AND user_id = ?",
                        organization.id(), ownerA.id()));

        assertThat(activeOwnerCount(organization.id())).isEqualTo(1);
        assertThat(organizationCount(organization.id())).isEqualTo(1);
    }

    @Test
    void userStatusChangeDoesNotRewriteMembership() {
        User creator = activeUser();
        Organization organization = organizationService.createOrganization(creator.id());

        userService.changeStatus(creator.id(), UserStatus.SUSPENDED);

        var membership = jdbcTemplate.queryForMap(
                "SELECT role, status FROM organization_memberships "
                        + "WHERE organization_id = ? AND user_id = ?",
                organization.id(), creator.id());
        assertThat(membership.get("role")).isEqualTo("OWNER");
        assertThat(membership.get("status")).isEqualTo("ACTIVE");

        userService.changeStatus(creator.id(), UserStatus.DEACTIVATED);

        assertThat(activeOwnerCount(organization.id())).isEqualTo(1);
        assertThat(organizationService.findMemberships(organization.id())).containsExactly(
                new OrganizationMembership(organization.id(), creator.id(),
                        OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE));
    }

    @Test
    void truncatingMembershipsWhileOrganizationsSurviveIsRejected() {
        Organization organization = organizationService.createOrganization(activeUser().id());
        Integer organizationsBefore = totalRows("organizations");
        Integer membershipsBefore = totalRows("organization_memberships");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE organization_memberships")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining(
                        "TRUNCATE of organization_memberships would leave an existing "
                                + "Organization without an ACTIVE OWNER Organization Membership")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE organization_memberships CASCADE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(totalRows("organizations")).isEqualTo(organizationsBefore);
        assertThat(totalRows("organization_memberships")).isEqualTo(membershipsBefore);
        assertThat(activeOwnerCount(organization.id())).isEqualTo(1);
    }

    /**
     * Two-owner write-skew race: two concurrent real transactions each delete a
     * different ACTIVE OWNER Membership of the same Organization. The deferred
     * invariant trigger serializes both commits through a FOR UPDATE lock on
     * the parent Organization row, so exactly one removal may commit and the
     * other must fail with the ACTIVE OWNER invariant.
     */
    @Test
    @Timeout(60)
    void concurrentDeletionOfBothActiveOwnersCannotCommitTwice() throws Exception {
        User ownerA = activeUser();
        User ownerB = activeUser();
        Organization organization = organizationService.createOrganization(ownerA.id());
        addOwnerThroughRawSql(organization.id(), ownerB.id());
        assertThat(activeOwnerCount(organization.id())).isEqualTo(2);

        CountDownLatch bothDeletesExecuted = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Exception> t1 = executor.submit(
                    deleteOwnerTransaction(organization.id(), ownerA.id(), bothDeletesExecuted));
            Future<Exception> t2 = executor.submit(
                    deleteOwnerTransaction(organization.id(), ownerB.id(), bothDeletesExecuted));

            Exception outcome1 = t1.get(30, TimeUnit.SECONDS);
            Exception outcome2 = t2.get(30, TimeUnit.SECONDS);

            // Exactly one transaction may commit; the other must fail with the
            // ACTIVE OWNER invariant.
            List<Exception> failures = new ArrayList<>();
            if (outcome1 != null) {
                failures.add(outcome1);
            }
            if (outcome2 != null) {
                failures.add(outcome2);
            }
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0))
                    .hasStackTraceContaining("ACTIVE OWNER Organization Membership");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(activeOwnerCount(organization.id())).isEqualTo(1);
        assertThat(organizationCount(organization.id())).isEqualTo(1);
    }

    /**
     * Deletes one owner Membership in its own real transaction on a separate
     * database connection. Both deletion statements are synchronized to occur
     * before either transaction is allowed to continue towards commit.
     * Returns null on successful commit, or the commit-time exception.
     */
    private Callable<Exception> deleteOwnerTransaction(
            UUID organizationId, UUID ownerId, CountDownLatch bothDeletesExecuted) {
        return () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            "DELETE FROM organization_memberships "
                                    + "WHERE organization_id = ? AND user_id = ?",
                            organizationId, ownerId);
                    bothDeletesExecuted.countDown();
                    try {
                        if (!bothDeletesExecuted.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Timed out waiting for the concurrent deletion");
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

    private Integer totalRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private User activeUser() {
        return userService.createUser();
    }

    private Integer membershipCount(UUID organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_memberships WHERE organization_id = ?",
                Integer.class, organizationId);
    }

    private Integer activeOwnerCount(UUID organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_memberships WHERE organization_id = ? "
                        + "AND role = 'OWNER' AND status = 'ACTIVE'",
                Integer.class, organizationId);
    }

    private Integer organizationCount(UUID organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organizations WHERE id = ?", Integer.class, organizationId);
    }

    private void addOwnerThroughRawSql(UUID organizationId, UUID userId) {
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
