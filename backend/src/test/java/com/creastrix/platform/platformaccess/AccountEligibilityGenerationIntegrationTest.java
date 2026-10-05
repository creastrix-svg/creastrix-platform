package com.creastrix.platform.platformaccess;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.creastrix.platform.platformaccess.application.AccountEligibilityGeneration;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext;
import com.creastrix.platform.platformaccess.domain.PlatformAccessDecision;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant;
import com.creastrix.platform.platformaccess.domain.PlatformAccessPolicy;
import com.creastrix.platform.platformaccess.domain.PlatformPermission;
import com.creastrix.platform.platformaccess.domain.PlatformRole;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.InvalidUserStatusTransitionException;
import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Authoritative counter proof on a new, owned PostgreSQL database. Raw fixtures
 * are structural tests, not staff authorization or live MFA/activation evidence.
 * Grant retention constrains formerly granted recipients only: these tests do
 * not invent a universal User tombstone or privileged-database protection.
 */
@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Timeout(120)
class AccountEligibilityGenerationIntegrationTest {

    private static final String ASSIGNMENT_GUARD = "users_reject_generation_assignment";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.data-source-properties.connectTimeout", () -> "5");
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "40");
        registry.add("spring.datasource.hikari.data-source-properties.options",
                () -> "-c lock_timeout=10000 -c statement_timeout=30000");
    }

    @Autowired
    private UserService users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void realPostgresAndEnabledAdditiveGenerationTriggersAreUsed() throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("18.4");
        }
        assertThat(jdbc.queryForList("SELECT tgname FROM pg_trigger WHERE tgrelid='users'::regclass "
                        + "AND NOT tgisinternal AND tgenabled='O' ORDER BY tgname", String.class))
                .contains("users_enforce_initial_generation", "users_enforce_initial_status",
                        "users_enforce_status_transition", "users_increment_eligibility_generation",
                        ASSIGNMENT_GUARD, "users_require_profile");
        List<String> statusTriggers = jdbc.queryForList("SELECT tgname FROM pg_trigger "
                + "WHERE tgrelid='users'::regclass AND tgname IN "
                + "('users_enforce_status_transition','users_increment_eligibility_generation') "
                + "ORDER BY tgname", String.class);
        assertThat(statusTriggers).containsExactly("users_enforce_status_transition",
                "users_increment_eligibility_generation");
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,SUSPENDED", "SUSPENDED,ACTIVE", "ACTIVE,DEACTIVATED", "SUSPENDED,DEACTIVATED"})
    void eachSupportedServiceTransitionAdvancesOnceWithoutMutatingGrant(UserStatus from, UserStatus to) {
        UUID user = users.createUser().id();
        UUID grant = ownerGrant(user);
        moveTo(user, from);
        long before = generation(user);
        String grantBefore = grantJson(grant);

        assertThat(users.changeStatus(user, to).status()).isEqualTo(to);

        assertUser(user, to, before + 1);
        assertThat(grantJson(grant)).isEqualTo(grantBefore);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,SUSPENDED", "SUSPENDED,ACTIVE", "ACTIVE,DEACTIVATED", "SUSPENDED,DEACTIVATED"})
    void eachSupportedRawTransitionAdvancesOnceWithoutMutatingGrant(UserStatus from, UserStatus to) {
        UUID user = users.createUser().id();
        UUID grant = ownerGrant(user);
        moveTo(user, from);
        long before = generation(user);
        String grantBefore = grantJson(grant);

        assertThat(jdbc.update("UPDATE users SET status=? WHERE id=?", to.name(), user)).isEqualTo(1);

        assertUser(user, to, before + 1);
        assertThat(grantJson(grant)).isEqualTo(grantBefore);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,ACTIVE", "SUSPENDED,SUSPENDED", "DEACTIVATED,DEACTIVATED",
            "DEACTIVATED,ACTIVE", "DEACTIVATED,SUSPENDED"})
    void invalidOrSameStateServiceTransitionKeepsCounterAndStatus(UserStatus from, UserStatus to) {
        UUID user = users.createUser().id();
        moveTo(user, from);
        long before = generation(user);

        assertThatThrownBy(() -> users.changeStatus(user, to))
                .isInstanceOf(InvalidUserStatusTransitionException.class);

        assertUser(user, from, before);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,ACTIVE", "SUSPENDED,SUSPENDED", "DEACTIVATED,DEACTIVATED",
            "DEACTIVATED,ACTIVE", "DEACTIVATED,SUSPENDED", "ACTIVE,UNKNOWN"})
    void invalidOrSameStateRawTransitionKeepsCounterAndStatus(UserStatus from, String to) {
        UUID user = users.createUser().id();
        moveTo(user, from);
        long before = generation(user);

        PSQLException error = postgres(catchThrowable(() ->
                jdbc.update("UPDATE users SET status=? WHERE id=?", to, user)));

        assertThat(error.getSQLState()).isEqualTo("23514");
        assertThat(error.getServerErrorMessage().getMessage()).startsWith("Unsupported User status transition");
        assertUser(user, from, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "account_eligibility_generation", "account_eligibility_generation + 1",
            "0", "-1", "9223372036854775807"})
    void everyExplicitAssignmentIncludingSameValueAndResetIsRejected(String expression) {
        UUID user = users.createUser().id();
        users.changeStatus(user, UserStatus.SUSPENDED);

        PSQLException error = postgres(catchThrowable(() -> jdbc.update(
                "UPDATE users SET account_eligibility_generation=" + expression + " WHERE id=?", user)));

        assertThat(error.getSQLState()).isEqualTo("23514");
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("users_eligibility_generation_assignment");
        assertUser(user, UserStatus.SUSPENDED, 2);
    }

    @Test
    void combinedStatusAndGenerationAssignmentDoesNotBypassTheExplicitWriteGuard() {
        UUID user = users.createUser().id();
        PSQLException error = postgres(catchThrowable(() -> jdbc.update("UPDATE users "
                + "SET status='SUSPENDED', account_eligibility_generation=2 WHERE id=?", user)));

        assertThat(error.getSQLState()).isEqualTo("23514");
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("users_eligibility_generation_assignment");
        assertUser(user, UserStatus.ACTIVE, 1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 2, Long.MAX_VALUE})
    void explicitNonInitialGenerationInsertIsRejectedAtomically(long generation) {
        UUID user = UUID.randomUUID();
        PSQLException error = postgres(catchThrowable(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,account_eligibility_generation) VALUES (?,?)", user, generation);
            jdbc.update("INSERT INTO user_profiles(user_id) VALUES (?)", user);
        })));

        assertThat(error.getSQLState()).isEqualTo("23514");
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("users_initial_eligibility_generation");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id=?", Integer.class, user))
                .isZero();
    }

    @Test
    void explicitOneAndDefaultOneBothEstablishOnlyInitialGeneration() {
        UUID explicit = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,account_eligibility_generation) VALUES (?,1)", explicit);
            jdbc.update("INSERT INTO user_profiles(user_id) VALUES (?)", explicit);
        });

        assertUser(explicit, UserStatus.ACTIVE, 1);
        assertUser(users.createUser().id(), UserStatus.ACTIVE, 1);
    }

    @Test
    void copyInsertUsesTheSameEnabledInitialGenerationGuard() throws Exception {
        UUID valid = UUID.randomUUID();
        try (Connection connection = raw()) {
            connection.setAutoCommit(false);
            assertThat(connection.unwrap(PGConnection.class).getCopyAPI().copyIn(
                    "COPY users(id,account_eligibility_generation) FROM STDIN",
                    new StringReader(valid + "\t1\n"))).isEqualTo(1);
            try (var statement = connection.prepareStatement("INSERT INTO user_profiles(user_id) VALUES (?)")) {
                statement.setObject(1, valid);
                statement.executeUpdate();
            }
            connection.commit();
        }
        assertUser(valid, UserStatus.ACTIVE, 1);

        UUID invalid = UUID.randomUUID();
        try (Connection connection = raw()) {
            connection.setAutoCommit(false);
            PSQLException error = postgres(catchThrowable(() -> connection.unwrap(PGConnection.class).getCopyAPI()
                    .copyIn("COPY users(id,account_eligibility_generation) FROM STDIN",
                            new StringReader(invalid + "\t2\n"))));
            assertThat(error.getSQLState()).isEqualTo("23514");
            assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("users_initial_eligibility_generation");
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, invalid)).isZero();
    }

    @Test
    void rolledBackServiceTransitionDoesNotPublishCommittedGenerationOrMutateGrant() {
        UUID user = users.createUser().id();
        UUID grant = ownerGrant(user);
        String grantBefore = grantJson(grant);
        transactions.executeWithoutResult(status -> {
            users.changeStatus(user, UserStatus.SUSPENDED);
            assertUser(user, UserStatus.SUSPENDED, 2);
            status.setRollbackOnly();
        });

        assertUser(user, UserStatus.ACTIVE, 1);
        assertThat(grantJson(grant)).isEqualTo(grantBefore);
    }

    @Test
    void rolledBackDirectTransitionKeepsBothCommittedFields() {
        UUID user = users.createUser().id();
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", user);
            assertUser(user, UserStatus.SUSPENDED, 2);
            status.setRollbackOnly();
        });
        assertUser(user, UserStatus.ACTIVE, 1);
    }

    @Test
    void checkedOverflowRejectsCompleteTransitionWithAllGuardsReenabled() {
        UUID user = users.createUser().id();
        UUID grant = ownerGrant(user);
        String grantBefore = grantJson(grant);
        // This privileged, owned-database fixture is not an application setter.
        // Disable only the new assignment guard and restore it within one DDL transaction.
        transactions.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE users DISABLE TRIGGER " + ASSIGNMENT_GUARD);
            jdbc.update("UPDATE users SET account_eligibility_generation=? WHERE id=?", Long.MAX_VALUE - 1, user);
            jdbc.execute("ALTER TABLE users ENABLE TRIGGER " + ASSIGNMENT_GUARD);
        });
        assertThat(jdbc.queryForObject("SELECT tgenabled FROM pg_trigger WHERE tgrelid='users'::regclass "
                + "AND tgname=?", String.class, ASSIGNMENT_GUARD)).isEqualTo("O");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='users'::regclass "
                + "AND NOT tgisinternal AND tgenabled<>'O'", Integer.class)).isZero();

        users.changeStatus(user, UserStatus.SUSPENDED);
        assertUser(user, UserStatus.SUSPENDED, Long.MAX_VALUE);
        PSQLException error = postgres(catchThrowable(() -> users.changeStatus(user, UserStatus.ACTIVE)));

        assertThat(error.getSQLState()).isEqualTo("23514");
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("users_eligibility_generation_overflow");
        assertUser(user, UserStatus.SUSPENDED, Long.MAX_VALUE);
        assertThat(grantJson(grant)).isEqualTo(grantBefore);
        assertThat(AccountEligibilityGeneration.decode(AccountEligibilityGeneration.encode(generation(user))))
                .isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ,
            Connection.TRANSACTION_SERIALIZABLE})
    void noNewReadCommittedOnlyRestrictionIsImposedOnExistingUserWrites(int isolation) throws Exception {
        UUID user = users.createUser().id();
        try (Connection connection = raw()) {
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("UPDATE users SET status='SUSPENDED' WHERE id=?")) {
                statement.setObject(1, user);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            connection.commit();
        }
        assertUser(user, UserStatus.SUSPENDED, 2);
    }

    @ParameterizedTest
    @EnumSource(PlatformAccessGrant.State.class)
    void grantedRecipientsCannotBeDeletedRecreatedOrCascadeTruncatedIncludingRevoked(
            PlatformAccessGrant.State state) {
        UUID user = users.createUser().id();
        UUID issuer = users.createUser().id();
        UUID grant = ownerGrant(user, issuer);
        if (state != PlatformAccessGrant.State.ACTIVE) {
            transitionGrant(grant, state, issuer);
        }
        String retained = grantJson(grant);
        Map<String, Object> userBefore = userRow(user);

        PSQLException deletion = postgres(catchThrowable(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM user_profiles WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        })));
        // PostgreSQL 18 distinguishes referenced-row RESTRICT from an invalid FK insert.
        assertThat(deletion.getSQLState()).isEqualTo("23001");
        assertThat(deletion.getServerErrorMessage().getConstraint()).isEqualTo("platform_access_grants_recipient_fk");
        assertThat(deletion.getServerErrorMessage().getTable()).isEqualTo("platform_access_grants");
        assertThat(postgres(catchThrowable(() -> jdbc.update("INSERT INTO users(id) VALUES (?)", user)))
                .getSQLState()).isEqualTo("23505");
        assertThat(postgres(catchThrowable(() -> jdbc.execute("TRUNCATE users CASCADE"))).getSQLState())
                .isEqualTo("23514");

        assertThat(userRow(user)).isEqualTo(userBefore);
        assertThat(grantJson(grant)).isEqualTo(retained);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    void unreferencedSyntheticUserCanBeRecreatedWithoutInventingAUniversalTombstone() {
        UUID user = users.createUser().id();
        users.changeStatus(user, UserStatus.SUSPENDED);
        assertUser(user, UserStatus.SUSPENDED, 2);
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM user_profiles WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        });
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id) VALUES (?)", user);
            jdbc.update("INSERT INTO user_profiles(user_id) VALUES (?)", user);
        });

        assertUser(user, UserStatus.ACTIVE, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_access_grants WHERE recipient_user_id=?",
                Integer.class, user)).isZero();
    }

    @Test
    void suspensionAndReactivationInvalidateOldElevationWithoutChangingGrantOrOtherStampFields() {
        UUID user = users.createUser().id();
        UUID target = users.createUser().id();
        UUID grant = ownerGrant(user);
        UUID session = UUID.randomUUID();
        long originalGeneration = generation(user);
        String grantBefore = grantJson(grant);
        assertThat(decision(user, target, grant, session, user, originalGeneration).code())
                .isEqualTo(PlatformAccessDecision.Code.ALLOW);

        users.changeStatus(user, UserStatus.SUSPENDED);
        assertThat(decision(user, target, grant, session, user, originalGeneration).code())
                .isEqualTo(PlatformAccessDecision.Code.INACTIVE_ACTOR);
        users.changeStatus(user, UserStatus.ACTIVE);

        assertUser(user, UserStatus.ACTIVE, 3);
        assertThat(grantJson(grant)).isEqualTo(grantBefore);
        assertThat(decision(user, target, grant, session, user, originalGeneration).code())
                .isEqualTo(PlatformAccessDecision.Code.STAMP_MISMATCH);
        assertThat(decision(user, target, grant, session, user, generation(user)).code())
                .isEqualTo(PlatformAccessDecision.Code.ALLOW);
    }

    @Test
    void anotherUsersEqualNumericGenerationCannotSatisfyTheUnchangedPolicyStamp() {
        UUID first = users.createUser().id();
        UUID second = users.createUser().id();
        UUID target = users.createUser().id();
        UUID grant = ownerGrant(first);
        UUID session = UUID.randomUUID();
        assertThat(generation(first)).isEqualTo(generation(second));

        assertThat(decision(first, target, grant, session, second, generation(second)).code())
                .isEqualTo(PlatformAccessDecision.Code.STAMP_MISMATCH);
        assertThat(decision(first, target, grant, session, first, generation(first)).code())
                .isEqualTo(PlatformAccessDecision.Code.ALLOW);
    }

    private PlatformAccessDecision decision(UUID user, UUID target, UUID grantId, UUID session,
                                            UUID elevatedUser, long elevatedGeneration) {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        PlatformAccessGrant grant = new PlatformAccessGrant(grantId, user, PlatformRole.PLATFORM_OWNER,
                1, 1, 1, PlatformAccessGrant.State.ACTIVE, now.minusSeconds(60),
                new PlatformAccessGrant.Validity(PlatformAccessGrant.ValidityKind.UNBOUNDED, null),
                new PlatformAccessGrant.Scope(PlatformAccessGrant.ScopeKind.PLATFORM_SECURITY_METADATA, List.of()));
        var stamp = new PlatformAccessContext.Stamp(elevatedUser,
                AccountEligibilityGeneration.encode(elevatedGeneration), session, grantId, 1);
        var assurance = new PlatformAccessContext.Assurance(PlatformAccessContext.Verification.VERIFIED_MFA,
                stamp, now.minusSeconds(20), now.minusSeconds(15), now.minusSeconds(10), null);
        var context = new PlatformAccessContext(now,
                new PlatformAccessContext.Actor(user, UserStatus.valueOf((String) userRow(user).get("status")),
                        AccountEligibilityGeneration.encode(generation(user)), session), grant,
                new PlatformAccessContext.Slot(user, List.of(grantId)), assurance,
                new PlatformAccessContext.Request(PlatformPermission.USER_SECURITY_READ,
                        PlatformAccessContext.Action.READ, PlatformAccessContext.ReadReason.SECURITY_REVIEW),
                new PlatformAccessContext.UserResource(target, UserStatus.ACTIVE, UUID.randomUUID()),
                null, Set.of(user, target));
        return new PlatformAccessPolicy().evaluate(context);
    }

    private void moveTo(UUID user, UserStatus state) {
        if (state != UserStatus.ACTIVE) {
            users.changeStatus(user, state);
        }
    }

    private UUID ownerGrant(UUID user) {
        return ownerGrant(user, user);
    }

    private UUID ownerGrant(UUID user, UUID issuer) {
        UUID grant = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_access_grants(id,recipient_user_id,role,catalog_version,bundle_version,"
                + "starts_at,validity_kind,scope_kind,scope_target_count,issued_by_user_id,issued_at) "
                + "VALUES (?,?,'PLATFORM_OWNER',1,1,TIMESTAMPTZ '2026-10-05 10:00:00+00',"
                + "'UNBOUNDED','PLATFORM_SECURITY_METADATA',0,?,TIMESTAMPTZ '2026-10-05 10:00:00+00')",
                grant, user, issuer);
        return grant;
    }

    private void transitionGrant(UUID grant, PlatformAccessGrant.State state, UUID actor) {
        if (state == PlatformAccessGrant.State.REVOKED) {
            jdbc.update("UPDATE platform_access_grants SET state='REVOKED',revision=revision+1,"
                    + "last_changed_by_user_id=?,last_changed_at=TIMESTAMPTZ '2026-10-05 11:00:00+00',"
                    + "revoked_by_user_id=?,revoked_at=TIMESTAMPTZ '2026-10-05 11:00:00+00' WHERE id=?",
                    actor, actor, grant);
        } else {
            jdbc.update("UPDATE platform_access_grants SET state=?,revision=revision+1,"
                    + "last_changed_by_user_id=?,last_changed_at=TIMESTAMPTZ '2026-10-05 11:00:00+00' WHERE id=?",
                    state.name(), actor, grant);
        }
    }

    private long generation(UUID user) {
        return jdbc.queryForObject("SELECT account_eligibility_generation FROM users WHERE id=?", Long.class, user);
    }

    private Map<String, Object> userRow(UUID user) {
        return jdbc.queryForMap("SELECT status,account_eligibility_generation FROM users WHERE id=?", user);
    }

    private String grantJson(UUID grant) {
        return jdbc.queryForObject("SELECT row_to_json(g)::text FROM platform_access_grants g WHERE id=?",
                String.class, grant);
    }

    private void assertUser(UUID user, UserStatus state, long generation) {
        assertThat(userRow(user)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", state.name(), "account_eligibility_generation", generation));
    }

    private Connection raw() throws Exception {
        var properties = new java.util.Properties();
        properties.setProperty("user", POSTGRES.getUsername());
        properties.setProperty("password", POSTGRES.getPassword());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "40");
        properties.setProperty("options", "-c lock_timeout=10000 -c statement_timeout=30000");
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), properties);
    }

    private PSQLException postgres(Throwable failure) {
        assertThat(failure).as("a real PostgreSQL failure is required").isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres) {
                return postgres;
            }
        }
        throw new AssertionError("Expected a PostgreSQL failure", failure);
    }
}
