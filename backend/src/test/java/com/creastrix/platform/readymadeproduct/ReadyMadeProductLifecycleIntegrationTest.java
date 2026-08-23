package com.creastrix.platform.readymadeproduct;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import java.util.concurrent.atomic.AtomicReference;

import com.creastrix.platform.readymadeproduct.application.ReadyMadeProductService;
import com.creastrix.platform.readymadeproduct.domain.InvalidReadyMadeProductStatusTransitionException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductActorNotActiveException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductActorNotAuthorizedException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductNotFoundException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserNotFoundException;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.Workspace;
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
 * Integration coverage for the bounded Ready-Made Product lifecycle path.
 *
 * <p>Application authorization and concurrency behavior are exercised through
 * the service. Raw SQL tests separately prove that PostgreSQL V7 owns only the
 * structural transition set and does not claim actor authorization.
 */
@SpringBootTest
@Testcontainers
class ReadyMadeProductLifecycleIntegrationTest {

    private static final String CHECK_VIOLATION_SQL_STATE = "23514";
    private static final String LIFECYCLE_TRIGGER =
            "ready_made_products_enforce_status_transition";

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
    private UserService userService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------
    // Supported application path
    // ------------------------------------------------------------------

    @Test
    void activeAdminArchivesAndActivatesWhileEveryOtherValueRemainsUnchanged() {
        ProductFixture fixture = productOwnedByActiveAdmin(17L);

        ReadyMadeProduct archived = readyMadeProductService.archiveReadyMadeProduct(
                fixture.product().id(), fixture.actor().id());
        ReadyMadeProduct activated = readyMadeProductService.activateReadyMadeProduct(
                fixture.product().id(), fixture.actor().id());

        assertOnlyStatusChanged(fixture.product(), archived, ReadyMadeProductStatus.ARCHIVED);
        assertOnlyStatusChanged(fixture.product(), activated, ReadyMadeProductStatus.ACTIVE);
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void activeEditorWithExactGrantArchivesAndActivates() {
        ProductFixture fixture = productOwnedByActiveAdmin(3L);
        User editor = activeUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", "ACTIVE");
        addScope(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS");

        assertThat(readyMadeProductService.archiveReadyMadeProduct(
                fixture.product().id(), editor.id()).status())
                .isEqualTo(ReadyMadeProductStatus.ARCHIVED);
        assertThat(readyMadeProductService.activateReadyMadeProduct(
                fixture.product().id(), editor.id()).status())
                .isEqualTo(ReadyMadeProductStatus.ACTIVE);
    }

    @Test
    void activeEditorWithoutExactGrantIsRejected() {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        User editor = activeUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", "ACTIVE");

        assertNotAuthorized(fixture.product(), editor, () ->
                readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), editor.id()));
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void viewerIsRejectedEvenWithExactGrant() {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        User viewer = activeUser();
        addMembership(fixture.workspace().id(), viewer.id(), "VIEWER", "ACTIVE");
        addScope(fixture.workspace().id(), viewer.id(), "READY_MADE_PRODUCTS");

        assertNotAuthorized(fixture.product(), viewer, () ->
                readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), viewer.id()));
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void inactiveActorIsRejectedWithActualStatus() {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        userService.changeStatus(fixture.actor().id(), UserStatus.SUSPENDED);

        assertThatExceptionOfType(ReadyMadeProductActorNotActiveException.class)
                .isThrownBy(() -> readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), fixture.actor().id()))
                .satisfies(failure -> {
                    assertThat(failure.actorUserId()).isEqualTo(fixture.actor().id());
                    assertThat(failure.actualStatus()).isEqualTo(UserStatus.SUSPENDED);
                });
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @ParameterizedTest(name = "Membership status {0} is not actionable")
    @CsvSource({"INVITED", "SUSPENDED"})
    void inactiveMembershipIsRejected(String membershipStatus) {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        User editor = activeUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", membershipStatus);
        addScope(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS");

        assertNotAuthorized(fixture.product(), editor, () ->
                readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), editor.id()));
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void actorWithoutMembershipIsRejected() {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        User outsider = activeUser();

        assertNotAuthorized(fixture.product(), outsider, () ->
                readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), outsider.id()));
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void wrongScopesDoNotSubstituteForReadyMadeProducts() {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        User editor = activeUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", "ACTIVE");
        addScope(fixture.workspace().id(), editor.id(), "PROJECTS");
        addScope(fixture.workspace().id(), editor.id(), "LISTINGS");

        assertNotAuthorized(fixture.product(), editor, () ->
                readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), editor.id()));
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void missingActorUsesExistingUserNotFoundOutcome() {
        ProductFixture fixture = productOwnedByActiveAdmin(1L);
        UUID missingActor = UUID.randomUUID();

        assertThatExceptionOfType(UserNotFoundException.class)
                .isThrownBy(() -> readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), missingActor))
                .satisfies(failure -> assertThat(failure.userId()).isEqualTo(missingActor));
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void productNotFoundPrecedesActorAndAuthorizationChecks() {
        UUID missingProduct = UUID.randomUUID();
        UUID missingActor = UUID.randomUUID();

        assertThatExceptionOfType(ReadyMadeProductNotFoundException.class)
                .isThrownBy(() -> readyMadeProductService.archiveReadyMadeProduct(
                        missingProduct, missingActor))
                .satisfies(failure ->
                        assertThat(failure.readyMadeProductId()).isEqualTo(missingProduct));
    }

    @Test
    void sameTargetArchiveIsRejectedAndCommittedStateIsUnchanged() {
        ProductFixture fixture = productOwnedByActiveAdmin(9L);
        readyMadeProductService.archiveReadyMadeProduct(
                fixture.product().id(), fixture.actor().id());

        assertInvalidTransition(
                fixture.product().id(),
                ReadyMadeProductStatus.ARCHIVED,
                ReadyMadeProductStatus.ARCHIVED,
                () -> readyMadeProductService.archiveReadyMadeProduct(
                        fixture.product().id(), fixture.actor().id()));
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ARCHIVED);
    }

    @Test
    void sameTargetActivateIsRejectedAndCommittedStateIsUnchanged() {
        ProductFixture fixture = productOwnedByActiveAdmin(9L);

        assertInvalidTransition(
                fixture.product().id(),
                ReadyMadeProductStatus.ACTIVE,
                ReadyMadeProductStatus.ACTIVE,
                () -> readyMadeProductService.activateReadyMadeProduct(
                        fixture.product().id(), fixture.actor().id()));
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ACTIVE);
    }

    @Test
    void publicLifecycleApiUsesBoundedVerbsAndNeverAcceptsStatus() {
        List<Method> publicMethods = Arrays.stream(ReadyMadeProductService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(publicMethods)
                .filteredOn(method -> method.getName().equals("archiveReadyMadeProduct")
                        || method.getName().equals("activateReadyMadeProduct"))
                .extracting(Method::getParameterTypes)
                .containsExactlyInAnyOrder(
                        new Class<?>[] {UUID.class, UUID.class},
                        new Class<?>[] {UUID.class, UUID.class});
        assertThat(publicMethods)
                .noneMatch(method -> Arrays.asList(method.getParameterTypes())
                        .contains(ReadyMadeProductStatus.class));
    }

    // ------------------------------------------------------------------
    // Raw SQL structural boundary and OID-bound metadata
    // ------------------------------------------------------------------

    @Test
    void rawSqlAllowsExactlyBothStructurallyValidDirections() {
        ProductFixture fixture = productOwnedByActiveAdmin(4L);

        jdbcTemplate.update(
                "UPDATE ready_made_products SET status = 'ARCHIVED' WHERE id = ?",
                fixture.product().id());
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ARCHIVED");

        jdbcTemplate.update(
                "UPDATE ready_made_products SET status = 'ACTIVE' WHERE id = ?",
                fixture.product().id());
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @ParameterizedTest(name = "raw same-state {0} is rejected")
    @CsvSource({"ACTIVE", "ARCHIVED"})
    void rawSqlRejectsBothSameStateUpdates(String state) {
        ProductFixture fixture = productOwnedByActiveAdmin(2L);
        if (state.equals("ARCHIVED")) {
            jdbcTemplate.update(
                    "UPDATE ready_made_products SET status = 'ARCHIVED' WHERE id = ?",
                    fixture.product().id());
        }

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET status = ? WHERE id = ?",
                state, fixture.product().id()))
                .satisfies(failure -> assertStructuralTransitionViolation(
                        failure, state, state));
        assertThat(storedStatus(fixture.product().id())).isEqualTo(state);
    }

    @Test
    void rawSqlRejectsUnknownStatusAndClosedCheckRemainsExact() {
        ProductFixture fixture = productOwnedByActiveAdmin(2L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ready_made_products SET status = 'PUBLISHED' WHERE id = ?",
                fixture.product().id()))
                .satisfies(failure -> assertStructuralTransitionViolation(
                        failure, "ACTIVE", "PUBLISHED"));
        assertThat(constraintDefinition("ready_made_products_status_allowed")).isEqualTo(
                "CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'ARCHIVED'::text])))");
        assertThat(storedStatus(fixture.product().id())).isEqualTo("ACTIVE");
    }

    @Test
    void lifecycleTriggerIsBoundToExactPublicRelationAndStatusColumn() {
        Map<String, Object> trigger = lifecycleTriggerMetadata();

        assertThat(trigger.get("relation_oid")).isEqualTo(readyMadeProductsRelationOid());
        assertThat(trigger.get("schema_name")).isEqualTo("public");
        assertThat(trigger.get("relation_name")).isEqualTo("ready_made_products");
        assertThat(trigger.get("trigger_name")).isEqualTo(LIFECYCLE_TRIGGER);
        assertThat(trigger.get("function_schema")).isEqualTo("public");
        assertThat(trigger.get("function_name")).isEqualTo(LIFECYCLE_TRIGGER);
        assertThat(trigger.get("enabled")).isEqualTo("O");
        assertThat(trigger.get("is_constraint")).isEqualTo(false);
        assertThat(String.valueOf(trigger.get("definition")))
                .contains("BEFORE UPDATE OF status ON public.ready_made_products")
                .contains("FOR EACH ROW")
                .contains("EXECUTE FUNCTION ready_made_products_enforce_status_transition()");
        assertThat(triggerColumns(trigger.get("trigger_oid"))).containsExactly("status");

        String source = functionSource(trigger.get("function_oid"));
        assertThat(source)
                .contains("OLD.status = 'ACTIVE'")
                .contains("NEW.status = 'ARCHIVED'")
                .contains("OLD.status = 'ARCHIVED'")
                .contains("NEW.status = 'ACTIVE'")
                .contains("ERRCODE = 'check_violation'")
                .doesNotContain("actor")
                .doesNotContain("workspace_memberships")
                .doesNotContain("users")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void lifecycleMetadataIgnoresSameNamedDecoyObjects() {
        String decoySchema = "decoy_" + UUID.randomUUID().toString().replace("-", "");
        Object expectedTriggerOid = lifecycleTriggerMetadata().get("trigger_oid");
        Object expectedFunctionOid = lifecycleTriggerMetadata().get("function_oid");
        try {
            jdbcTemplate.execute("CREATE SCHEMA " + decoySchema);
            jdbcTemplate.execute("CREATE TABLE " + decoySchema
                    + ".ready_made_products (id uuid NOT NULL, status text NOT NULL)");
            jdbcTemplate.execute("CREATE FUNCTION " + decoySchema
                    + ".ready_made_products_enforce_status_transition() RETURNS trigger AS $$ "
                    + "BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql");
            jdbcTemplate.execute("CREATE TRIGGER ready_made_products_enforce_status_transition "
                    + "BEFORE UPDATE OF status ON " + decoySchema + ".ready_made_products "
                    + "FOR EACH ROW EXECUTE FUNCTION " + decoySchema
                    + ".ready_made_products_enforce_status_transition()");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal",
                    Integer.class, LIFECYCLE_TRIGGER)).isEqualTo(2);
            Map<String, Object> actual = lifecycleTriggerMetadata();
            assertThat(actual.get("trigger_oid")).isEqualTo(expectedTriggerOid);
            assertThat(actual.get("function_oid")).isEqualTo(expectedFunctionOid);
            assertThat(triggerColumns(actual.get("trigger_oid"))).containsExactly("status");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + decoySchema + " CASCADE");
        }
    }

    // ------------------------------------------------------------------
    // Deterministic lifecycle races (no sleep, bounded exact PID evidence)
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    void userRevocationCommittingFirstRejectsTransition() throws Exception {
        ProductFixture fixture = productOwnedByActiveAdmin(5L);

        MutationFirstOutcome outcome = runMutationCommitsFirst(
                fixture.product(), fixture.actor(),
                () -> jdbcTemplate.update(
                        "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                        fixture.actor().id()));

        assertThat(outcome.waitObserved()).isTrue();
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.transitionFailure())
                .isInstanceOf(ReadyMadeProductActorNotActiveException.class);
        assertThat(userService.findUser(fixture.actor().id()).status())
                .isEqualTo(UserStatus.SUSPENDED);
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ACTIVE);
    }

    @Test
    @Timeout(120)
    void transitionCommittingFirstSurvivesLaterUserRevocation() throws Exception {
        ProductFixture fixture = productOwnedByActiveAdmin(5L);

        TransitionFirstOutcome outcome = runTransitionCommitsFirst(
                fixture.product(), fixture.actor(),
                () -> jdbcTemplate.update(
                        "UPDATE users SET status = 'SUSPENDED' WHERE id = ?",
                        fixture.actor().id()));

        assertSuccessfulTransitionFirst(outcome);
        assertThat(userService.findUser(fixture.actor().id()).status())
                .isEqualTo(UserStatus.SUSPENDED);
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ARCHIVED);
    }

    @Test
    @Timeout(120)
    void editorScopeDeletionCommittingFirstRejectsTransition() throws Exception {
        ProductFixture fixture = productOwnedByActiveAdmin(5L);
        User editor = activeUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", "ACTIVE");
        addScope(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS");

        MutationFirstOutcome outcome = runMutationCommitsFirst(
                fixture.product(), editor,
                () -> deleteReadyMadeProductsScope(fixture.workspace().id(), editor.id()));

        assertThat(outcome.waitObserved()).isTrue();
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.transitionFailure())
                .isInstanceOf(ReadyMadeProductActorNotAuthorizedException.class);
        assertThat(scopeCount(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS"))
                .isZero();
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ACTIVE);
    }

    @Test
    @Timeout(120)
    void transitionCommittingFirstSurvivesLaterEditorScopeDeletion() throws Exception {
        ProductFixture fixture = productOwnedByActiveAdmin(5L);
        User editor = activeUser();
        addMembership(fixture.workspace().id(), editor.id(), "EDITOR", "ACTIVE");
        addScope(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS");

        TransitionFirstOutcome outcome = runTransitionCommitsFirst(
                fixture.product(), editor,
                () -> deleteReadyMadeProductsScope(fixture.workspace().id(), editor.id()));

        assertSuccessfulTransitionFirst(outcome);
        assertThat(scopeCount(fixture.workspace().id(), editor.id(), "READY_MADE_PRODUCTS"))
                .isZero();
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ARCHIVED);
    }

    @Test
    @Timeout(120)
    void duplicateSameTargetTransitionHasExactlyOneSuccess() throws Exception {
        ProductFixture fixture = productOwnedByActiveAdmin(5L);
        User secondAdmin = activeUser();
        addMembership(fixture.workspace().id(), secondAdmin.id(), "ADMIN", "ACTIVE");

        DuplicateTransitionOutcome outcome = runDuplicateArchiveRace(
                fixture.product(), fixture.actor(), secondAdmin);

        assertThat(outcome.waitObserved()).isTrue();
        assertThat(outcome.firstFailure()).isNull();
        assertThat(outcome.firstResult()).isNotNull();
        assertThat(outcome.firstResult().status()).isEqualTo(ReadyMadeProductStatus.ARCHIVED);
        assertThat(outcome.secondFailure())
                .isInstanceOf(InvalidReadyMadeProductStatusTransitionException.class);
        assertThat(outcome.secondResult()).isNull();
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ARCHIVED);
    }

    @Test
    @Timeout(120)
    void oppositeDirectionTransitionsSerializeOnProductLock() throws Exception {
        ProductFixture fixture = productOwnedByActiveAdmin(5L);
        User activatingAdmin = activeUser();
        addMembership(fixture.workspace().id(), activatingAdmin.id(), "ADMIN", "ACTIVE");

        OppositeTransitionOutcome outcome = runOppositeDirectionTransitionRace(
                fixture.product(), fixture.actor(), activatingAdmin);

        assertThat(outcome.archivePid()).isNotNull();
        assertThat(outcome.activatePid()).isNotNull().isNotEqualTo(outcome.archivePid());
        assertThat(outcome.activateFailure())
                .as("activation must wait for the archive Product lock; exact wait observed=%s",
                        outcome.waitObserved())
                .isNull();
        assertThat(outcome.waitObserved()).isTrue();
        assertThat(outcome.archiveFailure()).isNull();
        assertThat(outcome.archiveResult()).isNotNull();
        assertThat(outcome.activateResult()).isNotNull();
        assertOnlyStatusChanged(
                fixture.product(), outcome.archiveResult(), ReadyMadeProductStatus.ARCHIVED);
        assertOnlyStatusChanged(
                fixture.product(), outcome.activateResult(), ReadyMadeProductStatus.ACTIVE);
        assertStoredProduct(fixture.product(), ReadyMadeProductStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // Concurrency orchestration
    // ------------------------------------------------------------------

    private MutationFirstOutcome runMutationCommitsFirst(
            ReadyMadeProduct product, User actor, Runnable mutation) throws Exception {
        CountDownLatch mutationExecuted = new CountDownLatch(1);
        CountDownLatch transitionAttempting = new CountDownLatch(1);
        CountDownLatch mutationPidPublished = new CountDownLatch(1);
        CountDownLatch transitionPidPublished = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        AtomicReference<Integer> mutationPid = new AtomicReference<>();
        AtomicReference<Integer> transitionPid = new AtomicReference<>();

        Callable<Exception> mutationWorker = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    mutationPid.set(currentTransactionBackendPid());
                    mutationPidPublished.countDown();
                    mutation.run();
                    mutationExecuted.countDown();
                    awaitLatch(releaseMutation, "the blocked lifecycle transition");
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                mutationPidPublished.countDown();
                mutationExecuted.countDown();
            }
        };

        Callable<Exception> transitionWorker = () -> {
            try {
                awaitLatch(mutationExecuted, "the authorization mutation");
                transactionTemplate.executeWithoutResult(status -> {
                    transitionPid.set(currentTransactionBackendPid());
                    transitionPidPublished.countDown();
                    transitionAttempting.countDown();
                    readyMadeProductService.archiveReadyMadeProduct(product.id(), actor.id());
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                transitionPidPublished.countDown();
                transitionAttempting.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception mutationFailure;
        Exception transitionFailure;
        boolean waitObserved;
        try {
            Future<Exception> mutationFuture = executor.submit(mutationWorker);
            Future<Exception> transitionFuture = executor.submit(transitionWorker);
            awaitLatch(transitionAttempting, "the lifecycle transition attempt");
            awaitLatch(mutationPidPublished, "the mutation backend PID");
            awaitLatch(transitionPidPublished, "the transition backend PID");
            waitObserved = mutationPid.get() != null
                    && transitionPid.get() != null
                    && awaitExactBlockedBackend(transitionPid.get(), mutationPid.get());
            releaseMutation.countDown();
            mutationFailure = mutationFuture.get(60, TimeUnit.SECONDS);
            transitionFailure = transitionFuture.get(60, TimeUnit.SECONDS);
        } finally {
            releaseMutation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        return new MutationFirstOutcome(mutationFailure, transitionFailure, waitObserved);
    }

    private TransitionFirstOutcome runTransitionCommitsFirst(
            ReadyMadeProduct product, User actor, Runnable mutation) throws Exception {
        CountDownLatch transitionCompleted = new CountDownLatch(1);
        CountDownLatch mutationAttempting = new CountDownLatch(1);
        CountDownLatch transitionPidPublished = new CountDownLatch(1);
        CountDownLatch mutationPidPublished = new CountDownLatch(1);
        CountDownLatch releaseTransition = new CountDownLatch(1);
        AtomicReference<Integer> transitionPid = new AtomicReference<>();
        AtomicReference<Integer> mutationPid = new AtomicReference<>();
        AtomicReference<ReadyMadeProduct> result = new AtomicReference<>();

        Callable<Exception> transitionWorker = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    transitionPid.set(currentTransactionBackendPid());
                    transitionPidPublished.countDown();
                    result.set(readyMadeProductService.archiveReadyMadeProduct(
                            product.id(), actor.id()));
                    transitionCompleted.countDown();
                    awaitLatch(releaseTransition, "the blocked authorization mutation");
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                transitionPidPublished.countDown();
                transitionCompleted.countDown();
            }
        };

        Callable<Exception> mutationWorker = () -> {
            try {
                awaitLatch(transitionCompleted, "the completed lifecycle update");
                transactionTemplate.executeWithoutResult(status -> {
                    mutationPid.set(currentTransactionBackendPid());
                    mutationPidPublished.countDown();
                    mutationAttempting.countDown();
                    mutation.run();
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                mutationPidPublished.countDown();
                mutationAttempting.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception transitionFailure;
        Exception mutationFailure;
        boolean waitObserved;
        try {
            Future<Exception> transitionFuture = executor.submit(transitionWorker);
            Future<Exception> mutationFuture = executor.submit(mutationWorker);
            awaitLatch(mutationAttempting, "the authorization mutation attempt");
            awaitLatch(transitionPidPublished, "the transition backend PID");
            awaitLatch(mutationPidPublished, "the mutation backend PID");
            waitObserved = transitionPid.get() != null
                    && mutationPid.get() != null
                    && awaitExactBlockedBackend(mutationPid.get(), transitionPid.get());
            releaseTransition.countDown();
            transitionFailure = transitionFuture.get(60, TimeUnit.SECONDS);
            mutationFailure = mutationFuture.get(60, TimeUnit.SECONDS);
        } finally {
            releaseTransition.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        return new TransitionFirstOutcome(
                transitionFailure, mutationFailure, result.get(), waitObserved);
    }

    private DuplicateTransitionOutcome runDuplicateArchiveRace(
            ReadyMadeProduct product, User firstActor, User secondActor) throws Exception {
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        CountDownLatch firstPidPublished = new CountDownLatch(1);
        CountDownLatch secondPidPublished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Integer> firstPid = new AtomicReference<>();
        AtomicReference<Integer> secondPid = new AtomicReference<>();
        AtomicReference<ReadyMadeProduct> firstResult = new AtomicReference<>();
        AtomicReference<ReadyMadeProduct> secondResult = new AtomicReference<>();

        Callable<Exception> firstWorker = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    firstPid.set(currentTransactionBackendPid());
                    firstPidPublished.countDown();
                    firstResult.set(readyMadeProductService.archiveReadyMadeProduct(
                            product.id(), firstActor.id()));
                    firstCompleted.countDown();
                    awaitLatch(releaseFirst, "the duplicate lifecycle command");
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                firstPidPublished.countDown();
                firstCompleted.countDown();
            }
        };

        Callable<Exception> secondWorker = () -> {
            try {
                awaitLatch(firstCompleted, "the first lifecycle update");
                transactionTemplate.executeWithoutResult(status -> {
                    secondPid.set(currentTransactionBackendPid());
                    secondPidPublished.countDown();
                    secondAttempting.countDown();
                    secondResult.set(readyMadeProductService.archiveReadyMadeProduct(
                            product.id(), secondActor.id()));
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                secondPidPublished.countDown();
                secondAttempting.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception firstFailure;
        Exception secondFailure;
        boolean waitObserved;
        try {
            Future<Exception> firstFuture = executor.submit(firstWorker);
            Future<Exception> secondFuture = executor.submit(secondWorker);
            awaitLatch(secondAttempting, "the duplicate lifecycle attempt");
            awaitLatch(firstPidPublished, "the first transition backend PID");
            awaitLatch(secondPidPublished, "the second transition backend PID");
            waitObserved = firstPid.get() != null
                    && secondPid.get() != null
                    && awaitExactBlockedBackend(secondPid.get(), firstPid.get());
            releaseFirst.countDown();
            firstFailure = firstFuture.get(60, TimeUnit.SECONDS);
            secondFailure = secondFuture.get(60, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        return new DuplicateTransitionOutcome(
                firstFailure,
                secondFailure,
                firstResult.get(),
                secondResult.get(),
                waitObserved);
    }

    private OppositeTransitionOutcome runOppositeDirectionTransitionRace(
            ReadyMadeProduct product, User archivingActor, User activatingActor) throws Exception {
        CountDownLatch archiveCompleted = new CountDownLatch(1);
        CountDownLatch activateAttempting = new CountDownLatch(1);
        CountDownLatch archivePidPublished = new CountDownLatch(1);
        CountDownLatch activatePidPublished = new CountDownLatch(1);
        CountDownLatch activateFinished = new CountDownLatch(1);
        CountDownLatch releaseArchive = new CountDownLatch(1);
        AtomicReference<Integer> archivePid = new AtomicReference<>();
        AtomicReference<Integer> activatePid = new AtomicReference<>();
        AtomicReference<ReadyMadeProduct> archiveResult = new AtomicReference<>();
        AtomicReference<ReadyMadeProduct> activateResult = new AtomicReference<>();

        Callable<Exception> archiveWorker = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    archivePid.set(currentTransactionBackendPid());
                    archivePidPublished.countDown();
                    archiveResult.set(readyMadeProductService.archiveReadyMadeProduct(
                            product.id(), archivingActor.id()));
                    archiveCompleted.countDown();
                    awaitLatch(releaseArchive, "the opposite-direction activation");
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                archivePidPublished.countDown();
                archiveCompleted.countDown();
            }
        };

        Callable<Exception> activateWorker = () -> {
            try {
                awaitLatch(archiveCompleted, "the uncommitted archive result");
                transactionTemplate.executeWithoutResult(status -> {
                    activatePid.set(currentTransactionBackendPid());
                    activatePidPublished.countDown();
                    activateAttempting.countDown();
                    activateResult.set(readyMadeProductService.activateReadyMadeProduct(
                            product.id(), activatingActor.id()));
                });
                return null;
            } catch (Exception failure) {
                return failure;
            } finally {
                activatePidPublished.countDown();
                activateAttempting.countDown();
                activateFinished.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Exception archiveFailure;
        Exception activateFailure;
        boolean waitObserved;
        try {
            Future<Exception> archiveFuture = executor.submit(archiveWorker);
            Future<Exception> activateFuture = executor.submit(activateWorker);
            awaitLatch(activateAttempting, "the opposite-direction activation attempt");
            awaitLatch(archivePidPublished, "the archive backend PID");
            awaitLatch(activatePidPublished, "the activate backend PID");
            waitObserved = archivePid.get() != null
                    && activatePid.get() != null
                    && awaitExactBlockedBackendOrCompletion(
                            activatePid.get(), archivePid.get(), activateFinished);
            releaseArchive.countDown();
            archiveFailure = archiveFuture.get(60, TimeUnit.SECONDS);
            activateFailure = activateFuture.get(60, TimeUnit.SECONDS);
        } finally {
            releaseArchive.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        return new OppositeTransitionOutcome(
                archiveFailure,
                activateFailure,
                archiveResult.get(),
                activateResult.get(),
                archivePid.get(),
                activatePid.get(),
                waitObserved);
    }

    private boolean awaitExactBlockedBackendOrCompletion(
            int waiterPid, int blockerPid, CountDownLatch waiterFinished) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (waiterFinished.getCount() == 0) {
                return false;
            }
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

    private Integer currentTransactionBackendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void awaitLatch(CountDownLatch latch, String what) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + what);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // ------------------------------------------------------------------
    // Assertions and persistence helpers
    // ------------------------------------------------------------------

    private ProductFixture productOwnedByActiveAdmin(long quantity) {
        User actor = activeUser();
        Workspace workspace = workspaceService.createUserOwnedWorkspace(actor.id());
        ReadyMadeProduct product = readyMadeProductService.createReadyMadeProduct(
                workspace.id(), actor.id(), quantity);
        return new ProductFixture(actor, workspace, product);
    }

    private User activeUser() {
        return userService.createUser();
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

    private void deleteReadyMadeProductsScope(UUID workspaceId, UUID userId) {
        jdbcTemplate.update(
                "DELETE FROM workspace_membership_scopes "
                        + "WHERE workspace_id = ? AND user_id = ? "
                        + "AND scope = 'READY_MADE_PRODUCTS'",
                workspaceId, userId);
    }

    private Integer scopeCount(UUID workspaceId, UUID userId, String scope) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_membership_scopes "
                        + "WHERE workspace_id = ? AND user_id = ? AND scope = ?",
                Integer.class, workspaceId, userId, scope);
    }

    private String storedStatus(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ready_made_products WHERE id = ?",
                String.class, productId);
    }

    private void assertOnlyStatusChanged(
            ReadyMadeProduct original,
            ReadyMadeProduct actual,
            ReadyMadeProductStatus expectedStatus) {
        assertThat(actual.id()).isEqualTo(original.id());
        assertThat(actual.workspaceId()).isEqualTo(original.workspaceId());
        assertThat(actual.createdByUserId()).isEqualTo(original.createdByUserId());
        assertThat(actual.availableQuantity()).isEqualTo(original.availableQuantity());
        assertThat(actual.status()).isEqualTo(expectedStatus);
    }

    private void assertStoredProduct(
            ReadyMadeProduct original, ReadyMadeProductStatus expectedStatus) {
        ReadyMadeProduct stored = readyMadeProductService.findReadyMadeProduct(original.id());
        assertOnlyStatusChanged(original, stored, expectedStatus);
    }

    private void assertNotAuthorized(
            ReadyMadeProduct product, User actor, Runnable command) {
        assertThatExceptionOfType(ReadyMadeProductActorNotAuthorizedException.class)
                .isThrownBy(command::run)
                .satisfies(failure -> {
                    assertThat(failure.readyMadeProductId()).isEqualTo(product.id());
                    assertThat(failure.workspaceId()).isEqualTo(product.workspaceId());
                    assertThat(failure.actorUserId()).isEqualTo(actor.id());
                });
    }

    private void assertInvalidTransition(
            UUID productId,
            ReadyMadeProductStatus currentStatus,
            ReadyMadeProductStatus targetStatus,
            Runnable command) {
        assertThatExceptionOfType(InvalidReadyMadeProductStatusTransitionException.class)
                .isThrownBy(command::run)
                .satisfies(failure -> {
                    assertThat(failure.readyMadeProductId()).isEqualTo(productId);
                    assertThat(failure.currentStatus()).isEqualTo(currentStatus);
                    assertThat(failure.targetStatus()).isEqualTo(targetStatus);
                });
    }

    private void assertSuccessfulTransitionFirst(TransitionFirstOutcome outcome) {
        assertThat(outcome.waitObserved()).isTrue();
        assertThat(outcome.transitionFailure()).isNull();
        assertThat(outcome.mutationFailure()).isNull();
        assertThat(outcome.transitionResult()).isNotNull();
        assertThat(outcome.transitionResult().status()).isEqualTo(ReadyMadeProductStatus.ARCHIVED);
    }

    private void assertStructuralTransitionViolation(
            Throwable failure, String currentStatus, String targetStatus) {
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure)
                .rootCause()
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("Unsupported Ready-Made Product status transition")
                .hasMessageContaining(currentStatus)
                .hasMessageContaining(targetStatus)
                .extracting(cause -> ((PSQLException) cause).getSQLState())
                .isEqualTo(CHECK_VIOLATION_SQL_STATE);
    }

    private Long readyMadeProductsRelationOid() {
        return jdbcTemplate.queryForObject(
                "SELECT c.oid::bigint FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relname = 'ready_made_products' "
                        + "AND c.relkind = 'r'",
                Long.class);
    }

    private Map<String, Object> lifecycleTriggerMetadata() {
        return jdbcTemplate.queryForMap(
                "SELECT t.oid::bigint AS trigger_oid, t.tgrelid::bigint AS relation_oid, "
                        + "t.tgfoid::bigint AS function_oid, n.nspname AS schema_name, "
                        + "c.relname AS relation_name, t.tgname AS trigger_name, "
                        + "pn.nspname AS function_schema, p.proname AS function_name, "
                        + "t.tgenabled::text AS enabled, t.tgconstraint <> 0 AS is_constraint, "
                        + "pg_get_triggerdef(t.oid) AS definition "
                        + "FROM pg_trigger t "
                        + "JOIN pg_class c ON c.oid = t.tgrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "JOIN pg_proc p ON p.oid = t.tgfoid "
                        + "JOIN pg_namespace pn ON pn.oid = p.pronamespace "
                        + "WHERE t.tgrelid = ? AND t.tgname = ? AND NOT t.tgisinternal",
                readyMadeProductsRelationOid(), LIFECYCLE_TRIGGER);
    }

    private List<String> triggerColumns(Object triggerOid) {
        return jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_trigger t "
                        + "JOIN pg_attribute a ON a.attrelid = t.tgrelid "
                        + "AND a.attnum = ANY (t.tgattr::smallint[]) "
                        + "WHERE t.oid = CAST(? AS oid) ORDER BY a.attnum",
                String.class, triggerOid);
    }

    private String functionSource(Object functionOid) {
        return jdbcTemplate.queryForObject(
                "SELECT prosrc FROM pg_proc WHERE oid = CAST(? AS oid)",
                String.class, functionOid);
    }

    private String constraintDefinition(String constraintName) {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = ? AND conname = ?",
                String.class, readyMadeProductsRelationOid(), constraintName);
    }

    private record ProductFixture(User actor, Workspace workspace, ReadyMadeProduct product) {
    }

    private record MutationFirstOutcome(
            Exception mutationFailure, Exception transitionFailure, boolean waitObserved) {
    }

    private record TransitionFirstOutcome(
            Exception transitionFailure,
            Exception mutationFailure,
            ReadyMadeProduct transitionResult,
            boolean waitObserved) {
    }

    private record DuplicateTransitionOutcome(
            Exception firstFailure,
            Exception secondFailure,
            ReadyMadeProduct firstResult,
            ReadyMadeProduct secondResult,
            boolean waitObserved) {
    }

    private record OppositeTransitionOutcome(
            Exception archiveFailure,
            Exception activateFailure,
            ReadyMadeProduct archiveResult,
            ReadyMadeProduct activateResult,
            Integer archivePid,
            Integer activatePid,
            boolean waitObserved) {
    }
}
