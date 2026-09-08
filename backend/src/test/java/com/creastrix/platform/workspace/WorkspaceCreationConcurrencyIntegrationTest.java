package com.creastrix.platform.workspace;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.creastrix.platform.CreastrixApplication;
import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.readymadeproduct.application.ReadyMadeProductService;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaCommandState;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaRejectionReason;
import com.creastrix.platform.readymadeproduct.domain.ManualQuantityDeltaResult;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductManualQuantityDeltaAccessException;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import com.creastrix.platform.readymadeproduct.persistence.JdbcReadyMadeProductRepository;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import com.creastrix.platform.workspace.domain.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Bounded public-service evidence for the three V10 creation-lock changes.
 * V9 RED uses its own real Boot application and disposable PostgreSQL; it does
 * not replace a migration function in the V10 database. No operation is retried.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=5000",
        "spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.data-source-properties.connectTimeout=5",
        "spring.datasource.hikari.data-source-properties.socketTimeout=50"
})
@Testcontainers
@Timeout(120)
class WorkspaceCreationConcurrencyIntegrationTest {

    private static final String FORCE_CREATION =
            "SET CONSTRAINTS workspaces_require_initial_foundation IMMEDIATE";
    private static final String COMMANDS =
            "public.ready_made_product_manual_quantity_delta_commands";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private UserService users;
    @Autowired private OrganizationService organizations;
    @Autowired private WorkspaceService workspaces;
    @Autowired private ReadyMadeProductService products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private DataSource source;
    @MockitoSpyBean private JdbcReadyMadeProductRepository productRepository;

    private final AtomicReference<ManualHook> selectedManualHook = new AtomicReference<>();

    enum Pair { SAME_USER, SAME_ORGANIZATION, DIFFERENT_USERS, DIFFERENT_ORGANIZATIONS,
        DIFFERENT_ORGANIZATIONS_COMMON_ACTOR, USER_ORGANIZATION_COMMON_ACTOR }

    @ParameterizedTest
    @EnumSource(value = Pair.class, names = {"SAME_USER", "SAME_ORGANIZATION"})
    void v9BaselineReproducesExactDeadlockAndAtomicLoserRollback(Pair pair) {
        try (PostgreSQLContainer baseline = new PostgreSQLContainer("postgres:18.4-alpine")) {
            baseline.start();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CreastrixApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.datasource.url=" + baseline.getJdbcUrl(),
                            "--spring.datasource.username=" + baseline.getUsername(),
                            "--spring.datasource.password=" + baseline.getPassword(),
                            "--spring.flyway.target=9",
                            "--spring.datasource.hikari.connection-timeout=5000",
                            "--spring.datasource.hikari.maximum-pool-size=8",
                            "--spring.datasource.hikari.data-source-properties.connectTimeout=5",
                            "--spring.datasource.hikari.data-source-properties.socketTimeout=50")) {
                Environment env = new Environment(context.getBean(JdbcTemplate.class),
                        context.getBean(TransactionTemplate.class), context.getBean(UserService.class),
                        context.getBean(OrganizationService.class), context.getBean(WorkspaceService.class));
                assertThat(env.jdbc.queryForObject("SHOW server_version_num", Integer.class)).isEqualTo(180004);
                assertThat(env.jdbc.queryForList("SELECT version FROM public.flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
                for (int repetition = 1; repetition <= 3; repetition++) {
                    proveTwoCreators(env, pair, true, repetition);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Pair.class)
    void v10OverlappingPublicCreationsCommitWithoutRetry(Pair pair) {
        assertV10();
        for (int repetition = 1; repetition <= 3; repetition++) {
            proveTwoCreators(environment(), pair, false, repetition);
        }
    }

    private void proveTwoCreators(Environment env, Pair pair, boolean red, int repetition) {
        CreationPair fixture = createPair(env, pair);
        try (Workers workers = new Workers()) {
            Worker<Workspace> first = workers.start(env, fixture.first, false, true);
            awaitBody(first);
            Worker<Workspace> second = workers.start(env, fixture.second, false, true);
            // Service bodies finish before either normal commit is released. An
            // early lock/failure is diagnosed rather than hidden behind a barrier.
            awaitBodyOrExactWait(env, second, first);
            assertThat(second.body.getCount()).as("deferred candidate completed second coherent call").isZero();
            assertThat(first.pid.get()).isNotEqualTo(second.pid.get());
            assertHeld(first);
            assertHeld(second);
            first.release.countDown();
            second.release.countDown();
            Outcome<Workspace> a = result(first);
            Outcome<Workspace> b = result(second);
            if (red) {
                List<Outcome<Workspace>> failures = List.of(a, b).stream()
                        .filter(value -> value.failure != null).toList();
                assertThat(failures).hasSize(1);
                assertSqlFailure(failures.getFirst().failure, "40P01", "deadlock detected");
                assertWorkspaceOutcome(env, first, fixture.firstActor, a.failure == null);
                assertWorkspaceOutcome(env, second, fixture.secondActor, b.failure == null);
                System.out.printf("V10_BASELINE_RED pair=%s repetition=%d pids=%s/%s SQLSTATE=40P01 "
                                + "commits=1 loserWorkspaceAndMembershipRows=0%n",
                        pair, repetition, first.pid.get(), second.pid.get());
            } else {
                assertSuccess(a);
                assertSuccess(b);
                assertThat(a.value.id()).isNotEqualTo(b.value.id());
                assertWorkspaceOutcome(env, first, fixture.firstActor, true);
                assertWorkspaceOutcome(env, second, fixture.secondActor, true);
                System.out.printf("V10_GREEN pair=%s repetition=%d pids=%s/%s commits=2 retries=0%n",
                        pair, repetition, first.pid.get(), second.pid.get());
            }
            assertExpectedOwner(first.value.get(), fixture.firstKind, fixture.firstOwner);
            assertExpectedOwner(second.value.get(), fixture.secondKind, fixture.secondOwner);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Pair.class, names = {"DIFFERENT_USERS", "DIFFERENT_ORGANIZATIONS"})
    void differentOwnersCommitSecondWhileFirstStillHoldsItsCreationLocks(Pair pair) {
        CreationPair fixture = createPair(environment(), pair);
        try (Workers workers = new Workers()) {
            Worker<Workspace> first = workers.start(environment(), fixture.first, true, true);
            awaitBody(first);
            Worker<Workspace> second = workers.start(environment(), fixture.second, true, false);
            assertSuccess(result(second));
            assertHeld(first);
            first.release.countDown();
            assertSuccess(result(first));
            assertWorkspaceOutcome(environment(), first, fixture.firstActor, true);
            assertWorkspaceOutcome(environment(), second, fixture.secondActor, true);
            assertExpectedOwner(first.value.get(), fixture.firstKind, fixture.firstOwner);
            assertExpectedOwner(second.value.get(), fixture.secondKind, fixture.secondOwner);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Pair.class, names = {"SAME_USER", "SAME_ORGANIZATION"})
    void forcedConstraintControlProvesExactCreationWait(Pair pair) {
        CreationPair fixture = createPair(environment(), pair);
        try (Workers workers = new Workers()) {
            Worker<Workspace> first = workers.start(environment(), fixture.first, true, true);
            awaitBody(first);
            Worker<Workspace> second = workers.start(environment(), fixture.second, true, false);
            awaitExactWait(environment(), second, first);
            first.release.countDown();
            assertSuccess(result(first));
            assertSuccess(result(second));
            assertWorkspaceOutcome(environment(), first, fixture.firstActor, true);
            assertWorkspaceOutcome(environment(), second, fixture.secondActor, true);
            assertExpectedOwner(first.value.get(), fixture.firstKind, fixture.firstOwner);
            assertExpectedOwner(second.value.get(), fixture.secondKind, fixture.secondOwner);
        }
    }

    @ParameterizedTest
    @CsvSource({"USER,false", "ORGANIZATION,false", "USER,true", "ORGANIZATION,true"})
    void queuedWriterDoesNotPreventBothAlreadyCoherentCreatorsFromCommitting(String kind, boolean writerBeforeSecond) {
        for (int repetition = 1; repetition <= 3; repetition++) {
            UUID firstActor = users.createUser().id();
            UUID secondActor = kind.equals("USER") ? firstActor : users.createUser().id();
            UUID organization = kind.equals("USER") ? null : organizations.createOrganization(firstActor).id();
            if (organization != null) {
                addOwner(organization, secondActor);
            }
            Supplier<Workspace> firstCall = creation(kind, organization, firstActor);
            Supplier<Workspace> secondCall = creation(kind, organization, secondActor);
            try (Workers workers = new Workers()) {
                Worker<Workspace> first = workers.start(environment(), firstCall, false, true);
                awaitBody(first);
                Worker<Workspace> second = writerBeforeSecond ? null
                        : workers.start(environment(), secondCall, false, true);
                if (second != null) {
                    awaitBodyOrExactWait(environment(), second, first);
                }
                Worker<?> writer = workers.start(environment(), () -> {
                    if (organization == null) {
                        return users.changeStatus(firstActor, UserStatus.SUSPENDED);
                    }
                    return jdbc.update("DELETE FROM public.organization_memberships "
                            + "WHERE organization_id=? AND user_id=?", organization, secondActor);
                }, false, false);
                if (writerBeforeSecond) {
                    awaitExactWait(environment(), writer, first);
                    second = workers.start(environment(), secondCall, false, true);
                    if (awaitBodyOrQueuedAdmissionWait(environment(), second, first, writer)) {
                        // A real early wait must not become a service-return
                        // barrier hostage. This branch is a queued-admission
                        // control, not the both-coherent RED/GREEN discriminator.
                        first.release.countDown();
                        second.release.countDown();
                        assertSuccess(result(first));
                        assertSuccess(result(writer));
                        assertSqlFailure(result(second).failure, "23514", organization == null
                                ? "requires an ACTIVE owner User" : "requires a creator who is an ACTIVE User");
                        assertWorkspaceOutcome(environment(), first, firstActor, true);
                        assertWorkspaceOutcome(environment(), second, secondActor, false);
                        assertThat(organization == null ? users.findUser(firstActor).status() == UserStatus.SUSPENDED
                                : ownerCount(organization, secondActor) == 0).isTrue();
                        System.out.printf("V10_QUEUED_ADMISSION kind=%s repetition=%d firstPID=%s secondPID=%s writerPID=%s "
                                        + "firstCommit=true earlyWaitProven=true secondOutcome=23514_ROLLBACK%n",
                                kind, repetition, first.pid.get(), second.pid.get(), writer.pid.get());
                        continue;
                    }
                }
                Worker<Workspace> currentlyBlocking = awaitOneOfTwoExactBlockers(environment(), writer, first, second);
                Worker<Workspace> remaining = currentlyBlocking == first ? second : first;
                // PostgreSQL can await MultiXact members one at a time. Prove
                // both exact waits sequentially, not by inventing an all-PID snapshot.
                currentlyBlocking.release.countDown();
                assertSuccess(result(currentlyBlocking));
                assertHeld(remaining);
                awaitExactWait(environment(), writer, remaining);
                remaining.release.countDown();
                assertSuccess(result(remaining));
                Outcome<?> writerResult = result(writer);
                if (organization == null) {
                    assertSuccess(writerResult);
                    assertThat(users.findUser(firstActor).status()).isEqualTo(UserStatus.SUSPENDED);
                } else {
                    assertSqlFailure(writerResult.failure, "23514", "must retain at least one User who is both");
                    assertThat(ownerCount(organization, secondActor)).isOne();
                }
                assertWorkspaceOutcome(environment(), first, firstActor, true);
                assertWorkspaceOutcome(environment(), second, secondActor, true);
                System.out.printf("V10_QUEUED_WRITER kind=%s writerBeforeSecond=%s repetition=%d creators=%s/%s writer=%s "
                                + "creatorCommits=2 writerOutcome=%s%n", kind, writerBeforeSecond, repetition, first.pid.get(),
                        second.pid.get(), writer.pid.get(), organization == null ? "COMMITTED" : "23514_ROLLBACK");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"USER,true", "USER,false", "ORGANIZATION,true", "ORGANIZATION,false"})
    void userStatusAndCreationRespectBothLockOrders(String kind, boolean mutationFirst) {
        UUID actor = users.createUser().id();
        UUID organization = kind.equals("USER") ? null : organizations.createOrganization(actor).id();
        Supplier<Workspace> create = creation(kind, organization, actor);
        try (Workers workers = new Workers()) {
            Worker<Workspace> creator;
            Worker<?> mutation;
            if (mutationFirst) {
                mutation = workers.start(environment(), () -> users.changeStatus(actor, UserStatus.SUSPENDED), false, true);
                awaitBody(mutation);
                creator = workers.start(environment(), create, false, false);
                awaitExactWait(environment(), creator, mutation);
                mutation.release.countDown();
                assertSuccess(result(mutation));
                assertSqlFailure(result(creator).failure, "23514", kind.equals("USER")
                        ? "requires an ACTIVE owner User" : "requires a creator who is an ACTIVE User");
                assertWorkspaceOutcome(environment(), creator, actor, false);
            } else {
                creator = workers.start(environment(), create, true, true);
                awaitBody(creator);
                mutation = workers.start(environment(), () -> users.changeStatus(actor, UserStatus.SUSPENDED), false, false);
                awaitExactWait(environment(), mutation, creator);
                creator.release.countDown();
                assertSuccess(result(creator));
                assertSuccess(result(mutation));
                assertWorkspaceOutcome(environment(), creator, actor, true);
            }
            assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.SUSPENDED);
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void ownerRemovalAndCreationRespectBothLockOrders(boolean mutationFirst) {
        UUID actor = users.createUser().id();
        UUID replacement = users.createUser().id();
        UUID organization = organizations.createOrganization(actor).id();
        addOwner(organization, replacement);
        Supplier<Integer> remove = () -> {
            int deleted = jdbc.update("DELETE FROM public.organization_memberships "
                    + "WHERE organization_id=? AND user_id=?", organization, actor);
            assertThat(deleted).isOne();
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            return deleted;
        };
        try (Workers workers = new Workers()) {
            Worker<Workspace> creator;
            Worker<Integer> removal;
            if (mutationFirst) {
                removal = workers.start(environment(), remove, false, true);
                awaitBody(removal);
                creator = workers.start(environment(), creation("ORGANIZATION", organization, actor), false, false);
                awaitExactWait(environment(), creator, removal);
                removal.release.countDown();
                assertSuccess(result(removal));
                assertSqlFailure(result(creator).failure, "23514", "requires a creator who is an ACTIVE User");
                assertWorkspaceOutcome(environment(), creator, actor, false);
                assertThat(ownerCount(organization, actor)).isZero();
            } else {
                creator = workers.start(environment(), creation("ORGANIZATION", organization, actor), true, true);
                awaitBody(creator);
                removal = workers.start(environment(), remove, false, false);
                awaitExactWait(environment(), removal, creator);
                creator.release.countDown();
                assertSuccess(result(creator));
                assertSqlFailure(result(removal).failure, "23514", "must retain at least one User who is both");
                assertWorkspaceOutcome(environment(), creator, actor, true);
                assertThat(ownerCount(organization, actor)).isOne();
            }
            assertThat(ownerCount(organization, replacement)).isOne();
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void existingWorkspaceMembershipMutationIsIndependentOfNewWorkspaceCreation(boolean mutationFirst) {
        UUID actor = users.createUser().id();
        UUID replacement = users.createUser().id();
        UUID organization = organizations.createOrganization(actor).id();
        addOwner(organization, replacement);
        Workspace existing = workspaces.createOrganizationOwnedWorkspace(organization, actor);
        jdbc.update("INSERT INTO public.workspace_memberships (workspace_id,user_id,role,status) "
                + "VALUES (?,?,'ADMIN','ACTIVE')", existing.id(), replacement);
        Supplier<Integer> remove = () -> {
            int rows = jdbc.update("DELETE FROM public.workspace_memberships WHERE workspace_id=? AND user_id=?",
                    existing.id(), actor);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            return rows;
        };
        try (Workers workers = new Workers()) {
            Worker<Workspace> creator;
            Worker<Integer> mutation;
            if (mutationFirst) {
                mutation = workers.start(environment(), remove, false, true);
                awaitBody(mutation);
                creator = workers.start(environment(), creation("ORGANIZATION", organization, actor), true, false);
                assertSuccess(result(creator));
                assertHeld(mutation);
                mutation.release.countDown();
                assertSuccess(result(mutation));
            } else {
                creator = workers.start(environment(), creation("ORGANIZATION", organization, actor), true, true);
                awaitBody(creator);
                mutation = workers.start(environment(), remove, false, false);
                assertSuccess(result(mutation));
                assertHeld(creator);
                creator.release.countDown();
                assertSuccess(result(creator));
            }
            assertThat(mutation.value.get()).isOne();
            assertWorkspaceOutcome(environment(), creator, actor, true);
            assertThat(jdbc.queryForList("SELECT user_id FROM public.workspace_memberships "
                    + "WHERE workspace_id=? AND role='ADMIN' AND status='ACTIVE'", UUID.class, existing.id()))
                    .containsExactly(replacement);
        }
    }

    @ParameterizedTest
    @CsvSource({"USER_STATUS", "ORGANIZATION_OWNER", "WORKSPACE_ADMIN"})
    void sameTransactionMutationCannotEvadeDeferredCreationValidation(String mutation) {
        UUID actor = users.createUser().id();
        UUID organization = mutation.equals("ORGANIZATION_OWNER") ? organizations.createOrganization(actor).id() : null;
        if (organization != null) {
            addOwner(organization, users.createUser().id());
        }
        AtomicReference<Workspace> attempted = new AtomicReference<>();
        AtomicInteger bodyReached = new AtomicInteger();
        Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            limits(jdbc);
            Workspace created = creation(organization == null ? "USER" : "ORGANIZATION", organization, actor).get();
            attempted.set(created);
            if (mutation.equals("USER_STATUS")) {
                users.changeStatus(actor, UserStatus.SUSPENDED);
            } else if (mutation.equals("ORGANIZATION_OWNER")) {
                jdbc.update("DELETE FROM public.organization_memberships WHERE organization_id=? AND user_id=?",
                        organization, actor);
            } else {
                jdbc.update("DELETE FROM public.workspace_memberships WHERE workspace_id=? AND user_id=?",
                        created.id(), actor);
            }
            bodyReached.incrementAndGet();
        }));
        assertThat(bodyReached.get()).isOne();
        assertSqlFailure(failure, "23514", switch (mutation) {
            case "USER_STATUS" -> "requires an ACTIVE owner User";
            case "ORGANIZATION_OWNER" -> "requires a creator who is an ACTIVE User";
            default -> "must retain its owner User";
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.workspaces WHERE id=?",
                Integer.class, attempted.get().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.workspace_memberships WHERE workspace_id=?",
                Integer.class, attempted.get().id())).isZero();
        assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.ACTIVE);
        if (organization != null) {
            assertThat(ownerCount(organization, actor)).isOne();
        }
    }

    @ParameterizedTest
    @CsvSource({"CREATE,true", "CREATE,false", "ARCHIVE,true", "ARCHIVE,false", "ACTIVATE,true", "ACTIVATE,false"})
    void productCreationAndLifecycleUseBothRealServiceLockOrders(String operation, boolean productFirst) {
        UUID actor = users.createUser().id();
        Workspace existing = workspaces.createUserOwnedWorkspace(actor);
        ReadyMadeProduct original = operation.equals("CREATE") ? null
                : products.createReadyMadeProduct(existing.id(), actor, 10);
        if (operation.equals("ACTIVATE")) {
            products.archiveReadyMadeProduct(original.id(), actor);
        }
        Supplier<ReadyMadeProduct> productCall = switch (operation) {
            case "CREATE" -> () -> products.createReadyMadeProduct(existing.id(), actor, 10);
            case "ARCHIVE" -> () -> products.archiveReadyMadeProduct(original.id(), actor);
            default -> () -> products.activateReadyMadeProduct(original.id(), actor);
        };
        try (Workers workers = new Workers()) {
            Worker<Workspace> creator;
            Worker<ReadyMadeProduct> product;
            if (productFirst) {
                product = workers.start(environment(), productCall, false, true);
                awaitBody(product);
                creator = workers.start(environment(), () -> workspaces.createUserOwnedWorkspace(actor), false, false);
                awaitExactWait(environment(), creator, product);
                product.release.countDown();
            } else {
                creator = workers.start(environment(), () -> workspaces.createUserOwnedWorkspace(actor), true, true);
                awaitBody(creator);
                product = workers.start(environment(), productCall, false, false);
                awaitExactWait(environment(), product, creator);
                creator.release.countDown();
            }
            assertSuccess(result(creator));
            assertSuccess(result(product));
            assertWorkspaceOutcome(environment(), creator, actor, true);
            ReadyMadeProduct committed = products.findReadyMadeProduct(product.value.get().id());
            assertThat(committed).isEqualTo(product.value.get());
            assertThat(committed.status()).isEqualTo(operation.equals("ARCHIVE")
                    ? ReadyMadeProductStatus.ARCHIVED : ReadyMadeProductStatus.ACTIVE);
            assertThat(committed.availableQuantity()).isEqualTo(10);
            assertThat(committed.workspaceId()).isEqualTo(existing.id());
        }
    }

    @ParameterizedTest
    @CsvSource({"REGISTER,true", "REGISTER,false", "APPLY,true", "APPLY,false",
            "RETRY,true", "RETRY,false", "APPLIED_REPLAY,true", "APPLIED_REPLAY,false",
            "REJECTED_REPLAY,true", "REJECTED_REPLAY,false"})
    void manualDeltaUsesActualRequiresNewInnerTransactionInBothOrders(String mode, boolean manualFirst) {
        assertThat(AopUtils.isAopProxy(products)).isTrue();
        ManualFixture fixture = committedManualFixture(mode);
        ManualHook hook = new ManualHook(fixture, mode.equals("REGISTER") ? "REGISTER" : "APPLY", manualFirst);
        assertThat(selectedManualHook.compareAndSet(null, hook)).isTrue();
        installManualSpy();
        try (Workers workers = new Workers()) {
            workers.releaseOnClose(hook.release);
            Worker<Workspace> creator;
            Worker<ManualQuantityDeltaResult> manual;
            Supplier<ManualQuantityDeltaResult> manualCall = () -> {
                hook.thread = Thread.currentThread();
                hook.outerPid.set(capturePid(jdbc));
                return hook.method.equals("REGISTER")
                        ? products.registerManualQuantityDelta(fixture.product.id(), fixture.command, fixture.delta, fixture.actor)
                        : products.applyManualQuantityDelta(fixture.product.id(), fixture.command, fixture.delta, fixture.actor);
            };
            if (manualFirst) {
                manual = workers.start(environment(), manualCall, false, false);
                awaitManualBoundary(manual, hook, true);
                creator = workers.start(environment(), () -> workspaces.createUserOwnedWorkspace(fixture.actor), false, false);
                awaitManualWait(creator, manual, hook, true);
                hook.release.countDown();
            } else {
                creator = workers.start(environment(), () -> workspaces.createUserOwnedWorkspace(fixture.actor), true, true);
                awaitBody(creator);
                manual = workers.start(environment(), manualCall, false, false);
                awaitManualBoundary(manual, hook, false);
                awaitManualWait(creator, manual, hook, false);
                creator.release.countDown();
            }
            Outcome<ManualQuantityDeltaResult> actual = result(manual);
            assertSuccess(actual);
            assertSuccess(result(creator));
            assertThat(actual.value).isEqualTo(fixture.expected);
            assertThat(hook.realCalls.get()).as("one real repository call, no transparent retry").isOne();
            assertThat(hook.afterCompletionCalls.get()).isOne();
            assertThat(hook.completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(manual.completion.get()).as("separate ambient transaction finished after inner commit")
                    .isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(hook.outerPid.get()).isEqualTo(manual.pid.get()).isNotEqualTo(hook.innerPid.get());
            assertThat(hook.databaseOid.get()).isEqualTo(environment().observer().queryForObject(
                    "SELECT oid::integer FROM pg_database WHERE datname=current_database()", Integer.class));
            assertWorkspaceOutcome(environment(), creator, fixture.actor, true);
            assertExpectedOwner(creator.value.get(), "USER", fixture.actor);
            assertThat(readCommand(fixture.product.id(), fixture.command)).isEqualTo(fixture.expected);
            ReadyMadeProduct persisted = products.findReadyMadeProduct(fixture.product.id());
            assertThat(persisted.availableQuantity()).isEqualTo(fixture.quantityAfter);
            assertThat(persisted.workspaceId()).isEqualTo(fixture.product.workspaceId())
                    .isNotEqualTo(creator.value.get().id());
            System.out.printf("V10_MANUAL_INNER mode=%s manualFirst=%s outerPID=%s innerPID=%s creatorPID=%s "
                            + "realCalls=1 innerCompletion=COMMITTED command=%s quantity=%d%n", mode, manualFirst,
                    hook.outerPid.get(), hook.innerPid.get(), creator.pid.get(), fixture.expected.state(), fixture.quantityAfter);
        } finally {
            hook.release.countDown();
            selectedManualHook.set(null);
            reset(productRepository);
        }
    }

    private ManualFixture committedManualFixture(String mode) {
        UUID actor = users.createUser().id();
        Workspace existing = workspaces.createUserOwnedWorkspace(actor);
        ReadyMadeProduct product = products.createReadyMadeProduct(existing.id(), actor, 10);
        UUID command = UUID.randomUUID();
        long delta = mode.equals("REJECTED_REPLAY") ? -11 : 3;
        ManualQuantityDeltaResult expected;
        long quantityAfter;
        if (mode.equals("REGISTER")) {
            expected = ManualQuantityDeltaResult.registered(product.id(), command, delta);
            quantityAfter = 10;
            assertThat(environment().observer().queryForObject("SELECT count(*) FROM " + COMMANDS
                    + " WHERE product_id=? AND command_id=?", Integer.class, product.id(), command)).isZero();
        } else {
            ManualQuantityDeltaResult registered = products.registerManualQuantityDelta(product.id(), command, delta, actor);
            assertThat(readCommand(product.id(), command)).isEqualTo(registered)
                    .isEqualTo(ManualQuantityDeltaResult.registered(product.id(), command, delta));
            if (mode.equals("RETRY")) {
                users.changeStatus(actor, UserStatus.SUSPENDED);
                assertThatThrownBy(() -> products.applyManualQuantityDelta(product.id(), command, delta, actor))
                        .isInstanceOf(ReadyMadeProductManualQuantityDeltaAccessException.class);
                assertThat(readCommand(product.id(), command)).isEqualTo(registered);
                assertThat(products.findReadyMadeProduct(product.id()).availableQuantity()).isEqualTo(10);
                users.changeStatus(actor, UserStatus.ACTIVE);
            }
            if (mode.endsWith("REPLAY")) {
                expected = products.applyManualQuantityDelta(product.id(), command, delta, actor);
                assertThat(expected).isEqualTo(mode.equals("APPLIED_REPLAY")
                        ? ManualQuantityDeltaResult.applied(product.id(), command, delta, 13)
                        : ManualQuantityDeltaResult.rejected(product.id(), command, delta,
                                ManualQuantityDeltaRejectionReason.UNDERFLOW, 10));
                UUID laterCommand = UUID.randomUUID();
                products.registerManualQuantityDelta(product.id(), laterCommand, 5, actor);
                products.applyManualQuantityDelta(product.id(), laterCommand, 5, actor);
                quantityAfter = mode.equals("APPLIED_REPLAY") ? 18 : 15;
                // Current quantity differs from stored historical output before
                // the race, so recalculation cannot masquerade as terminal replay.
                assertThat(readCommand(product.id(), command)).isEqualTo(expected);
            } else {
                expected = ManualQuantityDeltaResult.applied(product.id(), command, delta, 13);
                quantityAfter = 13;
            }
        }
        assertThat(environment().observer().queryForObject("SELECT available_quantity FROM public.ready_made_products "
                + "WHERE id=?", Long.class, product.id())).isEqualTo(mode.endsWith("REPLAY") ? quantityAfter : 10);
        assertThat(environment().observer().queryForObject("SELECT count(*) FROM public.workspace_memberships "
                + "WHERE workspace_id=? AND user_id=? AND role='ADMIN' AND status='ACTIVE'",
                Integer.class, existing.id(), actor)).isOne();
        return new ManualFixture(actor, product, command, delta, expected, quantityAfter);
    }

    private ManualQuantityDeltaResult readCommand(UUID product, UUID command) {
        return environment().observer().queryForObject("SELECT product_id,command_id,delta,state,resulting_available_quantity,"
                        + "rejection_reason,observed_available_quantity FROM " + COMMANDS + " WHERE product_id=? AND command_id=?",
                (rs, row) -> new ManualQuantityDeltaResult(rs.getObject("product_id", UUID.class),
                        rs.getObject("command_id", UUID.class), rs.getLong("delta"),
                        ManualQuantityDeltaCommandState.valueOf(rs.getString("state")),
                        rs.getObject("resulting_available_quantity", Long.class),
                        rs.getString("rejection_reason") == null ? null
                                : ManualQuantityDeltaRejectionReason.valueOf(rs.getString("rejection_reason")),
                        rs.getObject("observed_available_quantity", Long.class)), product, command);
    }

    private void installManualSpy() {
        doAnswer(invocation -> observeManual(invocation, "REGISTER"))
                .when(productRepository).registerManualQuantityDelta(any(UUID.class), any(UUID.class), anyLong(), any(UUID.class));
        doAnswer(invocation -> observeManual(invocation, "APPLY"))
                .when(productRepository).applyManualQuantityDelta(any(UUID.class), any(UUID.class), anyLong(), any(UUID.class));
    }

    private Object observeManual(org.mockito.invocation.InvocationOnMock invocation, String method) throws Throwable {
        ManualHook hook = selectedManualHook.get();
        if (hook == null || hook.thread != Thread.currentThread() || !hook.method.equals(method)
                || !hook.fixture.product.id().equals(invocation.getArgument(0))
                || !hook.fixture.command.equals(invocation.getArgument(1))) {
            return invocation.callRealMethod();
        }
        assertThat(invocation.<Long>getArgument(2)).isEqualTo(hook.fixture.delta);
        assertThat(invocation.<UUID>getArgument(3)).isEqualTo(hook.fixture.actor);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(jdbc.getDataSource()).isSameAs(source);
        ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(source);
        assertThat(holder).isNotNull();
        Connection connection = holder.getConnection();
        assertThat(connection.getAutoCommit()).isFalse();
        limits(jdbc);
        hook.innerPid.set(capturePid(jdbc));
        hook.databaseOid.set(jdbc.queryForObject(
                "SELECT oid::integer FROM pg_database WHERE datname=current_database()", Integer.class));
        assertThat(hook.innerPid.get()).isNotEqualTo(hook.outerPid.get());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                hook.completion.set(status);
                hook.afterCompletionCalls.incrementAndGet();
            }
        });
        hook.innerEntered.countDown();
        assertThat(hook.realCalls.incrementAndGet()).isOne();
        Object result = invocation.callRealMethod();
        assertThat(result).isEqualTo(hook.fixture.expected);
        assertThat(capturePid(jdbc)).isEqualTo(hook.innerPid.get());
        assertThat(holder.getConnection()).isSameAs(connection);
        assertThat(connection.getAutoCommit()).isFalse();
        assertThat(hook.completion.get()).isNull();
        assertThat(hook.afterCompletionCalls.get()).isZero();
        hook.repositoryCompleted.countDown();
        if (hook.hold) {
            gate(hook.release, "hold after real manual repository operation, before return and inner commit");
        }
        return result;
    }

    private static void awaitManualBoundary(Worker<?> manual, ManualHook hook, boolean completedRepository) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        CountDownLatch boundary = completedRepository ? hook.repositoryCompleted : hook.innerEntered;
        while (System.nanoTime() < deadline) {
            assertStillRunning(manual);
            if (boundary.getCount() == 0) {
                assertThat(hook.innerPid.get()).isNotNull().isNotEqualTo(manual.pid.get());
                assertThat(hook.completion.get()).isNull();
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Real inner manual-delta boundary was not reached");
    }

    private void awaitManualWait(Worker<?> creator, Worker<?> manual, ManualHook hook, boolean manualFirst) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertStillRunning(creator);
            assertStillRunning(manual);
            assertThat(hook.completion.get()).isNull();
            Integer waiter = manualFirst ? creator.pid.get() : hook.innerPid.get();
            Integer blocker = manualFirst ? hook.innerPid.get() : creator.pid.get();
            if (exactWait(environment(), waiter, blocker)) {
                assertThat(hook.innerPid.get()).isNotEqualTo(manual.pid.get());
                assertThat(exactWait(environment(), manualFirst ? creator.pid.get() : manual.pid.get(),
                        manualFirst ? manual.pid.get() : creator.pid.get()))
                        .as("suspended ambient PID is not accepted as inner lock evidence").isFalse();
                assertThat(hook.release.getCount()).isOne();
                if (manualFirst) {
                    assertThat(hook.repositoryCompleted.getCount()).isZero();
                } else {
                    assertHeld(creator);
                    assertThat(hook.repositoryCompleted.getCount()).isOne();
                }
                System.out.printf("V10_MANUAL_EXACT_WAIT waiter=%s blocker=%s suspendedOuter=%s%n",
                        waiter, blocker, manual.pid.get());
                return;
            }
        }
        throw new AssertionError("No exact manual inner/creator lock overlap");
    }

    @Test
    void observerRejectsNullWrongSamePidAndUnrelatedRealBlockedBackend() {
        UUID firstActor = users.createUser().id();
        UUID secondActor = users.createUser().id();
        try (Workers workers = new Workers()) {
            Worker<UUID> firstHolder = workers.start(environment(), () -> lockUser(firstActor), false, true);
            awaitBody(firstHolder);
            Worker<Workspace> firstWaiter = workers.start(environment(), () -> workspaces.createUserOwnedWorkspace(firstActor), false, false);
            awaitExactWait(environment(), firstWaiter, firstHolder);
            Worker<UUID> otherHolder = workers.start(environment(), () -> lockUser(secondActor), false, true);
            awaitBody(otherHolder);
            Worker<Workspace> otherWaiter = workers.start(environment(), () -> workspaces.createUserOwnedWorkspace(secondActor), false, false);
            awaitExactWait(environment(), otherWaiter, otherHolder);
            assertThat(exactWait(environment(), null, firstHolder.pid.get())).isFalse();
            assertThat(exactWait(environment(), firstWaiter.pid.get(), null)).isFalse();
            assertThat(exactWait(environment(), firstWaiter.pid.get(), firstWaiter.pid.get())).isFalse();
            assertThat(exactWait(environment(), Integer.MAX_VALUE, firstHolder.pid.get())).isFalse();
            assertThat(exactWait(environment(), firstWaiter.pid.get(), Integer.MAX_VALUE)).isFalse();
            assertThat(exactWait(environment(), firstWaiter.pid.get(), otherHolder.pid.get())).isFalse();
            assertThat(exactWait(environment(), otherWaiter.pid.get(), firstHolder.pid.get())).isFalse();
            firstHolder.release.countDown();
            otherHolder.release.countDown();
            assertSuccess(result(firstHolder));
            assertSuccess(result(otherHolder));
            assertSuccess(result(firstWaiter));
            assertSuccess(result(otherWaiter));
            assertWorkspaceOutcome(environment(), firstWaiter, firstActor, true);
            assertWorkspaceOutcome(environment(), otherWaiter, secondActor, true);
        }
    }

    @Test
    void observerRejectsEarlyWorkerFailureAndSuccessfulCompletionWithoutOverlap() {
        UUID actor = users.createUser().id();
        IllegalStateException injected = new IllegalStateException("observer negative control: early worker failure");
        try (Workers workers = new Workers()) {
            Worker<UUID> holder = workers.start(environment(), () -> lockUser(actor), false, true);
            awaitBody(holder);
            Worker<Object> failed = workers.start(environment(), () -> { throw injected; }, false, false);
            assertThat(result(failed).failure).isSameAs(injected);
            assertThatThrownBy(() -> awaitExactWait(environment(), failed, holder))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("completed before required overlap")
                    .hasCause(injected);
            Worker<Integer> finished = workers.start(environment(), () -> 1, false, false);
            assertSuccess(result(finished));
            assertThatThrownBy(() -> awaitExactWait(environment(), finished, holder))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("completed before required overlap");
            assertHeld(holder);
            holder.release.countDown();
            assertSuccess(result(holder));
        }
    }

    private UUID lockUser(UUID actor) {
        return jdbc.queryForObject("SELECT id FROM public.users WHERE id=? FOR UPDATE", UUID.class, actor);
    }

    private Supplier<Workspace> creation(String kind, UUID organization, UUID actor) {
        return kind.equals("USER") ? () -> workspaces.createUserOwnedWorkspace(actor)
                : () -> workspaces.createOrganizationOwnedWorkspace(organization, actor);
    }

    private void addOwner(UUID organization, UUID actor) {
        assertThat(jdbc.update("INSERT INTO public.organization_memberships "
                + "(organization_id,user_id,role,status) VALUES (?,?,'OWNER','ACTIVE')", organization, actor)).isOne();
    }

    private int ownerCount(UUID organization, UUID actor) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM public.organization_memberships "
                + "WHERE organization_id=? AND user_id=? AND role='OWNER' AND status='ACTIVE'",
                Integer.class, organization, actor));
    }

    private Environment environment() {
        return new Environment(jdbc, transactions, users, organizations, workspaces);
    }

    private static CreationPair createPair(Environment env, Pair pair) {
        UUID a = env.users.createUser().id();
        UUID b = pair == Pair.SAME_USER || pair == Pair.DIFFERENT_ORGANIZATIONS_COMMON_ACTOR
                || pair == Pair.USER_ORGANIZATION_COMMON_ACTOR ? a : env.users.createUser().id();
        if (pair == Pair.SAME_USER || pair == Pair.DIFFERENT_USERS) {
            return new CreationPair(() -> env.workspaces.createUserOwnedWorkspace(a),
                    () -> env.workspaces.createUserOwnedWorkspace(b), a, b, "USER", a, "USER", b);
        }
        UUID organization = env.organizations.createOrganization(a).id();
        if (pair == Pair.SAME_ORGANIZATION) {
            env.jdbc.update("INSERT INTO public.organization_memberships "
                    + "(organization_id,user_id,role,status) VALUES (?,?,'OWNER','ACTIVE')", organization, b);
            return new CreationPair(() -> env.workspaces.createOrganizationOwnedWorkspace(organization, a),
                    () -> env.workspaces.createOrganizationOwnedWorkspace(organization, b), a, b,
                    "ORGANIZATION", organization, "ORGANIZATION", organization);
        }
        if (pair == Pair.USER_ORGANIZATION_COMMON_ACTOR) {
            return new CreationPair(() -> env.workspaces.createUserOwnedWorkspace(a),
                    () -> env.workspaces.createOrganizationOwnedWorkspace(organization, a), a, a,
                    "USER", a, "ORGANIZATION", organization);
        }
        UUID other = env.organizations.createOrganization(b).id();
        return new CreationPair(() -> env.workspaces.createOrganizationOwnedWorkspace(organization, a),
                () -> env.workspaces.createOrganizationOwnedWorkspace(other, b), a, b,
                "ORGANIZATION", organization, "ORGANIZATION", other);
    }

    private static void assertExpectedOwner(Workspace actual, String kind, UUID expectedOwner) {
        assertThat(actual.ownerType().name()).isEqualTo(kind);
        assertThat(actual.ownerId()).isEqualTo(expectedOwner);
    }

    private static void assertWorkspaceOutcome(Environment env, Worker<Workspace> worker, UUID actor,
            boolean committed) {
        Workspace created = worker.value.get();
        assertThat(created).as("actual service result before commit").isNotNull();
        assertThat(env.jdbc.queryForObject("SELECT count(*) FROM public.workspaces WHERE id=?",
                Integer.class, created.id())).isEqualTo(committed ? 1 : 0);
        assertThat(env.jdbc.queryForObject("SELECT count(*) FROM public.workspace_memberships WHERE workspace_id=?",
                Integer.class, created.id())).isEqualTo(committed ? 1 : 0);
        assertThat(env.jdbc.queryForObject("SELECT count(*) FROM public.workspace_memberships "
                        + "WHERE workspace_id=? AND user_id=? AND role='ADMIN' AND status='ACTIVE'",
                Integer.class, created.id(), actor)).isEqualTo(committed ? 1 : 0);
        assertThat(env.jdbc.queryForObject("SELECT count(*) FROM public.workspace_membership_scopes WHERE workspace_id=?",
                Integer.class, created.id())).isZero();
        if (committed) {
            assertThat(env.workspaces.findWorkspace(created.id())).isEqualTo(created);
            assertThat(worker.completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        } else {
            // JDBC commit failure can be STATUS_UNKNOWN to Spring. PostgreSQL
            // 40P01 plus the exact zero-row checks above prove actual rollback.
            assertThat(worker.completion.get()).isNotNull()
                    .isNotEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private void assertV10() {
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isEqualTo(180004);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history "
                + "WHERE version='10' AND success", Integer.class)).isOne();
        assertThat(AopUtils.isAopProxy(workspaces)).isTrue();
    }

    private static void limits(JdbcTemplate jdbc) {
        jdbc.execute("SET LOCAL lock_timeout='30s'");
        jdbc.execute("SET LOCAL statement_timeout='40s'");
    }

    private static int capturePid(JdbcTemplate jdbc) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
    }

    private static void completion(AtomicReference<Integer> status) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                status.set(completionStatus);
            }
        });
    }

    private static boolean exactWait(Environment env, Integer waiter, Integer blocker) {
        if (waiter == null || blocker == null || waiter.equals(blocker)) {
            return false;
        }
        return Boolean.TRUE.equals(env.observer().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity w JOIN pg_stat_activity b ON b.pid=?
                    WHERE w.pid=? AND w.datid=(SELECT oid FROM pg_database WHERE datname=current_database())
                      AND b.datid=w.datid AND w.wait_event_type='Lock'
                      AND b.pid=ANY(pg_blocking_pids(w.pid)))
                """, Boolean.class, blocker, waiter));
    }

    private static void awaitExactWait(Environment env, Worker<?> waiter, Worker<?> blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertHeld(blocker);
            assertStillRunning(waiter);
            if (exactWait(env, waiter.pid.get(), blocker.pid.get())) {
                System.out.printf("V10_EXACT_WAIT waiter=%s blocker=%s database=%s%n", waiter.pid.get(),
                        blocker.pid.get(), env.observer().queryForObject("SELECT current_database()", String.class));
                return;
            }
        }
        throw new AssertionError("No exact database lock wait: " + waiter.pid.get() + " -> " + blocker.pid.get());
    }

    private static <T> Worker<T> awaitOneOfTwoExactBlockers(Environment env, Worker<?> waiter,
            Worker<T> first, Worker<T> second) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertHeld(first);
            assertHeld(second);
            assertStillRunning(waiter);
            if (exactWait(env, waiter.pid.get(), first.pid.get())) {
                return first;
            }
            if (exactWait(env, waiter.pid.get(), second.pid.get())) {
                return second;
            }
        }
        throw new AssertionError("Writer did not wait on either exact creator PID");
    }

    private static boolean awaitBodyOrQueuedAdmissionWait(Environment env, Worker<?> creator,
            Worker<?> first, Worker<?> writer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertHeld(first);
            assertStillRunning(writer);
            assertStillRunning(creator);
            if (creator.body.getCount() == 0) {
                return false;
            }
            if (exactWait(env, creator.pid.get(), writer.pid.get())
                    || exactWait(env, creator.pid.get(), first.pid.get())) {
                return true;
            }
        }
        throw new AssertionError("No coherent second creation or exact queued-admission wait");
    }

    private static void awaitBodyOrExactWait(Environment env, Worker<?> waiter, Worker<?> blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertHeld(blocker);
            assertStillRunning(waiter);
            if (waiter.body.getCount() == 0) {
                return;
            }
            if (exactWait(env, waiter.pid.get(), blocker.pid.get())) {
                // Never await a service-return latch while keeping its proven
                // blocker hostage. The present deferred candidate must not use it.
                blocker.release.countDown();
                throw new AssertionError("Unexpected early creation wait; protocol differs from reviewed deferred candidate");
            }
        }
        throw new AssertionError("Neither coherent call completion nor exact early wait was observed");
    }

    private static void awaitBody(Worker<?> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (worker.body.getCount() == 0) {
                assertStillRunning(worker);
                return;
            }
            assertStillRunning(worker);
            Thread.onSpinWait();
        }
        throw new AssertionError("Worker did not finish its held operation");
    }

    private static void assertHeld(Worker<?> worker) {
        assertThat(worker.release.getCount()).isOne();
        assertThat(worker.completion.get()).isNull();
        assertStillRunning(worker);
    }

    private static void assertStillRunning(Worker<?> worker) {
        if (worker.future.isDone()) {
            Outcome<?> outcome = result(worker);
            throw new AssertionError("Worker completed before required overlap; result=" + outcome.value, outcome.failure);
        }
    }

    private static void gate(CountDownLatch latch, String description) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as(description).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description, exception);
        }
    }

    private static <T> Outcome<T> result(Worker<T> worker) {
        try {
            return worker.future.get(60, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Worker did not complete within the bounded future deadline", exception);
        }
    }

    private static void assertSuccess(Outcome<?> outcome) {
        assertThat(outcome.failure).as("transaction must commit without retry").isNull();
    }

    private static void assertSqlFailure(Throwable failure, String sqlState, String message) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOfSatisfying(PSQLException.class, error -> {
            assertThat(error.getSQLState()).isEqualTo(sqlState);
            assertThat(error.getServerErrorMessage()).isNotNull();
            assertThat(error.getServerErrorMessage().getMessage()).contains(message);
        });
    }

    private record Environment(JdbcTemplate jdbc, TransactionTemplate transactions, UserService users,
            OrganizationService organizations, WorkspaceService workspaces) {
        JdbcTemplate observer() {
            JdbcTemplate observer = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
            observer.setQueryTimeout(5);
            return observer;
        }
    }
    private record CreationPair(Supplier<Workspace> first, Supplier<Workspace> second,
            UUID firstActor, UUID secondActor, String firstKind, UUID firstOwner,
            String secondKind, UUID secondOwner) { }
    private record Outcome<T>(T value, Throwable failure) { }

    private static final class Worker<T> {
        final AtomicReference<Integer> pid = new AtomicReference<>();
        final AtomicReference<Integer> completion = new AtomicReference<>();
        final AtomicReference<T> value = new AtomicReference<>();
        final CountDownLatch body = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Future<Outcome<T>> future;
    }

    private static final class Workers implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(4);
        private final List<Worker<?>> workers = new ArrayList<>();
        private final List<CountDownLatch> additionalReleases = new ArrayList<>();

        void releaseOnClose(CountDownLatch gate) {
            additionalReleases.add(gate);
        }

        <T> Worker<T> start(Environment env, Supplier<T> operation, boolean forceCreation, boolean hold) {
            Worker<T> worker = new Worker<>();
            workers.add(worker);
            worker.future = executor.submit(() -> {
                try {
                    T value = env.transactions.execute(status -> {
                        limits(env.jdbc);
                        worker.pid.set(capturePid(env.jdbc));
                        completion(worker.completion);
                        T actual = operation.get();
                        worker.value.set(actual);
                        if (forceCreation) {
                            env.jdbc.execute(FORCE_CREATION);
                        }
                        worker.body.countDown();
                        if (hold) {
                            gate(worker.release, "release real worker transaction");
                        }
                        return actual;
                    });
                    return new Outcome<>(value, null);
                } catch (Throwable failure) {
                    return new Outcome<>(worker.value.get(), failure);
                }
            });
            return worker;
        }

        @Override
        public void close() {
            additionalReleases.forEach(CountDownLatch::countDown);
            workers.forEach(worker -> worker.release.countDown());
            executor.shutdown();
            try {
                boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
                if (!terminated) {
                    executor.shutdownNow();
                }
                assertThat(terminated).as("all own workers and transaction resources terminated").isTrue();
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted worker cleanup", exception);
            }
        }
    }

    private record ManualFixture(UUID actor, ReadyMadeProduct product, UUID command, long delta,
            ManualQuantityDeltaResult expected, long quantityAfter) { }

    // PLAN-REV-003 manual-delta observer is scoped to one exact real call.
    private static final class ManualHook {
        final ManualFixture fixture;
        final String method;
        final boolean hold;
        final AtomicReference<Integer> innerPid = new AtomicReference<>();
        final AtomicReference<Integer> outerPid = new AtomicReference<>();
        final AtomicReference<Integer> databaseOid = new AtomicReference<>();
        final AtomicReference<Integer> completion = new AtomicReference<>();
        final AtomicInteger realCalls = new AtomicInteger();
        final AtomicInteger afterCompletionCalls = new AtomicInteger();
        final CountDownLatch innerEntered = new CountDownLatch(1);
        final CountDownLatch repositoryCompleted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread thread;

        ManualHook(ManualFixture fixture, String method, boolean hold) {
            this.fixture = fixture;
            this.method = method;
            this.hold = hold;
        }
    }
}
