package com.creastrix.platform.user.application;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.creastrix.platform.user.application.port.UserIdentityBindingRepository;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.PairAlreadyBoundException;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Called only after complete protocol/claim validation; not a public registration API. */
@Service
public class AuthenticatedUserService {

    private static final long RESOLUTION_MILLIS = 15_000;
    private final UserService users;
    private final UserIdentityBindingRepository bindings;
    private final DataSource dataSource;
    private final JdbcTransactionManager transactions;

    public AuthenticatedUserService(UserService users, UserIdentityBindingRepository bindings,
            DataSource dataSource) {
        this.users = users;
        this.bindings = bindings;
        this.dataSource = dataSource;
        this.transactions = new JdbcTransactionManager(dataSource);
    }

    public User resolve(Identity identity, Predicate<Identity> admission) {
        return bounded(identity, admission, lease -> {
            try {
                return transaction(lease, false, () -> {
                    requireAdmission(identity, admission);
                    Optional<User> existing = bindings.findCurrentUser(identity);
                    if (existing.isPresent()) {
                        return requireActive(existing.get());
                    }
                    User created = users.createUser(); // existing Spring proxy joins this exact holder
                    bindings.insert(identity, created.id());
                    return requireActive(created);
                });
            } catch (PairAlreadyBoundException duplicate) {
                // execute() has completed rollback/cleanup before control arrives here.
                // Rollback failures replace this exception and must never enter recovery.
                return transaction(lease, true, () -> {
                    requireAdmission(identity, admission);
                    return requireActive(bindings.findCurrentUser(identity)
                            .orElseThrow(ResolutionUnavailableException::new));
                });
            }
        });
    }

    /** Fresh, read-only exact binding/status lookup. Missing identity never creates anything. */
    public Optional<User> currentUser(Identity identity, Predicate<Identity> admission) {
        return bounded(identity, admission, lease -> transaction(lease, true, () -> {
            requireAdmission(identity, admission);
            return bindings.findCurrentUser(identity);
        }));
    }

    private <T> T bounded(Identity identity, Predicate<Identity> admission, LeaseWork<T> work) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(admission, "admission");
        // Check on the calling thread, BEFORE moving work to an otherwise empty worker context.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Authentication resolution rejects ambient transactions");
        }
        Lease lease = new Lease();
        FutureTask<T> result = new FutureTask<>(() -> {
            requireAdmission(identity, admission);
            // Hikari's blocking acquisition is interruptible. It is included in the deadline,
            // unlike a TransactionTemplate timeout; the shared pool policy never changes.
            try (Connection connection = dataSource.getConnection()) {
                lease.install(connection);
                int originalNetworkTimeout = -1;
                try {
                    originalNetworkTimeout = connection.getNetworkTimeout();
                    connection.setNetworkTimeout(Runnable::run, lease.remainingMillis());
                    TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
                    try {
                        return work.run(lease);
                    } finally {
                        TransactionSynchronizationManager.unbindResource(dataSource);
                    }
                } finally {
                    // Clearing ownership precedes pool return: cancellation can NEVER abort
                    // a connection subsequently borrowed by an unrelated request.
                    lease.release(connection);
                    if (originalNetworkTimeout >= 0 && !connection.isClosed()) {
                        connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
                    }
                }
            }
        });
        Thread.ofVirtual().name("creastrix-auth-resolution").start(result);
        try {
            return result.get(lease.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            result.cancel(true);
            lease.cancel();
            throw new ResolutionUnavailableException(failure);
        } catch (InterruptedException failure) {
            result.cancel(true);
            lease.cancel();
            Thread.currentThread().interrupt();
            throw new ResolutionUnavailableException(failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof AdmissionDeniedException denied) {
                throw denied;
            }
            if (cause instanceof InactiveUserException inactive) {
                throw inactive;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ResolutionUnavailableException(cause);
        } catch (ResolutionUnavailableException expiredBeforeWait) {
            result.cancel(true);
            lease.cancel();
            throw expiredBeforeWait;
        }
    }

    private <T> T transaction(Lease lease, boolean readOnly, Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(readOnly);
        transaction.setTimeout((lease.remainingMillis() + 999) / 1000);
        return transaction.execute(status -> {
            lease.configureTransaction();
            T value = work.get();
            lease.remainingMillis(); // never intentionally commit an already expired attempt
            return value;
        });
    }

    private static void requireAdmission(Identity identity, Predicate<Identity> admission) {
        if (!admission.test(identity)) {
            throw new AdmissionDeniedException();
        }
    }

    private static User requireActive(User user) {
        if (user.status() != UserStatus.ACTIVE) {
            throw new InactiveUserException();
        }
        return user;
    }

    @FunctionalInterface
    private interface LeaseWork<T> {
        T run(Lease lease);
    }

    private static final class Lease {
        private final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESOLUTION_MILLIS);
        private Connection owned;
        private boolean cancelled;

        private int remainingMillis() {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                throw new ResolutionUnavailableException();
            }
            return (int) Math.min(remaining, RESOLUTION_MILLIS);
        }

        private synchronized void install(Connection connection) {
            if (cancelled) {
                throw new ResolutionUnavailableException();
            }
            remainingMillis();
            owned = connection;
        }

        private synchronized void release(Connection connection) {
            if (owned == connection) {
                owned = null;
            }
        }

        private synchronized void cancel() {
            cancelled = true;
            if (owned != null) {
                try {
                    // PostgreSQL abort closes the socket; it does not wait for a blocked SQL query.
                    owned.abort(Runnable::run);
                } catch (SQLException ignored) {
                    // The outcome is unresolved, regardless of whether abort itself was acknowledged.
                }
            }
        }

        private void configureTransaction() {
            int remaining = remainingMillis();
            try (PreparedStatement statement = owned.prepareStatement("""
                    SELECT set_config('lock_timeout', ?, true),
                           set_config('statement_timeout', ?, true),
                           set_config('transaction_timeout', ?, true)
                    """)) {
                statement.setQueryTimeout((remaining + 999) / 1000);
                statement.setString(1, Math.min(5000, remaining) + "ms");
                statement.setString(2, Math.min(10000, remaining) + "ms");
                statement.setString(3, remaining + "ms");
                statement.execute();
            } catch (SQLException failure) {
                throw new ResolutionUnavailableException(failure);
            }
        }
    }

    public static final class AdmissionDeniedException extends RuntimeException {
        public AdmissionDeniedException() {
            super("Authentication admission denied");
        }
    }

    public static final class InactiveUserException extends RuntimeException {
        public InactiveUserException() {
            super("Authentication access denied");
        }
    }

    /** Failure says nothing about commit/rollback. Only a new validated login may resolve it. */
    public static final class ResolutionUnavailableException extends RuntimeException {
        public ResolutionUnavailableException() {
            super("Authentication resolution unavailable; outcome may be unknown");
        }

        public ResolutionUnavailableException(Throwable cause) {
            super("Authentication resolution unavailable; outcome may be unknown", cause);
        }
    }
}
