package com.creastrix.platform.organization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.creastrix.platform.organization.application.OrganizationService;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationCreatorNotActiveException;
import com.creastrix.platform.user.application.UserService;
import com.creastrix.platform.user.domain.UserStatus;
import com.creastrix.platform.workspace.application.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real Spring/RC admission proofs; observers never replace User facts or SQL results. */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.connection-timeout=5000"})
@Testcontainers
@Timeout(90)
class OrganizationCreationConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private UserService users;
    @Autowired private OrganizationService organizations;
    @Autowired private WorkspaceService workspaces;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Run> runs = new ArrayList<>();

    @AfterEach
    void releaseOnlyOwnedWorkersAndRestoreTheSqlObserver() throws Exception {
        runs.forEach(run -> run.release.countDown());
        executor.shutdown();
        assertThat(executor.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
        reset(jdbc);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void committedStatusWriterFirstPreventsStaleCreation(UserStatus inactive) throws Exception {
        UUID actor = users.createUser().id();
        Run writer = start(() -> users.changeStatus(actor, inactive), true, false);
        body(writer);
        Run creator = start(() -> organizations.createOrganization(actor), false, false);
        waitFor(creator, writer);
        writer.release.countDown();
        success(writer);
        Outcome result = done(creator);
        int memberships = memberships(actor);
        System.out.printf("CFA03_STATUS_FIRST status=%s completion=%d membershipRows=%d%n",
                inactive, creator.completion.get(), memberships);
        assertThat(result.failure()).isInstanceOf(OrganizationCreatorNotActiveException.class);
        assertThat(creator.completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(memberships).isZero();
        assertThat(users.findUser(actor).status()).isEqualTo(inactive);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void rolledBackStatusWriterAllowsCreation(UserStatus inactive) throws Exception {
        UUID actor = users.createUser().id();
        Run writer = start(() -> users.changeStatus(actor, inactive), true, true);
        body(writer);
        Run creator = start(() -> organizations.createOrganization(actor), false, false);
        waitFor(creator, writer);
        writer.release.countDown();
        assertThat(done(writer).failure()).isNull();
        assertThat(writer.completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        rows(((Organization) success(creator)).id(), actor, 1);
        assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @ParameterizedTest
    @CsvSource({"SUSPENDED,false", "DEACTIVATED,false", "SUSPENDED,true", "DEACTIVATED,true"})
    void creationAdmissionFirstOrdersTheStatusWriterAfterItsOutcome(UserStatus inactive, boolean rollback) throws Exception {
        UUID actor = users.createUser().id();
        Run creator = start(() -> organizations.createOrganization(actor), true, rollback);
        body(creator);
        assertThat(memberships(actor)).isZero();
        Run writer = start(() -> users.changeStatus(actor, inactive), false, false);
        waitFor(writer, creator);
        creator.release.countDown();
        Outcome created = done(creator);
        assertThat(created.failure()).isNull();
        assertThat(creator.completion.get()).isEqualTo(rollback
                ? TransactionSynchronization.STATUS_ROLLED_BACK : TransactionSynchronization.STATUS_COMMITTED);
        success(writer);
        rows(((Organization) created.value()).id(), actor, rollback ? 0 : 1);
        assertThat(users.findUser(actor).status()).isEqualTo(inactive);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void nonKeyRawStatusUpdateAlsoConflictsWithAdmission(UserStatus inactive) throws Exception {
        UUID actor = users.createUser().id();
        Run writer = start(() -> jdbc.update("UPDATE users SET status=? WHERE id=?", inactive.name(), actor), true, false);
        body(writer);
        Run creator = start(() -> organizations.createOrganization(actor), false, false);
        waitFor(creator, writer);
        writer.release.countDown();
        success(writer);
        assertThat(done(creator).failure()).isInstanceOf(OrganizationCreatorNotActiveException.class);
        assertThat(memberships(actor)).isZero();
        assertThat(users.findUser(actor).status()).isEqualTo(inactive);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void sameTransactionLaterStatusChangeIsAfterAdmissionNotACommitTimePredicate(UserStatus inactive) {
        UUID actor = users.createUser().id();
        Organization created = transactions.execute(status -> {
            int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            Organization organization = organizations.createOrganization(actor);
            users.changeStatus(actor, inactive);
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pid);
            return organization;
        });
        rows(created.id(), actor, 1);
        assertThat(users.findUser(actor).status()).isEqualTo(inactive);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void sameTransactionStatusFirstRejectsAndRollsBackTheWholeBatch(UserStatus inactive) {
        UUID actor = users.createUser().id();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            users.changeStatus(actor, inactive);
            organizations.createOrganization(actor);
        })).isInstanceOf(OrganizationCreatorNotActiveException.class);
        assertThat(memberships(actor)).isZero();
        assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void sameTransactionReactivationBeforeCreationReadsItsOwnCurrentState() {
        UUID actor = users.createUser().id();
        users.changeStatus(actor, UserStatus.SUSPENDED);
        Organization created = transactions.execute(status -> {
            users.changeStatus(actor, UserStatus.ACTIVE);
            return organizations.createOrganization(actor);
        });
        rows(created.id(), actor, 1);
        assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void keyShareHolderDoesNotForceACreatorLockUpgradeAndBothCreationsComplete() throws Exception {
        UUID actor = users.createUser().id();
        Run reference = start(() -> jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR KEY SHARE", UUID.class, actor), true, false);
        body(reference);
        Run first = start(() -> organizations.createOrganization(actor), true, false);
        body(first);
        Run second = start(() -> organizations.createOrganization(actor), false, false);
        waitFor(second, first);
        first.release.countDown();
        rows(((Organization) success(first)).id(), actor, 1);
        rows(((Organization) success(second)).id(), actor, 1);
        assertThat(reference.future.isDone()).isFalse();
        reference.release.countDown();
        success(reference);
    }

    @Test
    void differentCreatorIsNotBlockedByAProcessWideAdmissionLock() throws Exception {
        UUID firstActor = users.createUser().id();
        UUID otherActor = users.createUser().id();
        Run first = start(() -> organizations.createOrganization(firstActor), true, false);
        body(first);
        rows(((Organization) success(start(() -> organizations.createOrganization(otherActor), false, false))).id(), otherActor, 1);
        assertThat(first.future.isDone()).isFalse();
        first.release.countDown();
        rows(((Organization) success(first)).id(), firstActor, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO organizations", "INSERT INTO organization_memberships"})
    void failureAfterEachRealInsertLeavesNoPartialOrganization(String seam) {
        UUID actor = users.createUser().id();
        AtomicReference<UUID> id = new AtomicReference<>();
        IllegalStateException injected = new IllegalStateException("CFA03 owned post-insert fault");
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (((String) invocation.getArgument(0)).startsWith(seam)) {
                UUID organization = (UUID) ((Object[]) invocation.getRawArguments()[1])[0];
                id.set(organization);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations WHERE id=?", Integer.class, organization)).isOne();
                throw injected;
            }
            return result;
        }).when(jdbc).update(anyString(), any(Object[].class));
        assertThat(catchThrowable(() -> organizations.createOrganization(actor))).isSameAs(injected);
        assertThat(id.get()).isNotNull();
        rows(id.get(), actor, 0);
    }

    @Test
    void callerRollbackAfterInnerReturnRemovesBothRows() {
        UUID actor = users.createUser().id();
        AtomicReference<Organization> returned = new AtomicReference<>();
        transactions.executeWithoutResult(status -> {
            returned.set(organizations.createOrganization(actor));
            status.setRollbackOnly();
        });
        rows(returned.get().id(), actor, 0);
    }

    @Test
    void savepointRollbackRemovesCreationAndReleasesItsUserLock() throws Exception {
        UUID actor = users.createUser().id();
        AtomicReference<Organization> returned = new AtomicReference<>();
        transactions.executeWithoutResult(status -> {
            Object savepoint = status.createSavepoint();
            returned.set(organizations.createOrganization(actor));
            status.rollbackToSavepoint(savepoint);
            status.releaseSavepoint(savepoint);
            Run writer = start(() -> users.changeStatus(actor, UserStatus.SUSPENDED), false, false);
            try { success(writer); }
            catch (Exception failure) { throw new AssertionError("Owned writer did not finish after savepoint rollback", failure); }
            rows(returned.get().id(), actor, 0);
        });
        rows(returned.get().id(), actor, 0);
        assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8})
    void unsupportedIsolationStillReachesTheUnchangedFoundationGuard(int isolation) {
        UUID actor = users.createUser().id();
        TransactionTemplate unsupported = new TransactionTemplate(transactions.getTransactionManager());
        unsupported.setIsolationLevel(isolation);
        Throwable failure = catchThrowable(() -> unsupported.executeWithoutResult(status -> organizations.createOrganization(actor)));
        PSQLException postgres = postgres(failure);
        assertThat(postgres.getSQLState()).isEqualTo("0A000");
        assertThat(postgres.getServerErrorMessage().getMessage()).startsWith("CREASTRIX_FOUNDATION_WRITE_ISOLATION_V1:");
        assertThat(postgres.getServerErrorMessage().getTable()).isEqualTo("organizations");
        assertThat(memberships(actor)).isZero();
        rows(organizations.createOrganization(actor).id(), actor, 1);
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void composedWorkspaceKeepsItsStrongerDeferredGate(UserStatus target) {
        UUID actor = users.createUser().id();
        AtomicReference<UUID> organization = new AtomicReference<>();
        AtomicReference<UUID> workspace = new AtomicReference<>();
        Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            organization.set(organizations.createOrganization(actor).id());
            workspace.set(workspaces.createOrganizationOwnedWorkspace(organization.get(), actor).id());
            if (target != UserStatus.ACTIVE) { users.changeStatus(actor, target); }
        }));
        int expected = target == UserStatus.ACTIVE ? 1 : 0;
        if (expected == 1) { assertThat(failure).isNull(); }
        else {
            assertThat(postgres(failure).getSQLState()).isEqualTo("23514");
            assertThat(postgres(failure).getServerErrorMessage().getMessage()).contains("requires a creator who is an ACTIVE User");
        }
        rows(organization.get(), actor, expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id=?", Integer.class, workspace.get())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_memberships WHERE workspace_id=?", Integer.class, workspace.get())).isEqualTo(expected);
        assertThat(users.findUser(actor).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void admissionReadCannotUseAnAutocommitTransactionOfItsOwn() {
        UUID actor = users.createUser().id();
        assertThatThrownBy(() -> users.findUserForStatusAdmission(actor)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(memberships(actor)).isZero();
    }

    private void rows(UUID organization, UUID actor, int expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations WHERE id=?", Integer.class, organization)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE organization_id=?", Integer.class, organization)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE organization_id=? AND user_id=? "
                + "AND role='OWNER' AND status='ACTIVE'", Integer.class, organization, actor)).isEqualTo(expected);
    }

    private static PSQLException postgres(Throwable failure) {
        Throwable current = failure;
        for (int i = 0; current != null && i < 16; i++) {
            if (current instanceof PSQLException postgres) { return postgres; }
            current = current instanceof TransactionSystemException transaction && transaction.getApplicationException() != null
                    ? transaction.getApplicationException() : current.getCause();
        }
        throw new AssertionError("Expected the exact PostgreSQL guard failure", failure);
    }

    private Run start(Supplier<?> action, boolean hold, boolean rollback) {
        Run run = new Run();
        runs.add(run);
        run.future = executor.submit(() -> {
            Object[] value = new Object[1];
            Throwable failure = null;
            try {
                transactions.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL lock_timeout='20s'");
                    jdbc.execute("SET LOCAL statement_timeout='25s'");
                    assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                    run.pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int completion) { run.completion.set(completion); }
                    });
                    run.entered.countDown();
                    value[0] = action.get();
                    run.body.countDown();
                    if (hold) { await(run.release); }
                    if (rollback) { status.setRollbackOnly(); }
                });
            } catch (Throwable observed) { failure = observed; }
            return new Outcome(value[0], failure);
        });
        return run;
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Owned test barrier interrupted", interrupted);
        }
    }

    private static void body(Run run) throws Exception {
        if (!run.body.await(8, TimeUnit.SECONDS)) {
            if (run.future.isDone()) { throw new AssertionError("Worker failed before its real body boundary", done(run).failure()); }
            throw new AssertionError("Worker did not reach its bounded body boundary");
        }
    }

    private void waitFor(Run waiter, Run blocker) throws Exception {
        await(waiter.entered);
        await(blocker.entered);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (waiter.future.isDone()) { throw new AssertionError("Waiter completed before required lock overlap", done(waiter).failure()); }
            assertThat(blocker.future.isDone()).isFalse();
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                    Boolean.class, blocker.pid.get(), waiter.pid.get()))) {
                assertThat(waiter.pid.get()).isNotEqualTo(blocker.pid.get());
                assertThat(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
                        waiter.pid.get(), waiter.pid.get())).isFalse();
                assertThat(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
                        Integer.MAX_VALUE, waiter.pid.get())).isFalse();
                System.out.printf("CFA03_EXACT_WAIT waiter=%d blocker=%d%n", waiter.pid.get(), blocker.pid.get());
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Exact owned backend contention was not established");
    }

    private static Outcome done(Run run) throws Exception { return run.future.get(25, TimeUnit.SECONDS); }
    private static Object success(Run run) throws Exception {
        Outcome result = done(run);
        assertThat(result.failure()).isNull();
        assertThat(run.completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        return result.value();
    }

    private int memberships(UUID actor) {
        return jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE user_id=?", Integer.class, actor);
    }

    private record Outcome(Object value, Throwable failure) { }
    private static final class Run {
        final AtomicInteger pid = new AtomicInteger();
        final AtomicInteger completion = new AtomicInteger(-1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch body = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Future<Outcome> future;
    }
}
