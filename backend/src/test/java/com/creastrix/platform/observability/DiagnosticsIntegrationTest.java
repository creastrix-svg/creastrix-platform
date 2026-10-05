package com.creastrix.platform.observability;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creastrix.platform.readymadeproduct.application.ReadyMadeProductService;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaResult;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.workspace.application.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real service transactions distinguish returned results from committed database changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Timeout(60)
class DiagnosticsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserService users;

    @Autowired
    private WorkspaceService workspaces;

    @Autowired
    private ReadyMadeProductService products;

    private final Logger logger = (Logger) LoggerFactory.getLogger(Diagnostics.LOGGER_NAME);
    private ListAppender<ILoggingEvent> capture;
    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;

    @BeforeEach
    void observeOnlyTheDedicatedDiagnosticLogger() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(10);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        capture = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        capture.setContext(logger.getLoggerContext());
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detachOnlyThisTestsAppender() {
        logger.detachAppender(capture);
        capture.stop();
    }

    @Test
    void returnedUserIsNotReportedCommittedUntilThePhysicalTransactionCompletes() {
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isEqualTo(180004);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");

        UUID userId = transaction.execute(status -> {
            UUID id = users.createUser().id();
            assertMessages();
            assertThat(independentlyVisibleUserCount(id)).isZero();
            return id;
        });

        assertThat(independentlyVisibleUserCount(userId)).isOne();
        assertThat(profileCount(userId)).isOne();
        assertMessages(event("USER_CREATE", "COMMITTED"));
        assertNoLoggedIdentifiers(userId);
    }

    @Test
    void ambientRollbackRemovesUserAndProfileAndNeverReportsCommittedSuccess() {
        UUID userId = transaction.execute(status -> {
            UUID id = users.createUser().id();
            assertMessages();
            status.setRollbackOnly();
            return id;
        });

        assertThat(independentlyVisibleUserCount(userId)).isZero();
        assertThat(profileCount(userId)).isZero();
        assertMessages(event("USER_CREATE", "ROLLED_BACK"));
        assertNoLoggedIdentifiers(userId);
    }

    @Test
    void failureAfterServiceReturnRollsBackWithoutLoggingItsMessageOrCause() {
        String sensitiveMarker = "synthetic-private-value\r\nforged-event";
        RuntimeException expected = new IllegalStateException(sensitiveMarker,
                new SQLException(sensitiveMarker));
        UUID[] userId = new UUID[1];

        Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            userId[0] = users.createUser().id();
            assertMessages();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw expected;
                }
            });
        }));

        // Boolean comparisons cannot print the synthetic secret if an assertion fails.
        assertThat(failure == expected).isTrue();
        assertThat(independentlyVisibleUserCount(userId[0])).isZero();
        assertThat(profileCount(userId[0])).isZero();
        // The observer entered the commit phase before the injected failure; it cannot
        // distinguish this rollback from a lost acknowledgement after a physical commit.
        assertMessages(event("USER_CREATE", "UNKNOWN"));
        assertThat(messages().stream().anyMatch(message -> message.contains(sensitiveMarker))).isFalse();
        assertNoLoggedIdentifiers(userId[0]);
    }

    @Test
    void springManagedSavepointRollbackIsConservativelyUnknownEvenWhenOuterCommitSucceeds() {
        UUID userId = transaction.execute(status -> {
            Object savepoint = status.createSavepoint();
            UUID id = users.createUser().id();
            assertMessages();
            status.rollbackToSavepoint(savepoint);
            status.releaseSavepoint(savepoint);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, id))
                    .isZero();
            assertMessages();
            return id;
        });

        assertThat(independentlyVisibleUserCount(userId)).isZero();
        assertThat(profileCount(userId)).isZero();
        assertMessages(event("USER_CREATE", "UNKNOWN"));
        assertThat(capture.list.size()).as("Exactly one savepoint-completion diagnostic").isEqualTo(1);
        assertThat(capture.list.getFirst().getLevel() == ch.qos.logback.classic.Level.WARN)
                .as("Savepoint-completion diagnostic has WARN level").isTrue();
        assertNoLoggedIdentifiers(userId);
    }

    @Test
    void requiresNewRegistrationIsReportedCommittedDespiteAmbientRollback() {
        ProductFixture fixture = productFixture();
        UUID commandId = UUID.randomUUID();
        capture.list.clear();

        UUID ambientUserId = transaction.execute(status -> {
            UUID id = users.createUser().id();
            assertThat(products.registerManualQuantityDelta(fixture.product().id(), commandId,
                    5, fixture.actorId()))
                    .isEqualTo(ManualQuantityDeltaResult.registered(fixture.product().id(), commandId, 5));
            assertMessages(event("DELTA_REGISTER", "COMMITTED"));
            assertThat(storedCommandState(fixture.product().id(), commandId)).isEqualTo("REGISTERED");
            status.setRollbackOnly();
            return id;
        });

        assertThat(independentlyVisibleUserCount(ambientUserId)).isZero();
        assertThat(storedCommandState(fixture.product().id(), commandId)).isEqualTo("REGISTERED");
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(10L);
        assertMessages(event("DELTA_REGISTER", "COMMITTED"),
                event("USER_CREATE", "ROLLED_BACK"));
        assertNoLoggedIdentifiers(ambientUserId, fixture.actorId(), fixture.product().id(), commandId);
    }

    @Test
    void requiresNewApplicationAndQuantityRemainCommittedDespiteAmbientRollback() {
        ProductFixture fixture = productFixture();
        UUID commandId = UUID.randomUUID();
        products.registerManualQuantityDelta(fixture.product().id(), commandId, 5, fixture.actorId());
        capture.list.clear();

        UUID ambientUserId = transaction.execute(status -> {
            UUID id = users.createUser().id();
            assertThat(products.applyManualQuantityDelta(fixture.product().id(), commandId,
                    5, fixture.actorId()))
                    .isEqualTo(ManualQuantityDeltaResult.applied(fixture.product().id(), commandId, 5, 15));
            assertMessages(event("DELTA_APPLY_OR_REPLAY", "COMMITTED"));
            status.setRollbackOnly();
            return id;
        });

        assertThat(independentlyVisibleUserCount(ambientUserId)).isZero();
        assertThat(storedCommandState(fixture.product().id(), commandId)).isEqualTo("APPLIED");
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(15L);
        assertMessages(event("DELTA_APPLY_OR_REPLAY", "COMMITTED"),
                event("USER_CREATE", "ROLLED_BACK"));
        assertNoLoggedIdentifiers(ambientUserId, fixture.actorId(), fixture.product().id(), commandId);
    }

    @Test
    void terminalReplayReportsTheOperationWithoutClaimingAnotherQuantityMutation() {
        ProductFixture fixture = productFixture();
        UUID originalCommand = UUID.randomUUID();
        products.registerManualQuantityDelta(fixture.product().id(), originalCommand, 5, fixture.actorId());
        ManualQuantityDeltaResult original = products.applyManualQuantityDelta(
                fixture.product().id(), originalCommand, 5, fixture.actorId());
        UUID laterCommand = UUID.randomUUID();
        products.registerManualQuantityDelta(fixture.product().id(), laterCommand, 2, fixture.actorId());
        products.applyManualQuantityDelta(fixture.product().id(), laterCommand, 2, fixture.actorId());
        capture.list.clear();

        assertThat(products.applyManualQuantityDelta(fixture.product().id(), originalCommand,
                5, fixture.actorId())).isEqualTo(original);

        assertThat(original.resultingAvailableQuantity()).isEqualTo(15L);
        assertThat(storedQuantity(fixture.product().id())).isEqualTo(17L);
        assertThat(storedCommandState(fixture.product().id(), originalCommand)).isEqualTo("APPLIED");
        assertMessages(event("DELTA_APPLY_OR_REPLAY", "COMMITTED"));
        assertNoLoggedIdentifiers(fixture.actorId(), fixture.product().id(), originalCommand, laterCommand);
    }

    private ProductFixture productFixture() {
        UUID actorId = users.createUser().id();
        UUID workspaceId = workspaces.createUserOwnedWorkspace(actorId).id();
        return new ProductFixture(actorId, products.createReadyMadeProduct(workspaceId, actorId, 10));
    }

    private int independentlyVisibleUserCount(UUID userId) {
        // A directly acquired connection observes a different physical transaction,
        // rather than reusing the transaction-bound JdbcTemplate connection.
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT count(*) FROM users WHERE id = ?")) {
            statement.setQueryTimeout(10);
            statement.setObject(1, userId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
        catch (SQLException failure) {
            throw new AssertionError("Independent database visibility check failed");
        }
    }

    private int profileCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id = ?",
                Integer.class, userId);
    }

    private long storedQuantity(UUID productId) {
        return jdbc.queryForObject("SELECT available_quantity FROM ready_made_products WHERE id = ?",
                Long.class, productId);
    }

    private String storedCommandState(UUID productId, UUID commandId) {
        return jdbc.queryForObject("SELECT state FROM ready_made_product_manual_quantity_delta_commands "
                + "WHERE product_id = ? AND command_id = ?", String.class, productId, commandId);
    }

    private List<String> messages() {
        return capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertMessages(String... expected) {
        // Exact list equality preserves order and count without rendering captured
        // messages, which could contain sensitive values if the boundary regresses.
        assertThat(messages().equals(List.of(expected)))
                .as("Exact diagnostic message sequence matches the closed vocabulary").isTrue();
    }

    private static String event(String operation, String completion) {
        return "event=DOMAIN_TRANSACTION operation=" + operation + " completion=" + completion;
    }

    private void assertNoLoggedIdentifiers(UUID... identifiers) {
        for (UUID identifier : identifiers) {
            assertThat(messages().stream().anyMatch(message -> message.contains(identifier.toString()))).isFalse();
        }
        assertThat(capture.list.stream().anyMatch(record -> record.getThrowableProxy() != null)).isFalse();
    }

    private record ProductFixture(UUID actorId, ReadyMadeProduct product) { }
}
