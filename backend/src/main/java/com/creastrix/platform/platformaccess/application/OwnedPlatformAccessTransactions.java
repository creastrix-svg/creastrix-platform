package com.creastrix.platform.platformaccess.application;

import com.creastrix.platform.observability.Diagnostics;
import com.creastrix.platform.observability.Diagnostics.Completion;
import com.creastrix.platform.observability.Diagnostics.Reason;
import com.creastrix.platform.observability.TransactionDiagnostics.Operation;
import com.creastrix.platform.platformaccess.application.port.PlatformAccessRepository.AuthorityChangedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns one unwired platform-access transaction, never an ambient transaction.
 *
 * <p>The primary budget is fifteen seconds including connection acquisition and
 * commit; attempt-audit fallback has its own two-second budget. SQL lock and
 * statement limits are additionally capped at three and five seconds. A returned
 * callback value is private until commit acknowledgement and resource cleanup.
 * A thrown commit is UNKNOWN, even when a later rollback call returns normally.
 * This boundary establishes admission/commit, not network delivery or live MFA.
 *
 * <p>Cancellation interrupts the owned worker and aborts only its leased
 * connection. Ownership is cleared before pool return. The supported
 * Hikari/PostgreSQL path provides interruptible acquisition and socket abort;
 * an arbitrary uninterruptible driver is not given a fictitious real-time
 * guarantee. After a bounded two-second cleanup grace, an unfinished worker
 * withholds payload and preserves the last acknowledged completion. Only that
 * worker may later report its actual completion, after cleanup really finishes.
 * There is no Spring bean, implicit transaction join, retry, or audit writer here.
 */
public final class OwnedPlatformAccessTransactions {
    private static final int PRIMARY_MILLIS = 15_000;
    private static final int FALLBACK_MILLIS = 2_000;
    private static final int CLEANUP_GRACE_MILLIS = 2_000;
    private final DataSource dataSource;

    public OwnedPlatformAccessTransactions(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Must be consulted before trusted-facts callbacks as well as before SQL. */
    public boolean ambientPresent() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(dataSource);
    }

    public <T> Phase<T> primary(Operation operation, Work<T> work) {
        return run(Objects.requireNonNull(operation, "operation"), PRIMARY_MILLIS, work);
    }

    public <T> Phase<T> fallback(Work<T> work) {
        return run(Operation.PLATFORM_ATTEMPT_AUDIT, FALLBACK_MILLIS, work);
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection, Deadline deadline) throws Exception;
    }

    /** Internal result: failure objects must not cross the outward service API. */
    public record Phase<T>(T value, Completion completion, Reason reason, Reason failureReason,
                           Throwable failure, boolean ambient, boolean cleanupComplete, boolean releaseAllowed) {
        public boolean succeeded() {
            return !ambient && cleanupComplete && completion == Completion.COMMITTED && releaseAllowed;
        }
    }

    /** A typed application denial; its exact object survives rollback/cleanup failures. */
    public static class Rejected extends RuntimeException implements Diagnostics.ClassifiedFailure {
        private final Reason reason;

        public Rejected(Reason reason) {
            super("Platform access operation rejected");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Reason diagnosticReason() {
            return reason;
        }
    }

    private <T> Phase<T> run(Operation operation, int millis, Work<T> work) {
        // Do not allocate a worker/lease or touch any sink under someone else's locks.
        if (ambientPresent()) {
            return new Phase<>(null, Completion.NOT_OBSERVED, Reason.ADMISSION_DENIED, Reason.ADMISSION_DENIED,
                    new Rejected(Reason.ADMISSION_DENIED), true, true, false);
        }
        Objects.requireNonNull(work, "work");
        FailureState failures = new FailureState();
        Deadline deadline = new Deadline(millis, failures);
        String correlation = Diagnostics.correlationId();
        FutureTask<Phase<T>> task = new FutureTask<>(() -> {
            Phase<T> phase = execute(work, deadline, failures);
            // This worker owns no database resource now. Never attach a foreign observer.
            if (phase.cleanupComplete()) {
                try (var ignored = Diagnostics.withCorrelation(correlation)) {
                    Diagnostics.transaction(operation, phase.completion(), phase.reason());
                } catch (Throwable ignored) {
                    // Diagnostic sink failure cannot replace the transaction's exact result.
                }
            }
            return phase;
        });
        Thread worker = Thread.ofVirtual().name("creastrix-platform-access-owned").unstarted(task);
        worker.start();
        boolean interrupted = false;
        try {
            try {
                return task.get(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruption) {
                interrupted = true;
                Phase<T> completed = deadline.cancel(Reason.INTERRUPTED);
                if (completed != null) {
                    return completed;
                }
            } catch (TimeoutException | BudgetExpired expiry) {
                Reason cancellation = expiry instanceof BudgetExpired budget
                        ? budget.diagnosticReason() : Reason.DEADLINE_EXCEEDED;
                if (cancellation == Reason.INTERRUPTED) {
                    interrupted = true;
                    Thread.interrupted();
                }
                // A slow diagnostic sink is outside the completed owned phase, not lost commit ACK.
                Phase<T> completed = deadline.cancel(cancellation);
                if (completed != null) {
                    return completed;
                }
            }
            worker.interrupt();
            // Abort has its own thread so an unsupported blocking abort cannot hang the caller.
            Thread.ofVirtual().name("creastrix-platform-access-abort").start(deadline::abortOwned);
            long cleanupEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_GRACE_MILLIS);
            while (true) {
                long remaining = cleanupEnd - System.nanoTime();
                if (remaining <= 0) {
                    return failures.unfinished();
                }
                try {
                    return task.get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    deadline.cancel(Reason.INTERRUPTED);
                } catch (TimeoutException expiry) {
                    return failures.unfinished();
                }
            }
        } catch (ExecutionException unexpectedWorkerFailure) {
            // execute catches work/cleanup failures; this is a defensive worker infrastructure case.
            failures.add(unexpectedWorkerFailure.getCause());
            return failures.unfinished();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private <T> Phase<T> execute(Work<T> work, Deadline deadline, FailureState failures) {
        Connection connection = null;
        T value = null;
        Completion completion = Completion.NOT_OBSERVED;
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        boolean rollbackAcknowledged = false;
        boolean cleanupComplete = true;
        int originalNetworkTimeout = -1;
        boolean acquiring = true;
        try {
            deadline.remainingMillis();
            connection = dataSource.getConnection();
            acquiring = false;
            deadline.install(connection);
            originalNetworkTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, deadline.remainingMillis());
            if (!connection.getAutoCommit()) {
                throw new SQLException("Owned platform connection requires clean auto-commit state");
            }
            connection.setReadOnly(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            transactionStarted = true;
            completion = Completion.UNKNOWN;
            failures.observe(completion);
            connection.setAutoCommit(false);
            deadline.configure(connection);
            value = work.run(connection, deadline);
            deadline.configure(connection);
            deadline.remainingMillis();
            commitAttempted = true;
            connection.commit();
            completion = Completion.COMMITTED;
            // Only an actual returned commit call publishes ACK; close/isClosed never proves it.
            failures.observe(completion);
        } catch (Throwable failure) {
            failures.add(failure, acquiring);
        } finally {
            // Interrupt delivery must not prevent rollback/close; restore it only after cleanup.
            boolean interrupted = Thread.interrupted();
            if (connection != null) {
                if (transactionStarted && completion != Completion.COMMITTED) {
                    try {
                        connection.rollback();
                        rollbackAcknowledged = true;
                        if (!commitAttempted) {
                            completion = Completion.ROLLED_BACK;
                            failures.observe(completion);
                        }
                    } catch (Throwable rollbackFailure) {
                        failures.add(rollbackFailure);
                        completion = Completion.UNKNOWN;
                        failures.observe(completion);
                    }
                }
                // Never turn autoCommit on after an uncertain transaction: that could commit work.
                if (transactionStarted && completion != Completion.COMMITTED && !rollbackAcknowledged) {
                    deadline.abortOwned();
                }
                try {
                    if (originalNetworkTimeout >= 0 && !connection.isClosed()) {
                        connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
                    }
                } catch (Throwable resetFailure) {
                    failures.addCleanup(resetFailure);
                }
                deadline.release(connection);
                try {
                    connection.close();
                } catch (Throwable closeFailure) {
                    failures.addCleanup(closeFailure);
                    // A delegate may close successfully and then lose acknowledgement. Do not
                    // abort a handle after pool return: a new borrower may own the physical socket.
                    try {
                        cleanupComplete = connection.isClosed();
                        if (!cleanupComplete) {
                            // This exact handle is still open, so one bounded abort/close cleanup
                            // belongs to this phase, not to a subsequently borrowed connection.
                            connection.abort(Runnable::run);
                            connection.close();
                            cleanupComplete = true;
                        }
                    } catch (Throwable inspectionFailure) {
                        failures.addCleanup(inspectionFailure);
                        cleanupComplete = false;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        // Deadline cancellation is recorded before abort/interrupt, including a late commit ACK.
        return failures.finished(value, cleanupComplete, deadline);
    }

    /**
     * One monotonic nonrenewable budget shared by acquisition, every statement,
     * and commit. Repositories call configure before each SQL command; final
     * clock sampling must remain a separate command after the blocking lock.
     */
    public static final class Deadline {
        private final long expiresAt;
        private final FailureState failures;
        private Connection owned;

        private Deadline(int millis, FailureState failures) {
            this.expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            this.failures = failures;
        }

        public int remainingMillis() {
            if (Thread.currentThread().isInterrupted()) {
                throw new BudgetExpired(Reason.INTERRUPTED);
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(expiresAt - System.nanoTime());
            if (failures.cancelled() || remaining <= 0) {
                throw new BudgetExpired(Reason.DEADLINE_EXCEEDED);
            }
            return (int) Math.min(Integer.MAX_VALUE, remaining);
        }

        public void beforeStatement(Statement statement) throws SQLException {
            statement.setQueryTimeout((Math.min(5_000, remainingMillis()) + 999) / 1000);
        }

        public void configure(Connection connection) throws SQLException {
            int remaining = remainingMillis();
            connection.setNetworkTimeout(Runnable::run, remaining);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT set_config('lock_timeout', ?, true),
                           set_config('statement_timeout', ?, true),
                           set_config('transaction_timeout', ?, true)
                    """)) {
                beforeStatement(statement);
                statement.setString(1, Math.min(3_000, remaining) + "ms");
                statement.setString(2, Math.min(5_000, remaining) + "ms");
                statement.setString(3, remaining + "ms");
                statement.execute();
            }
            remainingMillis();
        }

        private synchronized void install(Connection connection) {
            // A late pool acquisition after cancellation is closed without doing any SQL.
            remainingMillis();
            owned = connection;
        }

        private synchronized void release(Connection connection) {
            if (owned == connection) {
                owned = null;
            }
        }

        private <T> Phase<T> cancel(Reason reason) {
            return failures.cancel(reason);
        }

        private synchronized void abortOwned() {
            if (owned != null) {
                try {
                    owned.abort(Runnable::run);
                } catch (Throwable abortFailure) {
                    failures.add(abortFailure);
                }
            }
        }
    }

    private static final class BudgetExpired extends RuntimeException implements Diagnostics.ClassifiedFailure {
        private final Reason reason;

        private BudgetExpired(Reason reason) {
            super("Platform access transaction budget unavailable");
            this.reason = reason;
        }

        @Override
        public Reason diagnosticReason() {
            return reason;
        }
    }

    /** First failure object is preserved; only the closed diagnostic severity may advance. */
    private static final class FailureState {
        private Throwable failure;
        private Reason reason;
        private Reason failureReason;
        private Completion completion = Completion.NOT_OBSERVED;
        private boolean cancelled;
        private Phase<?> completed;

        private synchronized void add(Throwable candidate) {
            add(candidate, false);
        }

        private synchronized void add(Throwable candidate, boolean acquisition) {
            add(candidate, classify(candidate, acquisition));
        }

        private synchronized void addCleanup(Throwable candidate) {
            Reason classified = classify(candidate, false);
            if (completion == Completion.COMMITTED && Diagnostics.level(classified).toInt()
                    <= org.slf4j.event.Level.WARN.toInt()) {
                classified = Reason.POST_COMMIT_CLEANUP_FAILURE;
            }
            add(candidate, classified);
        }

        private void add(Throwable candidate, Reason classified) {
            if (candidate == null) {
                return;
            }
            if (failure == null) {
                failure = candidate;
                failureReason = classified;
            }
            if (reason == null || Diagnostics.level(classified).toInt() > Diagnostics.level(reason).toInt()) {
                reason = classified;
            }
        }

        private synchronized void observe(Completion observed) {
            completion = observed;
        }

        private synchronized boolean cancelled() { return cancelled; }

        private synchronized <T> Phase<T> finished(T value, boolean cleanupComplete, Deadline deadline) {
            if (!cancelled && (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline.expiresAt)) {
                cancel(Thread.currentThread().isInterrupted() ? Reason.INTERRUPTED : Reason.DEADLINE_EXCEEDED);
            }
            boolean release = cleanupComplete && completion == Completion.COMMITTED && !cancelled && value != null;
            Phase<T> phase = new Phase<>(release ? value : null, completion, reason, failureReason,
                    failure, false, cleanupComplete, release);
            completed = phase;
            return phase;
        }

        @SuppressWarnings("unchecked")
        private synchronized <T> Phase<T> cancel(Reason cancellation) {
            // Cancellation and the final release choice share this monitor. A completed
            // phase is immutable even if a diagnostic sink is still running outside it.
            if (completed != null) { return (Phase<T>) completed; }
            cancelled = true;
            add(new BudgetExpired(cancellation));
            return null;
        }

        private synchronized <T> Phase<T> unfinished() {
            return new Phase<>(null, completion, reason, failureReason, failure, false, false, false);
        }
    }

    /** Scoped to this owned JDBC path; the generic authentication classifier is unchanged. */
    private static Reason classify(Throwable failure, boolean acquisition) {
        Throwable current = failure;
        Reason sql = null;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof Diagnostics.ClassifiedFailure classified) { return classified.diagnosticReason(); }
            if (current instanceof AuthorityChangedException) { return Reason.AUTHORITY_CHANGED; }
            if (current instanceof InterruptedException) { return Reason.INTERRUPTED; }
            if (current instanceof TimeoutException || current instanceof SQLTimeoutException) { return Reason.DEADLINE_EXCEEDED; }
            if (current instanceof SQLException database) {
                String state = database.getSQLState();
                if ("55P03".equals(state)) { sql = Reason.LOCK_NOT_AVAILABLE; }
                else if ("57014".equals(state)) { sql = Reason.STATEMENT_CANCELLED; }
                else if (sql == null && (acquisition || database instanceof SQLTransientConnectionException
                        || database instanceof SQLNonTransientConnectionException
                        || (state != null && state.startsWith("08")) || "57P01".equals(state)
                        || "57P02".equals(state) || "57P03".equals(state) || "57P04".equals(state))) {
                    sql = Reason.DATABASE_UNAVAILABLE;
                }
            }
        }
        return sql == null ? Reason.UNEXPECTED_FAILURE : sql;
    }
}
