package com.creastrix.platform.readymadeproduct;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.creastrix.platform.readymadeproduct.application.ReadyMadeProductService;
import com.creastrix.platform.readymadeproduct.application.port.ReadyMadeProductRepository;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaCommandState;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaRejectionReason;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaResult;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaAccessException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaNotRegisteredException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaPayloadMismatchException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.Workspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * PostgreSQL integration coverage for durable manual quantity-delta commands.
 *
 * <p>Supported behavior is exercised through the application service or the
 * same JDBC port inside explicitly held test transactions. Raw SQL tests prove
 * only V8 structural rules; they do not claim actor authorization.
 */
@SpringBootTest
@Testcontainers
class ReadyMadeProductManualQuantityDeltaIntegrationTest {

    private static final String TABLE =
            "ready_made_product_manual_quantity_delta_commands";
    private static final String CHECK_VIOLATION_SQL_STATE = "23514";
    private static final String SIGN_REASON_CHECK =
            "rmp_manual_quantity_delta_commands_reason_matches_delta";

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
    private ReadyMadeProductService products;

    @Autowired
    private ReadyMadeProductRepository productRepository;

    @Autowired
    private UserService users;

    @Autowired
    private WorkspaceService workspaces;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------
    // Environment, public operations, and exact V8 structure
    // ------------------------------------------------------------------

    @Test
    void realPostgresAndExactFlywayHistoryReachV11() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("18.4");
        }
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "AND version IS NOT NULL ORDER BY installed_rank",
                String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
    }

    @Test
    void publicApiExposesTwoSeparateManualDeltaOperations() {
        List<Method> methods = Arrays.stream(ReadyMadeProductService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().contains("ManualQuantityDelta"))
                .toList();

        assertThat(methods).extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "registerManualQuantityDelta",
                        "applyManualQuantityDelta");
        assertThat(methods).extracting(Method::getParameterTypes)
                .containsOnly(new Class<?>[] {
                        UUID.class, UUID.class, long.class, UUID.class
                });
    }

    @Test
    void v8ColumnsAreExactAndHaveNoDefaults() {
        long relationOid = commandRelationOid();
        List<ColumnMetadata> columns = jdbcTemplate.query(
                "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, "
                        + "pg_get_expr(d.adbin, d.adrelid) AS default_expression "
                        + "FROM pg_attribute a "
                        + "LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid "
                        + "AND d.adnum = a.attnum "
                        + "WHERE a.attrelid = CAST(? AS oid) AND a.attnum > 0 "
                        + "AND NOT a.attisdropped ORDER BY a.attnum",
                (rs, rowNum) -> new ColumnMetadata(
                        rs.getString("attname"),
                        rs.getString("format_type"),
                        rs.getBoolean("attnotnull"),
                        rs.getString("default_expression")),
                relationOid);

        assertThat(columns).containsExactly(
                new ColumnMetadata("product_id", "uuid", true, null),
                new ColumnMetadata("command_id", "uuid", true, null),
                new ColumnMetadata("delta", "bigint", true, null),
                new ColumnMetadata("state", "text", true, null),
                new ColumnMetadata("resulting_available_quantity", "bigint", false, null),
                new ColumnMetadata("rejection_reason", "text", false, null),
                new ColumnMetadata("observed_available_quantity", "bigint", false, null));
    }

    @Test
    void v8CommentStatesTheSecurityAndDeadlockBoundariesHonestly() {
        String comment = jdbcTemplate.queryForObject(
                "SELECT obj_description(CAST(? AS oid), 'pg_class')",
                String.class, commandRelationOid());

        assertThat(comment)
                .contains("supported Java/JDBC path")
                .contains("raw SQL is not actor-authorized")
                .contains("Authentication is not implemented")
                .contains("runtime and migration-owner roles are not separated")
                .contains("global deadlock freedom is not claimed")
                .contains("no general SQLSTATE 40P01 retry policy exists");
    }

    @Test
    void v8HasExactCompositePrimaryKeyRestrictForeignKeyAndOnlyItsPrimaryIndex() {
        long relationOid = commandRelationOid();
        List<Map<String, Object>> keyConstraints = jdbcTemplate.queryForList(
                "SELECT conname, contype::text AS type, confdeltype::text AS delete_action, "
                        + "pg_get_constraintdef(oid) AS definition "
                        + "FROM pg_constraint WHERE conrelid = CAST(? AS oid) "
                        + "AND contype IN ('p', 'f') ORDER BY contype DESC",
                relationOid);

        assertThat(keyConstraints).hasSize(2);
        assertThat(constraint(keyConstraints, "rmp_manual_quantity_delta_commands_pk"))
                .containsEntry("type", "p")
                .containsEntry("definition", "PRIMARY KEY (product_id, command_id)");
        assertThat(constraint(keyConstraints, "rmp_manual_quantity_delta_commands_product_fk"))
                .containsEntry("type", "f")
                .containsEntry("delete_action", "r");
        assertThat(String.valueOf(constraint(
                keyConstraints,
                "rmp_manual_quantity_delta_commands_product_fk").get("definition")))
                .isEqualTo("FOREIGN KEY (product_id) REFERENCES ready_made_products(id) ON DELETE RESTRICT");

        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT c.relname AS index_name, i.indisprimary, i.indisunique, "
                        + "pg_get_indexdef(i.indexrelid) AS definition "
                        + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE i.indrelid = CAST(? AS oid)",
                relationOid);
        assertThat(indexes).singleElement().satisfies(index -> {
            assertThat(index.get("index_name"))
                    .isEqualTo("rmp_manual_quantity_delta_commands_pk");
            assertThat(index.get("indisprimary")).isEqualTo(true);
            assertThat(index.get("indisunique")).isEqualTo(true);
            assertThat(String.valueOf(index.get("definition")))
                    .endsWith("USING btree (product_id, command_id)");
        });
        assertThat(indexes).noneMatch(index ->
                String.valueOf(index.get("definition")).contains("(command_id)"));
    }

    @Test
    void v8CheckSetIsClosedAndOutcomeShapesAreEnforced() {
        long relationOid = commandRelationOid();
        Map<String, String> checks = jdbcTemplate.query(
                "SELECT conname, pg_get_constraintdef(oid) AS definition "
                        + "FROM pg_constraint WHERE conrelid = CAST(? AS oid) "
                        + "AND contype = 'c' ORDER BY conname",
                rs -> {
                    var result = new java.util.LinkedHashMap<String, String>();
                    while (rs.next()) {
                        result.put(rs.getString("conname"), rs.getString("definition"));
                    }
                    return result;
                },
                relationOid);

        assertThat(checks.keySet()).containsExactlyInAnyOrder(
                "rmp_manual_quantity_delta_commands_delta_non_zero",
                "rmp_manual_quantity_delta_commands_state_set",
                "rmp_manual_quantity_delta_commands_reason_set",
                SIGN_REASON_CHECK,
                "rmp_manual_quantity_delta_commands_outcome_shape");
        assertThat(checks.get("rmp_manual_quantity_delta_commands_delta_non_zero"))
                .isEqualTo("CHECK ((delta <> 0))");
        assertThat(checks.get(SIGN_REASON_CHECK))
                .isEqualTo("CHECK (((rejection_reason IS NULL) "
                        + "OR ((delta < 0) AND (rejection_reason = 'UNDERFLOW'::text)) "
                        + "OR ((delta > 0) AND (rejection_reason = 'OVERFLOW'::text))))");
        assertThat(checks.get("rmp_manual_quantity_delta_commands_state_set"))
                .isEqualTo("CHECK ((state = ANY (ARRAY['REGISTERED'::text, 'APPLIED'::text, 'REJECTED'::text])))");
        assertThat(checks.get("rmp_manual_quantity_delta_commands_reason_set"))
                .contains("UNDERFLOW".formatted())
                .contains("OVERFLOW")
                .doesNotContain("EXPIRED", "FAILED");
        assertThat(checks.get("rmp_manual_quantity_delta_commands_outcome_shape"))
                .contains("state = 'REGISTERED'::text")
                .contains("state = 'APPLIED'::text")
                .contains("state = 'REJECTED'::text")
                .contains("resulting_available_quantity >= 0")
                .contains("observed_available_quantity >= 0");

        ProductFixture fixture = productOwnedByAdmin(3L);
        assertCheckViolation(() -> insertRawCommand(
                fixture.product().id(), UUID.randomUUID(), 0L,
                "REGISTERED", null, null, null));
        assertCheckViolation(() -> insertRawCommand(
                fixture.product().id(), UUID.randomUUID(), 1L,
                "PENDING", null, null, null));
        assertCheckViolation(() -> insertRawCommand(
                fixture.product().id(), UUID.randomUUID(), 1L,
                "APPLIED", 4L, null, null));

        UUID appliedWithoutOutcome = UUID.randomUUID();
        insertRawRegistered(fixture.product().id(), appliedWithoutOutcome, 1L);
        assertCheckViolation(() -> jdbcTemplate.update(
                "UPDATE " + TABLE + " SET state = 'APPLIED' "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), appliedWithoutOutcome));

        UUID rejectedWithoutReason = UUID.randomUUID();
        insertRawRegistered(fixture.product().id(), rejectedWithoutReason, -1L);
        assertCheckViolation(() -> jdbcTemplate.update(
                "UPDATE " + TABLE + " SET state = 'REJECTED', "
                        + "observed_available_quantity = 3 "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), rejectedWithoutReason));
    }

    @Test
    void v8TriggersAreOidBoundAndUnaffectedBySameNamedDecoyObjects() {
        long publicRelationOid = commandRelationOid();
        List<TriggerMetadata> before = commandTriggers(publicRelationOid);
        assertExpectedTriggerMetadata(before);

        String schema = "rmp_delta_decoy_" + UUID.randomUUID().toString().replace("-", "");
        try {
            jdbcTemplate.execute("CREATE SCHEMA " + schema);
            jdbcTemplate.execute("CREATE TABLE " + schema + "." + TABLE
                    + " (product_id UUID, command_id UUID, delta BIGINT, state TEXT)");
            jdbcTemplate.execute("CREATE FUNCTION " + schema
                    + ".validate_rmp_manual_quantity_delta_command_insert() "
                    + "RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$");
            jdbcTemplate.execute("CREATE FUNCTION " + schema
                    + ".validate_rmp_manual_quantity_delta_command_update() "
                    + "RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$");
            jdbcTemplate.execute("CREATE FUNCTION " + schema
                    + ".prevent_rmp_manual_quantity_delta_command_removal() "
                    + "RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN OLD; END; $$");
            jdbcTemplate.execute("CREATE TRIGGER rmp_manual_quantity_delta_validate_insert "
                    + "BEFORE INSERT ON " + schema + "." + TABLE + " FOR EACH ROW "
                    + "EXECUTE FUNCTION " + schema
                    + ".validate_rmp_manual_quantity_delta_command_insert()");
            jdbcTemplate.execute("CREATE TRIGGER rmp_manual_quantity_delta_validate_update "
                    + "BEFORE UPDATE ON " + schema + "." + TABLE + " FOR EACH ROW "
                    + "EXECUTE FUNCTION " + schema
                    + ".validate_rmp_manual_quantity_delta_command_update()");
            jdbcTemplate.execute("CREATE TRIGGER rmp_manual_quantity_delta_prevent_delete "
                    + "BEFORE DELETE ON " + schema + "." + TABLE + " FOR EACH ROW "
                    + "EXECUTE FUNCTION " + schema
                    + ".prevent_rmp_manual_quantity_delta_command_removal()");
            jdbcTemplate.execute("CREATE TRIGGER rmp_manual_quantity_delta_prevent_truncate "
                    + "BEFORE TRUNCATE ON " + schema + "." + TABLE + " FOR EACH STATEMENT "
                    + "EXECUTE FUNCTION " + schema
                    + ".prevent_rmp_manual_quantity_delta_command_removal()");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_class c JOIN pg_namespace n "
                            + "ON n.oid = c.relnamespace WHERE c.relname = ? "
                            + "AND c.relkind = 'r'",
                    Integer.class, TABLE)).isEqualTo(2);
            assertThat(commandRelationOid()).isEqualTo(publicRelationOid);
            assertThat(commandTriggers(publicRelationOid)).containsExactlyElementsOf(before);
            assertExpectedTriggerMetadata(commandTriggers(publicRelationOid));
            assertThat(commandTriggers(publicRelationOid))
                    .allMatch(trigger -> trigger.functionSchema().equals("public"));
            jdbcTemplate.execute("ALTER TABLE " + schema + "." + TABLE
                    + " ADD CONSTRAINT " + SIGN_REASON_CHECK + " CHECK (delta = 0)");
            v8CheckSetIsClosedAndOutcomeShapesAreEnforced();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_namespace WHERE nspname = ?",
                Integer.class, schema)).isZero();
    }

    @Test
    void rawSqlCannotChangeBindingRepeatOrRewriteTerminalStateOrRemoveRows() {
        ProductFixture fixture = productOwnedByAdmin(8L);
        UUID commandId = UUID.randomUUID();
        insertRawRegistered(fixture.product().id(), commandId, 2L);

        UUID registeredCommandId = UUID.randomUUID();
        insertRawRegistered(fixture.product().id(), registeredCommandId, 1L);

        UUID rejectedCommandId = UUID.randomUUID();
        insertRawRegistered(fixture.product().id(), rejectedCommandId, -9L);
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET state = 'REJECTED', "
                        + "rejection_reason = 'UNDERFLOW', observed_available_quantity = 8 "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), rejectedCommandId);

        assertCheckViolation(() -> jdbcTemplate.update(
                "UPDATE " + TABLE + " SET delta = 3 "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), commandId));
        assertCheckViolation(() -> jdbcTemplate.update(
                "UPDATE " + TABLE + " SET state = 'REGISTERED' "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), commandId));

        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET state = 'APPLIED', "
                        + "resulting_available_quantity = 10 "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), commandId);
        assertCheckViolation(() -> jdbcTemplate.update(
                "UPDATE " + TABLE + " SET resulting_available_quantity = 11 "
                        + "WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), commandId));
        assertCheckViolation(() -> jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), commandId));
        assertCheckViolation(() -> jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), registeredCommandId));
        assertCheckViolation(() -> jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE product_id = ? AND command_id = ?",
                fixture.product().id(), rejectedCommandId));
        assertCheckViolation(() -> jdbcTemplate.execute("TRUNCATE TABLE " + TABLE));

        assertThat(storedCommand(fixture.product().id(), commandId).state())
                .isEqualTo(ManualQuantityDeltaCommandState.APPLIED);
        assertThat(storedCommand(fixture.product().id(), registeredCommandId).state())
                .isEqualTo(ManualQuantityDeltaCommandState.REGISTERED);
        assertThat(storedCommand(fixture.product().id(), rejectedCommandId).state())
                .isEqualTo(ManualQuantityDeltaCommandState.REJECTED);
    }

    // ------------------------------------------------------------------
    // Registration, application, replay, rollback, and disclosure
    // ------------------------------------------------------------------

    @Test
    void registrationCommitsRegisteredRecordWithoutChangingQuantity() {
        ProductFixture fixture = productOwnedByAdmin(9L);
        UUID commandId = UUID.randomUUID();

        ManualQuantityDeltaResult result = products.registerManualQuantityDelta(
                fixture.product().id(), commandId, -4L, fixture.actor().id());

        assertThat(result).isEqualTo(ManualQuantityDeltaResult.registered(
                fixture.product().id(), commandId, -4L));
        assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(result);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(9L);
    }

    @Test
    void identicalRegistrationReturnsRegisteredAppliedAndRejectedHistory() {
        ProductFixture fixture = productOwnedByAdmin(2L);

        UUID registeredId = UUID.randomUUID();
        ManualQuantityDeltaResult registered = products.registerManualQuantityDelta(
                fixture.product().id(), registeredId, 1L, fixture.actor().id());
        assertThat(products.registerManualQuantityDelta(
                fixture.product().id(), registeredId, 1L, fixture.actor().id()))
                .isEqualTo(registered);

        UUID appliedId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), appliedId, 1L, fixture.actor().id());
        ManualQuantityDeltaResult applied = products.applyManualQuantityDelta(
                fixture.product().id(), appliedId, 1L, fixture.actor().id());
        assertThat(products.registerManualQuantityDelta(
                fixture.product().id(), appliedId, 1L, fixture.actor().id()))
                .isEqualTo(applied);

        UUID rejectedId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), rejectedId, -10L, fixture.actor().id());
        ManualQuantityDeltaResult rejected = products.applyManualQuantityDelta(
                fixture.product().id(), rejectedId, -10L, fixture.actor().id());
        assertThat(products.registerManualQuantityDelta(
                fixture.product().id(), rejectedId, -10L, fixture.actor().id()))
                .isEqualTo(rejected);
    }

    @Test
    void changedDeltaForAuthorizedExactPairIsTypedMismatch() {
        ProductFixture fixture = productOwnedByAdmin(4L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 2L, fixture.actor().id());

        assertThatExceptionOfType(
                ReadyMadeProductManualQuantityDeltaPayloadMismatchException.class)
                .isThrownBy(() -> products.registerManualQuantityDelta(
                        fixture.product().id(), commandId, 3L, fixture.actor().id()));
        assertThatExceptionOfType(
                ReadyMadeProductManualQuantityDeltaPayloadMismatchException.class)
                .isThrownBy(() -> products.applyManualQuantityDelta(
                        fixture.product().id(), commandId, 3L, fixture.actor().id()));
        assertThat(storedCommand(fixture.product().id(), commandId).delta()).isEqualTo(2L);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(4L);
    }

    @Test
    void sameCommandIdIsIndependentAcrossProducts() {
        ProductFixture first = productOwnedByAdmin(5L);
        ProductFixture second = productOwnedByAdmin(7L);
        UUID commandId = UUID.randomUUID();

        ManualQuantityDeltaResult firstResult = products.registerManualQuantityDelta(
                first.product().id(), commandId, 2L, first.actor().id());
        ManualQuantityDeltaResult secondResult = products.registerManualQuantityDelta(
                second.product().id(), commandId, -3L, second.actor().id());

        assertThat(firstResult.readyMadeProductId()).isEqualTo(first.product().id());
        assertThat(secondResult.readyMadeProductId()).isEqualTo(second.product().id());
        assertThat(commandCount(commandId)).isEqualTo(2);
        assertThat(storedCommand(first.product().id(), commandId).delta()).isEqualTo(2L);
        assertThat(storedCommand(second.product().id(), commandId).delta()).isEqualTo(-3L);
    }

    @Test
    void invalidOrInaccessibleRegistrationLeavesNoRecord() {
        ProductFixture fixture = productOwnedByAdmin(6L);
        UUID zeroId = UUID.randomUUID();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> products.registerManualQuantityDelta(
                        fixture.product().id(), zeroId, 0L, fixture.actor().id()));
        assertThat(commandCount(fixture.product().id(), zeroId)).isZero();

        UUID inaccessibleId = UUID.randomUUID();
        User outsider = users.createUser();
        assertOpaqueAccess(() -> products.registerManualQuantityDelta(
                fixture.product().id(), inaccessibleId, 1L, outsider.id()));
        assertThat(commandCount(fixture.product().id(), inaccessibleId)).isZero();

        UUID missingProductId = UUID.randomUUID();
        UUID missingProductCommand = UUID.randomUUID();
        assertOpaqueAccess(() -> products.registerManualQuantityDelta(
                missingProductId, missingProductCommand, 1L, fixture.actor().id()));
        assertThat(commandCount(missingProductId, missingProductCommand)).isZero();

        UUID missingActorCommand = UUID.randomUUID();
        assertOpaqueAccess(() -> products.registerManualQuantityDelta(
                fixture.product().id(), missingActorCommand, 1L, UUID.randomUUID()));
        assertThat(commandCount(fixture.product().id(), missingActorCommand)).isZero();

        users.changeStatus(fixture.actor().id(), UserStatus.SUSPENDED);
        UUID inactiveActorCommand = UUID.randomUUID();
        assertOpaqueAccess(() -> products.registerManualQuantityDelta(
                fixture.product().id(), inactiveActorCommand, 1L, fixture.actor().id()));
        assertThat(commandCount(fixture.product().id(), inactiveActorCommand)).isZero();
    }

    @Test
    void authorizationFailureNeverDisclosesOrMutatesExistingCommand() {
        ProductFixture fixture = productOwnedByAdmin(1L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 1L, fixture.actor().id());
        User outsider = users.createUser();

        Throwable samePayload = catchThrowable(() -> products.applyManualQuantityDelta(
                fixture.product().id(), commandId, 1L, outsider.id()));
        Throwable changedPayload = catchThrowable(() -> products.applyManualQuantityDelta(
                fixture.product().id(), commandId, 2L, outsider.id()));
        Throwable missingProduct = catchThrowable(() -> products.applyManualQuantityDelta(
                UUID.randomUUID(), commandId, 2L, outsider.id()));

        assertSameOpaqueAccess(samePayload, changedPayload, missingProduct);
        assertThat(storedCommand(fixture.product().id(), commandId).state())
                .isEqualTo(ManualQuantityDeltaCommandState.REGISTERED);
        assertThat(storedQuantity(fixture.product().id())).isOne();
    }

    @Test
    void registrationSurvivesAmbientRollbackAndCommittedRetryResolvesHistory() {
        ProductFixture fixture = productOwnedByAdmin(3L);
        UUID commandId = UUID.randomUUID();
        ManualQuantityDeltaResult registered = ManualQuantityDeltaResult.registered(
                fixture.product().id(), commandId, 1L);
        assertThat(AopUtils.isAopProxy(products)).isTrue();

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(products.registerManualQuantityDelta(
                    fixture.product().id(), commandId, 1L, fixture.actor().id()))
                    .isEqualTo(registered);
            status.setRollbackOnly();
        });
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(commandCount(fixture.product().id(), commandId)).isOne();
            assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(registered);
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);
        });

        ManualQuantityDeltaResult recoveredAfterLostResponse =
                products.registerManualQuantityDelta(
                        fixture.product().id(), commandId, 1L, fixture.actor().id());
        assertThat(recoveredAfterLostResponse).isEqualTo(registered);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);
    }

    @Test
    void registrationAndApplicationCommitIndependentlyOfAmbientRollback() {
        ProductFixture fixture = productOwnedByAdmin(10L);
        UUID commandId = UUID.randomUUID();
        ManualQuantityDeltaResult registered = ManualQuantityDeltaResult.registered(
                fixture.product().id(), commandId, 5L);
        ManualQuantityDeltaResult applied = ManualQuantityDeltaResult.applied(
                fixture.product().id(), commandId, 5L, 15L);
        assertThat(AopUtils.isAopProxy(products)).isTrue();
        TransactionTemplate independentRead = new TransactionTemplate(
                transactionTemplate.getTransactionManager());
        independentRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independentRead.setReadOnly(true);
        independentRead.setTimeout(10);

        // Fixtures are committed before the ambient transaction: suspended
        // transactions retain locks and their uncommitted data is not visible.
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(products.registerManualQuantityDelta(
                    fixture.product().id(), commandId, 5L, fixture.actor().id()))
                    .isEqualTo(registered);
            // Detect REQUIRED registration before application could wait on
            // locks held by the suspended ambient transaction.
            independentRead.executeWithoutResult(readStatus -> {
                assertThat(commandCount(fixture.product().id(), commandId)).isOne();
                assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(registered);
                assertThat(storedQuantity(fixture.product().id())).isEqualTo(10L);
            });
            assertThat(products.applyManualQuantityDelta(
                    fixture.product().id(), commandId, 5L, fixture.actor().id()))
                    .isEqualTo(applied);
            status.setRollbackOnly();
        });

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(applied);
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(15L);
        });
        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), commandId, 5L, fixture.actor().id()))
                .isEqualTo(applied);
        assertThat(commandCount(fixture.product().id(), commandId)).isOne();
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(15L);
    }

    @ParameterizedTest(name = "delta={0}, invalid reason={1}")
    @CsvSource({"2, UNDERFLOW", "-2, OVERFLOW"})
    void rawSqlRejectsReasonIncompatibleWithDeltaSign(
            long delta, ManualQuantityDeltaRejectionReason reason) {
        ProductFixture fixture = productOwnedByAdmin(3L);
        UUID commandId = UUID.randomUUID();
        ManualQuantityDeltaResult registered = products.registerManualQuantityDelta(
                fixture.product().id(), commandId, delta, fixture.actor().id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE " + TABLE + " SET state = 'REJECTED', "
                                + "rejection_reason = ?, observed_available_quantity = 3 "
                                + "WHERE product_id = ? AND command_id = ?",
                        reason.name(), fixture.product().id(), commandId)))
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, failure -> {
                    assertThat(failure.getSQLState()).isEqualTo(CHECK_VIOLATION_SQL_STATE);
                    assertThat(failure.getServerErrorMessage()).isNotNull();
                    assertThat(failure.getServerErrorMessage().getConstraint())
                            .isEqualTo(SIGN_REASON_CHECK);
                });

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(registered);
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);
        });
    }

    @ParameterizedTest(name = "available={0}, delta={1}, result={2}")
    @CsvSource({
            "4, 3, 7",
            "4, -3, 1",
            "0, 9223372036854775807, 9223372036854775807",
            "1, -1, 0"
    })
    void applicationProducesExactAppliedOutcome(
            long available, long delta, long expected) {
        ProductFixture fixture = productOwnedByAdmin(available);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, delta, fixture.actor().id());

        ManualQuantityDeltaResult result = products.applyManualQuantityDelta(
                fixture.product().id(), commandId, delta, fixture.actor().id());

        assertThat(result).isEqualTo(ManualQuantityDeltaResult.applied(
                fixture.product().id(), commandId, delta, expected));
        assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(result);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(expected);
    }

    @ParameterizedTest(name = "available={0}, delta={1}, reason={2}")
    @CsvSource({
            "0, -1, UNDERFLOW",
            "0, -9223372036854775808, UNDERFLOW",
            "9223372036854775807, 1, OVERFLOW",
            "9223372036854775807, 9223372036854775807, OVERFLOW"
    })
    void arithmeticBoundaryBecomesExactTerminalRejection(
            long available,
            long delta,
            ManualQuantityDeltaRejectionReason reason) {
        ProductFixture fixture = productOwnedByAdmin(available);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, delta, fixture.actor().id());

        ManualQuantityDeltaResult result = products.applyManualQuantityDelta(
                fixture.product().id(), commandId, delta, fixture.actor().id());

        assertThat(result).isEqualTo(ManualQuantityDeltaResult.rejected(
                fixture.product().id(), commandId, delta, reason, available));
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(available);
        assertThat(storedCommand(fixture.product().id(), commandId)).isEqualTo(result);
        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), commandId, delta, fixture.actor().id()))
                .isEqualTo(result);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(available);
    }

    @Test
    void appliedReplayReturnsHistoricalResultWithoutRecalculationOrMutation() {
        ProductFixture fixture = productOwnedByAdmin(5L);
        UUID appliedId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), appliedId, 2L, fixture.actor().id());
        ManualQuantityDeltaResult applied = products.applyManualQuantityDelta(
                fixture.product().id(), appliedId, 2L, fixture.actor().id());

        UUID laterId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), laterId, 10L, fixture.actor().id());
        products.applyManualQuantityDelta(
                fixture.product().id(), laterId, 10L, fixture.actor().id());
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(17L);

        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), appliedId, 2L, fixture.actor().id()))
                .isEqualTo(applied);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(17L);
    }

    @Test
    void rejectedReplayIsImmutableAndANewAttemptRequiresANewCommandId() {
        ProductFixture fixture = productOwnedByAdmin(0L);
        UUID rejectedId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), rejectedId, -1L, fixture.actor().id());
        ManualQuantityDeltaResult rejected = products.applyManualQuantityDelta(
                fixture.product().id(), rejectedId, -1L, fixture.actor().id());

        UUID refillId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), refillId, 3L, fixture.actor().id());
        products.applyManualQuantityDelta(
                fixture.product().id(), refillId, 3L, fixture.actor().id());

        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), rejectedId, -1L, fixture.actor().id()))
                .isEqualTo(rejected);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);

        UUID newAttemptId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), newAttemptId, -1L, fixture.actor().id());
        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), newAttemptId, -1L, fixture.actor().id()).state())
                .isEqualTo(ManualQuantityDeltaCommandState.APPLIED);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(2L);
    }

    @Test
    void anotherCurrentlyAuthorizedActorMayApplyAndReplay() {
        ProductFixture fixture = productOwnedByAdmin(2L);
        User secondAdmin = users.createUser();
        addMembership(fixture.workspace().id(), secondAdmin.id(), "ADMIN", "ACTIVE");
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 2L, fixture.actor().id());

        ManualQuantityDeltaResult applied = products.applyManualQuantityDelta(
                fixture.product().id(), commandId, 2L, secondAdmin.id());

        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), commandId, 2L, fixture.actor().id()))
                .isEqualTo(applied);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(4L);
    }

    @Test
    void activeEditorNeedsTheExactGrantWhileAdminNeedsNoScopeRow() {
        ProductFixture fixture = productOwnedByAdmin(2L);
        User editor = users.createUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", "ACTIVE");
        UUID deniedId = UUID.randomUUID();
        assertOpaqueAccess(() -> products.registerManualQuantityDelta(
                fixture.product().id(), deniedId, 1L, editor.id()));
        assertThat(commandCount(fixture.product().id(), deniedId)).isZero();

        addScope(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS");
        UUID allowedId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), allowedId, 1L, editor.id());
        assertThat(products.applyManualQuantityDelta(
                fixture.product().id(), allowedId, 1L, editor.id()).state())
                .isEqualTo(ManualQuantityDeltaCommandState.APPLIED);

        assertThat(scopeCount(fixture.workspace().id(), fixture.actor().id())).isZero();
        UUID adminId = UUID.randomUUID();
        assertThat(products.registerManualQuantityDelta(
                fixture.product().id(), adminId, 1L, fixture.actor().id()).state())
                .isEqualTo(ManualQuantityDeltaCommandState.REGISTERED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleStatusNeverChangesDuringManualApplication(boolean archiveFirst) {
        ProductFixture fixture = productOwnedByAdmin(2L);
        if (archiveFirst) {
            products.archiveReadyMadeProduct(fixture.product().id(), fixture.actor().id());
        }
        String expectedStatus = archiveFirst ? "ARCHIVED" : "ACTIVE";
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 1L, fixture.actor().id());

        products.applyManualQuantityDelta(
                fixture.product().id(), commandId, 1L, fixture.actor().id());

        assertThat(storedStatus(fixture.product().id())).isEqualTo(expectedStatus);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);
    }

    @Test
    void missingRegistrationIsTypedOnlyAfterSuccessfulAuthorization() {
        ProductFixture fixture = productOwnedByAdmin(1L);

        assertThatExceptionOfType(
                ReadyMadeProductManualQuantityDeltaNotRegisteredException.class)
                .isThrownBy(() -> products.applyManualQuantityDelta(
                        fixture.product().id(), UUID.randomUUID(), 1L, fixture.actor().id()));
        assertThat(storedQuantity(fixture.product().id())).isOne();
    }

    @Test
    void failureAfterProductUpdateRollsBackProductAndLeavesCommandRegistered() {
        ProductFixture fixture = productOwnedByAdmin(3L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 2L, fixture.actor().id());

        jdbcTemplate.execute("CREATE FUNCTION test_fail_rmp_delta_terminal_update() "
                + "RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.state = 'APPLIED' THEN "
                + "RAISE EXCEPTION 'injected terminal persistence failure' USING ERRCODE = 'XX000'; "
                + "END IF; RETURN NEW; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER aaa_test_fail_rmp_delta_terminal_update "
                + "BEFORE UPDATE ON " + TABLE + " FOR EACH ROW "
                + "EXECUTE FUNCTION test_fail_rmp_delta_terminal_update()");
        try {
            assertThatThrownBy(() -> products.applyManualQuantityDelta(
                    fixture.product().id(), commandId, 2L, fixture.actor().id()))
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .extracting(cause -> ((PSQLException) cause).getSQLState())
                    .isEqualTo("XX000");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS "
                    + "aaa_test_fail_rmp_delta_terminal_update ON " + TABLE);
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_rmp_delta_terminal_update()");
        }

        assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);
        assertThat(storedCommand(fixture.product().id(), commandId))
                .isEqualTo(ManualQuantityDeltaResult.registered(
                        fixture.product().id(), commandId, 2L));
    }

    @Test
    @Timeout(60)
    void unauthorizedCallDoesNotWaitForOrReadLockedCommandRow() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(1L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 1L, fixture.actor().id());
        User outsider = users.createUser();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<Integer> commandHolder = startHeld(
                    executor,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT 1 FROM " + TABLE + " "
                                    + "WHERE product_id = ? AND command_id = ? FOR UPDATE",
                            Integer.class, fixture.product().id(), commandId),
                    false);
            await(commandHolder.operationFinished(), "command row lock");

            Future<Outcome<ManualQuantityDeltaResult>> unauthorized = executor.submit(
                    () -> capture(() -> products.applyManualQuantityDelta(
                            fixture.product().id(), commandId, 2L, outsider.id())));
            Outcome<ManualQuantityDeltaResult> outcome = unauthorized.get(10, TimeUnit.SECONDS);
            assertThat(outcome.failure())
                    .isInstanceOf(ReadyMadeProductManualQuantityDeltaAccessException.class);
            // The service has its own transaction. It must finish while the
            // command row remains locked, without using a suspended outer PID.
            assertThat(commandHolder.release().getCount()).isOne();
            assertThat(commandHolder.future().isDone()).isFalse();

            commandHolder.release().countDown();
            assertThat(commandHolder.future().get(20, TimeUnit.SECONDS).failure()).isNull();
        } finally {
            shutdown(executor);
        }
    }

    // ------------------------------------------------------------------
    // Deterministic concurrency: exact PIDs, bounded latches, no sleeps
    // ------------------------------------------------------------------

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void concurrentIdenticalRegistrationResolvesOneCommittedBinding() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(4L);
        UUID commandId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> winner = startHeld(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            fixture.product().id(), commandId, 2L, fixture.actor().id()),
                    false);
            await(winner.operationFinished(), "winner registration");
            RunningCall<ManualQuantityDeltaResult> contender = startCall(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            fixture.product().id(), commandId, 2L, fixture.actor().id()));
            awaitBlockedBy(contender.pid(), winner.pid());

            winner.release().countDown();
            Outcome<ManualQuantityDeltaResult> winnerOutcome =
                    winner.future().get(20, TimeUnit.SECONDS);
            Outcome<ManualQuantityDeltaResult> contenderOutcome =
                    contender.future().get(20, TimeUnit.SECONDS);

            assertThat(winnerOutcome.failure()).isNull();
            assertThat(contenderOutcome.failure()).isNull();
            assertThat(contenderOutcome.value()).isEqualTo(winnerOutcome.value());
            assertThat(commandCount(fixture.product().id(), commandId)).isOne();
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(4L);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void concurrentDifferentDeltaDecidesMismatchAfterWinnerCommit() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(4L);
        UUID commandId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> winner = startHeld(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            fixture.product().id(), commandId, 2L, fixture.actor().id()),
                    false);
            await(winner.operationFinished(), "winner registration");
            RunningCall<ManualQuantityDeltaResult> contender = startCall(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            fixture.product().id(), commandId, 3L, fixture.actor().id()));
            awaitBlockedBy(contender.pid(), winner.pid());

            winner.release().countDown();
            Outcome<ManualQuantityDeltaResult> winnerOutcome =
                    winner.future().get(20, TimeUnit.SECONDS);
            Outcome<ManualQuantityDeltaResult> contenderOutcome =
                    contender.future().get(20, TimeUnit.SECONDS);

            assertThat(winnerOutcome.failure()).isNull();
            assertThat(contenderOutcome.failure())
                    .isInstanceOf(ReadyMadeProductManualQuantityDeltaPayloadMismatchException.class);
            assertThat(storedCommand(fixture.product().id(), commandId).delta()).isEqualTo(2L);
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(4L);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void rolledBackFirstRegistrationLeavesNoDirtyBindingForWaiter() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(4L);
        UUID commandId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> rolledBackWinner = startHeld(
                    executor,
                    () -> {
                        ManualQuantityDeltaResult result =
                                productRepository.registerManualQuantityDelta(
                                        fixture.product().id(), commandId, 2L,
                                        fixture.actor().id());
                        jdbcTemplate.update(
                                "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                                fixture.actor().id());
                        return result;
                    },
                    true);
            await(rolledBackWinner.operationFinished(), "rollback registration");
            RunningCall<ManualQuantityDeltaResult> waiter = startCall(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            fixture.product().id(), commandId, 3L, fixture.actor().id()));
            awaitBlockedBy(waiter.pid(), rolledBackWinner.pid());

            rolledBackWinner.release().countDown();
            Outcome<ManualQuantityDeltaResult> rolledBackOutcome =
                    rolledBackWinner.future().get(20, TimeUnit.SECONDS);
            Outcome<ManualQuantityDeltaResult> waiterOutcome =
                    waiter.future().get(20, TimeUnit.SECONDS);

            assertThat(rolledBackOutcome.failure()).isNull();
            assertThat(waiterOutcome.failure()).isNull();
            assertThat(waiterOutcome.value().delta()).isEqualTo(3L);
            assertThat(storedCommand(fixture.product().id(), commandId).delta()).isEqualTo(3L);
            assertThat(users.findUser(fixture.actor().id()).status()).isEqualTo(UserStatus.ACTIVE);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void sameCommandIdOnDifferentProductsCommitsWithoutCrossProductWait() throws Exception {
        ProductFixture first = productOwnedByAdmin(3L);
        ProductFixture second = productOwnedByAdmin(6L);
        UUID commandId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> firstRegistration = startHeld(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            first.product().id(), commandId, 1L, first.actor().id()),
                    false);
            await(firstRegistration.operationFinished(), "first Product registration");
            RunningTransaction<ManualQuantityDeltaResult> secondRegistration = startHeld(
                    executor,
                    () -> productRepository.registerManualQuantityDelta(
                            second.product().id(), commandId, -1L, second.actor().id()),
                    false);
            await(secondRegistration.operationFinished(), "second Product registration");

            assertThat(blockersOf(secondRegistration.pid().get()))
                    .doesNotContain(firstRegistration.pid().get());
            secondRegistration.release().countDown();
            assertThat(secondRegistration.future().get(20, TimeUnit.SECONDS).failure()).isNull();
            firstRegistration.release().countDown();
            assertThat(firstRegistration.future().get(20, TimeUnit.SECONDS).failure()).isNull();
            assertThat(commandCount(commandId)).isEqualTo(2);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void concurrentDuplicateApplicationMutatesAvailableQuantityExactlyOnce() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(3L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 2L, fixture.actor().id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> first = startHeld(
                    executor,
                    () -> productRepository.applyManualQuantityDelta(
                            fixture.product().id(), commandId, 2L, fixture.actor().id()),
                    false);
            await(first.operationFinished(), "first application");
            RunningCall<ManualQuantityDeltaResult> duplicate = startCall(
                    executor,
                    () -> productRepository.applyManualQuantityDelta(
                            fixture.product().id(), commandId, 2L, fixture.actor().id()));
            awaitBlockedBy(duplicate.pid(), first.pid());

            first.release().countDown();
            Outcome<ManualQuantityDeltaResult> firstOutcome =
                    first.future().get(20, TimeUnit.SECONDS);
            Outcome<ManualQuantityDeltaResult> duplicateOutcome =
                    duplicate.future().get(20, TimeUnit.SECONDS);

            assertThat(firstOutcome.failure()).isNull();
            assertThat(duplicateOutcome.failure()).isNull();
            assertThat(duplicateOutcome.value()).isEqualTo(firstOutcome.value());
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(5L);
            assertThat(storedCommand(fixture.product().id(), commandId).state())
                    .isEqualTo(ManualQuantityDeltaCommandState.APPLIED);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void differentCommandsAgainstLimitedQuantitySerializeWithoutLostUpdate() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(1L);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), firstId, -1L, fixture.actor().id());
        products.registerManualQuantityDelta(
                fixture.product().id(), secondId, -1L, fixture.actor().id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> first = startHeld(
                    executor,
                    () -> productRepository.applyManualQuantityDelta(
                            fixture.product().id(), firstId, -1L, fixture.actor().id()),
                    false);
            await(first.operationFinished(), "first limited-stock application");
            RunningCall<ManualQuantityDeltaResult> second = startCall(
                    executor,
                    () -> productRepository.applyManualQuantityDelta(
                            fixture.product().id(), secondId, -1L, fixture.actor().id()));
            awaitBlockedBy(second.pid(), first.pid());

            first.release().countDown();
            Outcome<ManualQuantityDeltaResult> firstOutcome =
                    first.future().get(20, TimeUnit.SECONDS);
            Outcome<ManualQuantityDeltaResult> secondOutcome =
                    second.future().get(20, TimeUnit.SECONDS);

            assertThat(firstOutcome.failure()).isNull();
            assertThat(firstOutcome.value().state())
                    .isEqualTo(ManualQuantityDeltaCommandState.APPLIED);
            assertThat(secondOutcome.failure()).isNull();
            assertThat(secondOutcome.value().state())
                    .isEqualTo(ManualQuantityDeltaCommandState.REJECTED);
            assertThat(secondOutcome.value().rejectionReason())
                    .isEqualTo(ManualQuantityDeltaRejectionReason.UNDERFLOW);
            assertThat(secondOutcome.value().observedAvailableQuantity()).isZero();
            assertThat(storedQuantity(fixture.product().id())).isZero();
        } finally {
            shutdown(executor);
        }
    }

    @Test
    @Tag("manual-delta-concurrency")
    @Timeout(90)
    void applicationAndActorRevocationProveBothCommitOrders() throws Exception {
        proveRevocationFirstRejectsStaleApplication();
        proveApplicationFirstPreservesCommittedHistoricalResult();
    }

    @ParameterizedTest(name = "manual first={0}")
    @ValueSource(booleans = {true, false})
    @Tag("manual-delta-concurrency")
    @Timeout(60)
    void manualDeltaAndLifecycleTransitionUseCompatibleOrdering(boolean manualFirst)
            throws Exception {
        ProductFixture fixture = productOwnedByAdmin(5L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 2L, fixture.actor().id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            if (manualFirst) {
                RunningTransaction<ManualQuantityDeltaResult> manual = startHeld(
                        executor,
                        () -> productRepository.applyManualQuantityDelta(
                                fixture.product().id(), commandId, 2L, fixture.actor().id()),
                        false);
                await(manual.operationFinished(), "manual application");
                RunningCall<ReadyMadeProduct> lifecycle = startCall(
                        executor,
                        () -> productRepository.transitionStatus(
                                fixture.product().id(), fixture.actor().id(),
                                ReadyMadeProductStatus.ACTIVE,
                                ReadyMadeProductStatus.ARCHIVED));
                awaitBlockedBy(lifecycle.pid(), manual.pid());
                manual.release().countDown();
                assertThat(manual.future().get(20, TimeUnit.SECONDS).failure()).isNull();
                assertThat(lifecycle.future().get(20, TimeUnit.SECONDS).failure()).isNull();
            } else {
                RunningTransaction<ReadyMadeProduct> lifecycle = startHeld(
                        executor,
                        () -> productRepository.transitionStatus(
                                fixture.product().id(), fixture.actor().id(),
                                ReadyMadeProductStatus.ACTIVE,
                                ReadyMadeProductStatus.ARCHIVED),
                        false);
                await(lifecycle.operationFinished(), "lifecycle transition");
                RunningCall<ManualQuantityDeltaResult> manual = startCall(
                        executor,
                        () -> productRepository.applyManualQuantityDelta(
                                fixture.product().id(), commandId, 2L, fixture.actor().id()));
                awaitBlockedBy(manual.pid(), lifecycle.pid());
                lifecycle.release().countDown();
                assertThat(lifecycle.future().get(20, TimeUnit.SECONDS).failure()).isNull();
                assertThat(manual.future().get(20, TimeUnit.SECONDS).failure()).isNull();
            }

            assertThat(storedStatus(fixture.product().id())).isEqualTo("ARCHIVED");
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(7L);
            assertThat(storedCommand(fixture.product().id(), commandId).state())
                    .isEqualTo(ManualQuantityDeltaCommandState.APPLIED);
        } finally {
            shutdown(executor);
        }
    }

    private void proveRevocationFirstRejectsStaleApplication() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(2L);
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 1L, fixture.actor().id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<Integer> revocation = startHeld(
                    executor,
                    () -> jdbcTemplate.update(
                            "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                            fixture.actor().id()),
                    false);
            await(revocation.operationFinished(), "actor revocation");
            RunningCall<ManualQuantityDeltaResult> application = startCall(
                    executor,
                    () -> productRepository.applyManualQuantityDelta(
                            fixture.product().id(), commandId, 1L, fixture.actor().id()));
            awaitBlockedBy(application.pid(), revocation.pid());

            revocation.release().countDown();
            assertThat(revocation.future().get(20, TimeUnit.SECONDS).failure()).isNull();
            Outcome<ManualQuantityDeltaResult> applicationOutcome =
                    application.future().get(20, TimeUnit.SECONDS);
            assertThat(applicationOutcome.failure())
                    .isInstanceOf(ReadyMadeProductManualQuantityDeltaAccessException.class);
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(2L);
            assertThat(storedCommand(fixture.product().id(), commandId).state())
                    .isEqualTo(ManualQuantityDeltaCommandState.REGISTERED);
        } finally {
            shutdown(executor);
        }
    }

    private void proveApplicationFirstPreservesCommittedHistoricalResult() throws Exception {
        ProductFixture fixture = productOwnedByAdmin(2L);
        User replacementAdmin = users.createUser();
        addMembership(fixture.workspace().id(), replacementAdmin.id(), "ADMIN", "ACTIVE");
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(
                fixture.product().id(), commandId, 1L, fixture.actor().id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RunningTransaction<ManualQuantityDeltaResult> application = startHeld(
                    executor,
                    () -> productRepository.applyManualQuantityDelta(
                            fixture.product().id(), commandId, 1L, fixture.actor().id()),
                    false);
            await(application.operationFinished(), "authorized application");
            RunningCall<Integer> revocation = startCall(
                    executor,
                    () -> jdbcTemplate.update(
                            "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                            fixture.actor().id()));
            awaitBlockedBy(revocation.pid(), application.pid());

            application.release().countDown();
            Outcome<ManualQuantityDeltaResult> applicationOutcome =
                    application.future().get(20, TimeUnit.SECONDS);
            assertThat(applicationOutcome.failure()).isNull();
            assertThat(revocation.future().get(20, TimeUnit.SECONDS).failure()).isNull();

            assertOpaqueAccess(() -> products.applyManualQuantityDelta(
                    fixture.product().id(), commandId, 1L, fixture.actor().id()));
            assertThat(products.applyManualQuantityDelta(
                    fixture.product().id(), commandId, 1L, replacementAdmin.id()))
                    .isEqualTo(applicationOutcome.value());
            assertThat(storedQuantity(fixture.product().id())).isEqualTo(3L);
        } finally {
            shutdown(executor);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures, metadata, transaction harness, and assertions
    // ------------------------------------------------------------------

    private ProductFixture productOwnedByAdmin(long availableQuantity) {
        User actor = users.createUser();
        Workspace workspace = workspaces.createUserOwnedWorkspace(actor.id());
        ReadyMadeProduct product = products.createReadyMadeProduct(
                workspace.id(), actor.id(), availableQuantity);
        return new ProductFixture(actor, workspace, product);
    }

    private long commandRelationOid() {
        Long oid = jdbcTemplate.queryForObject(
                "SELECT c.oid FROM pg_class c JOIN pg_namespace n "
                        + "ON n.oid = c.relnamespace WHERE n.nspname = 'public' "
                        + "AND c.relname = ? AND c.relkind = 'r'",
                Long.class, TABLE);
        assertThat(oid).isNotNull();
        return oid;
    }

    private List<TriggerMetadata> commandTriggers(long relationOid) {
        return jdbcTemplate.query(
                "SELECT t.oid AS trigger_oid, t.tgname, p.oid AS function_oid, "
                        + "pn.nspname AS function_schema, p.proname AS function_name, "
                        + "pg_get_triggerdef(t.oid) AS definition, p.prosrc "
                        + "FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid "
                        + "JOIN pg_namespace pn ON pn.oid = p.pronamespace "
                        + "WHERE t.tgrelid = CAST(? AS oid) AND NOT t.tgisinternal "
                        + "ORDER BY t.tgname",
                (rs, rowNum) -> new TriggerMetadata(
                        rs.getLong("trigger_oid"),
                        rs.getString("tgname"),
                        rs.getLong("function_oid"),
                        rs.getString("function_schema"),
                        rs.getString("function_name"),
                        rs.getString("definition"),
                        rs.getString("prosrc")),
                relationOid);
    }

    private void assertExpectedTriggerMetadata(List<TriggerMetadata> triggers) {
        assertThat(triggers).extracting(TriggerMetadata::name)
                .containsExactly(
                        "rmp_manual_quantity_delta_prevent_delete",
                        "rmp_manual_quantity_delta_prevent_truncate",
                        "rmp_manual_quantity_delta_validate_insert",
                        "rmp_manual_quantity_delta_validate_update");
        assertThat(trigger(triggers, "rmp_manual_quantity_delta_validate_insert").functionName())
                .isEqualTo("validate_rmp_manual_quantity_delta_command_insert");
        assertThat(trigger(triggers, "rmp_manual_quantity_delta_validate_update").functionName())
                .isEqualTo("validate_rmp_manual_quantity_delta_command_update");
        assertThat(trigger(triggers, "rmp_manual_quantity_delta_prevent_delete").functionName())
                .isEqualTo("prevent_rmp_manual_quantity_delta_command_removal");
        assertThat(trigger(triggers, "rmp_manual_quantity_delta_prevent_truncate").functionName())
                .isEqualTo("prevent_rmp_manual_quantity_delta_command_removal");
        assertThat(trigger(triggers, "rmp_manual_quantity_delta_validate_update").source())
                .contains("NEW.product_id IS DISTINCT FROM OLD.product_id")
                .contains("OLD.state <> 'REGISTERED'")
                .contains("NEW.state NOT IN ('APPLIED', 'REJECTED')");
        assertThat(trigger(triggers, "rmp_manual_quantity_delta_prevent_delete").source())
                .contains("command records are permanent");
    }

    private void insertRawRegistered(UUID productId, UUID commandId, long delta) {
        insertRawCommand(productId, commandId, delta, "REGISTERED", null, null, null);
    }

    private void insertRawCommand(
            UUID productId,
            UUID commandId,
            long delta,
            String state,
            Long resultingQuantity,
            String rejectionReason,
            Long observedQuantity) {
        jdbcTemplate.update(
                "INSERT INTO " + TABLE + " "
                        + "(product_id, command_id, delta, state, "
                        + "resulting_available_quantity, rejection_reason, "
                        + "observed_available_quantity) VALUES (?, ?, ?, ?, ?, ?, ?)",
                productId, commandId, delta, state,
                resultingQuantity, rejectionReason, observedQuantity);
    }

    private ManualQuantityDeltaResult storedCommand(UUID productId, UUID commandId) {
        return jdbcTemplate.queryForObject(
                "SELECT product_id, command_id, delta, state, "
                        + "resulting_available_quantity, rejection_reason, "
                        + "observed_available_quantity FROM " + TABLE + " "
                        + "WHERE product_id = ? AND command_id = ?",
                (rs, rowNum) -> new ManualQuantityDeltaResult(
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("command_id", UUID.class),
                        rs.getLong("delta"),
                        ManualQuantityDeltaCommandState.valueOf(rs.getString("state")),
                        nullableLong(rs.getObject("resulting_available_quantity")),
                        Optional.ofNullable(rs.getString("rejection_reason"))
                                .map(ManualQuantityDeltaRejectionReason::valueOf)
                                .orElse(null),
                        nullableLong(rs.getObject("observed_available_quantity"))),
                productId, commandId);
    }

    private long storedQuantity(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM ready_made_products WHERE id = ?",
                Long.class, productId);
    }

    private String storedStatus(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ready_made_products WHERE id = ?",
                String.class, productId);
    }

    private int commandCount(UUID productId, UUID commandId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " "
                        + "WHERE product_id = ? AND command_id = ?",
                Integer.class, productId, commandId);
    }

    private int commandCount(UUID commandId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE command_id = ?",
                Integer.class, commandId);
    }

    private void addMembership(UUID workspaceId, UUID userId, String role, String status) {
        jdbcTemplate.update(
                "INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                        + "VALUES (?, ?, ?, ?)",
                workspaceId, userId, role, status);
    }

    private void addScope(UUID workspaceId, UUID userId, String scope) {
        jdbcTemplate.update(
                "INSERT INTO workspace_membership_scopes (workspace_id, user_id, scope) "
                        + "VALUES (?, ?, ?)",
                workspaceId, userId, scope);
    }

    private int scopeCount(UUID workspaceId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_membership_scopes "
                        + "WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspaceId, userId);
    }

    private void assertCheckViolation(Runnable action) {
        assertThatThrownBy(action::run)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    private void assertOpaqueAccess(Runnable action) {
        assertThatExceptionOfType(ReadyMadeProductManualQuantityDeltaAccessException.class)
                .isThrownBy(action::run)
                .withMessage("Manual quantity-delta operation is not accessible");
    }

    private void assertSameOpaqueAccess(Throwable... failures) {
        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure)
                    .isExactlyInstanceOf(ReadyMadeProductManualQuantityDeltaAccessException.class)
                    .hasMessage("Manual quantity-delta operation is not accessible");
        });
    }

    private <T> RunningTransaction<T> startHeld(
            ExecutorService executor,
            Supplier<T> operation,
            boolean rollback) {
        AtomicReference<Integer> pid = new AtomicReference<>();
        CountDownLatch pidReady = new CountDownLatch(1);
        CountDownLatch operationFinished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Outcome<T>> future = executor.submit(() -> capture(() ->
                transactionTemplate.execute(status -> {
                    pid.set(currentBackendPid());
                    pidReady.countDown();
                    try {
                        T value = operation.get();
                        operationFinished.countDown();
                        await(release, "held transaction release");
                        if (rollback) {
                            status.setRollbackOnly();
                        }
                        return value;
                    } finally {
                        operationFinished.countDown();
                    }
                })));
        await(pidReady, "held transaction PID");
        return new RunningTransaction<>(pid, operationFinished, release, future);
    }

    private <T> RunningCall<T> startCall(
            ExecutorService executor,
            Supplier<T> operation) {
        AtomicReference<Integer> pid = new AtomicReference<>();
        CountDownLatch pidReady = new CountDownLatch(1);
        Future<Outcome<T>> future = executor.submit(() -> capture(() ->
                transactionTemplate.execute(status -> {
                    pid.set(currentBackendPid());
                    pidReady.countDown();
                    return operation.get();
                })));
        await(pidReady, "transaction PID");
        return new RunningCall<>(pid, future);
    }

    private <T> Outcome<T> capture(Supplier<T> operation) {
        try {
            return new Outcome<>(operation.get(), null);
        } catch (Throwable failure) {
            return new Outcome<>(null, failure);
        }
    }

    private int currentBackendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void awaitBlockedBy(
            AtomicReference<Integer> waiterPid,
            AtomicReference<Integer> blockerPid) {
        Integer waiter = waiterPid.get();
        Integer blocker = blockerPid.get();
        assertThat(waiter).isNotNull();
        assertThat(blocker).isNotNull();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (blockersOf(waiter).contains(blocker)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
                "Backend PID %d was not blocked by PID %d; blockers=%s"
                        .formatted(waiter, blocker, blockersOf(waiter)));
    }

    private List<Integer> blockersOf(int waiterPid) {
        return jdbcTemplate.queryForList(
                "SELECT blocker_pid FROM unnest(pg_blocking_pids(?)) AS blocker_pid",
                Integer.class, waiterPid);
    }

    private void await(CountDownLatch latch, String purpose) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + purpose);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + purpose, exception);
        }
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private static Map<String, Object> constraint(
            List<Map<String, Object>> constraints, String name) {
        return constraints.stream()
                .filter(row -> name.equals(row.get("conname")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Constraint not found: " + name));
    }

    private static TriggerMetadata trigger(List<TriggerMetadata> triggers, String name) {
        return triggers.stream()
                .filter(trigger -> trigger.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Trigger not found: " + name));
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private record ColumnMetadata(
            String name, String type, boolean notNull, String defaultExpression) {
    }

    private record TriggerMetadata(
            long oid,
            String name,
            long functionOid,
            String functionSchema,
            String functionName,
            String definition,
            String source) {
    }

    private record ProductFixture(User actor, Workspace workspace, ReadyMadeProduct product) {
    }

    private record Outcome<T>(T value, Throwable failure) {
    }

    private record RunningTransaction<T>(
            AtomicReference<Integer> pid,
            CountDownLatch operationFinished,
            CountDownLatch release,
            Future<Outcome<T>> future) {
    }

    private record RunningCall<T>(
            AtomicReference<Integer> pid,
            Future<Outcome<T>> future) {
    }
}
