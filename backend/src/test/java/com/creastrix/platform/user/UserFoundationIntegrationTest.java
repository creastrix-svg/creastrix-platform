package com.creastrix.platform.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.InvalidUserStatusTransitionException;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserNotFoundException;
import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the User foundation against a real PostgreSQL instance.
 *
 * <p>Database invariants are proven with raw SQL so they are shown to hold
 * independently from the Java domain layer.
 */
@SpringBootTest
@Testcontainers
class UserFoundationIntegrationTest {

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
    void v2MigrationIsApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void v2AddsUsersAndUserProfilesTables() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN ('users', 'user_profiles') "
                        + "ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly("user_profiles", "users");
    }

    @Test
    void schemaDeclaresExpectedKeysAndConstraints() {
        var constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid IN ('users'::regclass, 'user_profiles'::regclass) "
                        + "AND contype IN ('p', 'c', 'f') ORDER BY conname",
                String.class);
        assertThat(constraints).containsExactlyInAnyOrder(
                "users_pk", "users_status_allowed", "user_profiles_pk", "user_profiles_user_fk");

        String foreignKeyAction = jdbcTemplate.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'user_profiles_user_fk'",
                String.class);
        assertThat(foreignKeyAction).isEqualTo("r");

        String statusColumnType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'status'",
                String.class);
        assertThat(statusColumnType).isEqualTo("text");

        String statusDefault = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'status'",
                String.class);
        assertThat(statusDefault).contains("ACTIVE");
    }

    @Test
    void createdUserHasIdentityActiveStatusAndExactlyOneProfile() {
        User created = userService.createUser();

        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);

        Integer committedUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'",
                Integer.class, created.id());
        assertThat(committedUsers).isEqualTo(1);

        Integer profiles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profiles WHERE user_id = ?",
                Integer.class, created.id());
        assertThat(profiles).isEqualTo(1);

        UUID profileUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_profiles WHERE user_id = ?",
                UUID.class, created.id());
        assertThat(profileUserId).isEqualTo(created.id());

        assertThat(userService.findUser(created.id())).isEqualTo(created);
    }

    @Test
    void findUserFailsForUnknownIdentity() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> userService.findUser(unknown))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void committingUserWithoutProfileIsRejected() {
        UUID orphan = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", orphan)))
                .hasStackTraceContaining("exactly one User Profile");

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, orphan);
        assertThat(remaining).isZero();
    }

    @Test
    void profileWithoutUserIsRejected() {
        UUID unknownUser = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO user_profiles (user_id) VALUES (?)", unknownUser)))
                .hasStackTraceContaining("user_profiles_user_fk");
    }

    @Test
    void duplicateProfileForSameUserIsRejected() {
        User user = userService.createUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO user_profiles (user_id) VALUES (?)", user.id())))
                .hasStackTraceContaining("user_profiles_pk");
    }

    @Test
    void deletingTheOnlyProfileOfAnExistingUserIsRejectedAtCommit() {
        User user = userService.createUser();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("DELETE FROM user_profiles WHERE user_id = ?", user.id())))
                .hasStackTraceContaining("exactly one User Profile");

        Integer profiles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profiles WHERE user_id = ?", Integer.class, user.id());
        assertThat(profiles).isEqualTo(1);
    }

    @Test
    void directlyCreatingSuspendedUserIsRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO users (id, status) VALUES (?, 'SUSPENDED')", id);
            jdbcTemplate.update("INSERT INTO user_profiles (user_id) VALUES (?)", id);
        })).hasStackTraceContaining("must start ACTIVE");
    }

    @Test
    void directlyCreatingDeactivatedUserIsRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO users (id, status) VALUES (?, 'DEACTIVATED')", id);
            jdbcTemplate.update("INSERT INTO user_profiles (user_id) VALUES (?)", id);
        })).hasStackTraceContaining("must start ACTIVE");
    }

    @Test
    void userAndUserProfileTriggersAreDeclaredWithTheExpectedSemantics() {
        var triggers = jdbcTemplate.queryForList(
                "SELECT tgname, tgrelid::regclass::text AS relation, "
                        + "tgconstraint <> 0 AS is_constraint, tgdeferrable, tginitdeferred, "
                        + "pg_get_triggerdef(oid) AS definition "
                        + "FROM pg_trigger "
                        + "WHERE NOT tgisinternal "
                        + "AND tgrelid IN ('users'::regclass, 'user_profiles'::regclass) "
                        + "ORDER BY tgname");

        assertThat(triggers).extracting(row -> row.get("tgname")).containsExactlyInAnyOrder(
                "users_enforce_initial_status",
                "users_enforce_status_transition",
                "users_require_profile",
                "user_profiles_preserve_mandatory",
                "user_profiles_preserve_mandatory_on_truncate");

        var initialStatus = trigger(triggers, "users_enforce_initial_status");
        assertThat(initialStatus.get("relation")).isEqualTo("users");
        assertThat(initialStatus.get("is_constraint")).isEqualTo(false);
        assertThat(initialStatus.get("tgdeferrable")).isEqualTo(false);
        assertThat(initialStatus.get("tginitdeferred")).isEqualTo(false);
        assertThat(definitionOf(initialStatus))
                .contains("BEFORE INSERT ON public.users")
                .contains("FOR EACH ROW");

        var lifecycle = trigger(triggers, "users_enforce_status_transition");
        assertThat(lifecycle.get("relation")).isEqualTo("users");
        assertThat(lifecycle.get("is_constraint")).isEqualTo(false);
        assertThat(lifecycle.get("tgdeferrable")).isEqualTo(false);
        assertThat(lifecycle.get("tginitdeferred")).isEqualTo(false);
        assertThat(definitionOf(lifecycle))
                .contains("BEFORE UPDATE OF status ON public.users")
                .contains("FOR EACH ROW");
        assertThat(jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_trigger t "
                        + "JOIN pg_attribute a ON a.attrelid = t.tgrelid "
                        + "AND a.attnum = ANY (t.tgattr::smallint[]) "
                        + "WHERE t.tgname = 'users_enforce_status_transition'",
                String.class)).containsExactly("status");

        var requireProfile = trigger(triggers, "users_require_profile");
        assertThat(requireProfile.get("relation")).isEqualTo("users");
        assertThat(requireProfile.get("is_constraint")).isEqualTo(true);
        assertThat(requireProfile.get("tgdeferrable")).isEqualTo(true);
        assertThat(requireProfile.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(requireProfile))
                .contains("CONSTRAINT TRIGGER")
                .contains("AFTER INSERT ON public.users")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("FOR EACH ROW");

        var preserveProfile = trigger(triggers, "user_profiles_preserve_mandatory");
        assertThat(preserveProfile.get("relation")).isEqualTo("user_profiles");
        assertThat(preserveProfile.get("is_constraint")).isEqualTo(true);
        assertThat(preserveProfile.get("tgdeferrable")).isEqualTo(true);
        assertThat(preserveProfile.get("tginitdeferred")).isEqualTo(true);
        assertThat(definitionOf(preserveProfile))
                .contains("CONSTRAINT TRIGGER")
                .contains("ON public.user_profiles")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("FOR EACH ROW")
                .contains("UPDATE")
                .contains("DELETE");

        var truncateGuard = trigger(triggers, "user_profiles_preserve_mandatory_on_truncate");
        assertThat(truncateGuard.get("relation")).isEqualTo("user_profiles");
        assertThat(truncateGuard.get("is_constraint")).isEqualTo(false);
        assertThat(truncateGuard.get("tgdeferrable")).isEqualTo(false);
        assertThat(truncateGuard.get("tginitdeferred")).isEqualTo(false);
        assertThat(definitionOf(truncateGuard))
                .contains("AFTER TRUNCATE ON public.user_profiles")
                .contains("FOR EACH STATEMENT")
                .doesNotContain("DEFERRABLE");
    }

    @Test
    void truncatingUserProfilesWhileUsersSurviveIsRejected() {
        User user = userService.createUser();
        assertThat(storedStatus(user.id())).isEqualTo("ACTIVE");
        assertThat(profileCount(user.id())).isEqualTo(1);

        Integer usersBefore = countRows("users");
        Integer profilesBefore = countRows("user_profiles");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.execute("TRUNCATE TABLE user_profiles")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining(
                        "TRUNCATE of user_profiles would leave an existing User "
                                + "without its mandatory User Profile")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        assertThat(countRows("users")).isEqualTo(usersBefore);
        assertThat(countRows("user_profiles")).isEqualTo(profilesBefore);
        assertThat(profileCount(user.id())).isEqualTo(1);
        assertThat(usersWithoutProfile()).isZero();
    }

    @Test
    void unknownStatusValueIsRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO users (id, status) VALUES (?, 'ARCHIVED')", id)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("ARCHIVED")
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);

        Integer stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id);
        assertThat(stored).isZero();
    }

    @Test
    void activeToSuspendedIsAllowed() {
        User user = userService.createUser();
        User suspended = userService.changeStatus(user.id(), UserStatus.SUSPENDED);

        assertThat(suspended.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(storedStatus(user.id())).isEqualTo("SUSPENDED");
    }

    @Test
    void suspendedToActiveIsAllowed() {
        User user = userService.createUser();
        userService.changeStatus(user.id(), UserStatus.SUSPENDED);
        User reactivated = userService.changeStatus(user.id(), UserStatus.ACTIVE);

        assertThat(reactivated.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(storedStatus(user.id())).isEqualTo("ACTIVE");
    }

    @Test
    void activeToDeactivatedIsAllowed() {
        User user = userService.createUser();
        User deactivated = userService.changeStatus(user.id(), UserStatus.DEACTIVATED);

        assertThat(deactivated.status()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(storedStatus(user.id())).isEqualTo("DEACTIVATED");
    }

    @Test
    void suspendedToDeactivatedIsAllowed() {
        User user = userService.createUser();
        userService.changeStatus(user.id(), UserStatus.SUSPENDED);
        User deactivated = userService.changeStatus(user.id(), UserStatus.DEACTIVATED);

        assertThat(deactivated.status()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(storedStatus(user.id())).isEqualTo("DEACTIVATED");
    }

    @Test
    void deactivatedToActiveIsRejectedByDomain() {
        User user = userService.createUser();
        userService.changeStatus(user.id(), UserStatus.DEACTIVATED);

        assertThatThrownBy(() -> userService.changeStatus(user.id(), UserStatus.ACTIVE))
                .isInstanceOf(InvalidUserStatusTransitionException.class);
        assertThat(storedStatus(user.id())).isEqualTo("DEACTIVATED");
    }

    @Test
    void sameStateCommandIsRejectedByDomain() {
        User user = userService.createUser();

        assertThatThrownBy(() -> userService.changeStatus(user.id(), UserStatus.ACTIVE))
                .isInstanceOf(InvalidUserStatusTransitionException.class);
        assertThat(storedStatus(user.id())).isEqualTo("ACTIVE");
    }

    @Test
    void deactivatedToActiveIsRejectedByDatabaseThroughRawSql() {
        User user = userService.createUser();
        userService.changeStatus(user.id(), UserStatus.DEACTIVATED);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user.id())))
                .hasStackTraceContaining("Unsupported User status transition");

        assertThat(storedStatus(user.id())).isEqualTo("DEACTIVATED");
    }

    @Test
    void statusChangePreservesIdentityAndProfile() {
        User user = userService.createUser();

        User suspended = userService.changeStatus(user.id(), UserStatus.SUSPENDED);

        assertThat(suspended.id()).isEqualTo(user.id());
        Integer profiles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profiles WHERE user_id = ?", Integer.class, user.id());
        assertThat(profiles).isEqualTo(1);
    }

    private String storedStatus(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private Integer profileCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profiles WHERE user_id = ?", Integer.class, userId);
    }

    private Integer countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Integer usersWithoutProfile() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users u WHERE NOT EXISTS "
                        + "(SELECT 1 FROM user_profiles p WHERE p.user_id = u.id)",
                Integer.class);
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
