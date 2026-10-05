package com.creastrix.platform.observability;

import java.util.EnumMap;
import java.util.EnumSet;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Observes transaction completion after a supported service operation returned.
 *
 * <p>A return is not a commit. At most one observation per operation is retained
 * in one synchronization for the transaction; repeated calls are coalesced and
 * retain their latest request correlation. This fixed fifteen-operation set is not
 * a per-call queue, a complete SQL mutation record, or a durable audit trail.
 * A manual-delta result may be historical replay, not a new quantity change.
 *
 * <p>Spring-observed savepoint rollback conservatively makes the observation
 * unknown, even if the outer transaction subsequently commits. Direct JDBC
 * savepoints bypass Spring callbacks and are outside this observation contract.
 * The observer does not perform database work or change transaction behavior.
 */
public final class TransactionDiagnostics {

    public enum Operation {
        USER_CREATE,
        USER_STATUS_CHANGE,
        ORGANIZATION_CREATE,
        USER_WORKSPACE_CREATE,
        ORGANIZATION_WORKSPACE_CREATE,
        PRODUCT_CREATE,
        PRODUCT_ARCHIVE,
        PRODUCT_ACTIVATE,
        DELTA_REGISTER,
        DELTA_APPLY_OR_REPLAY,
        PLATFORM_INTENT_REGISTER,
        PLATFORM_USER_SECURITY_READ,
        PLATFORM_SUPPORT_REVOKE,
        PLATFORM_OPERATION_RECEIPT,
        PLATFORM_ATTEMPT_AUDIT
    }

    private TransactionDiagnostics() {
    }

    /** Returns the original result unchanged; never treats an unobserved transaction as committed. */
    public static <T> T returned(Operation operation, T result) {
        String correlation = Diagnostics.correlationId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            Diagnostics.transaction(operation, Diagnostics.Completion.NOT_OBSERVED);
            return result;
        }
        CompletionObservation observation = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(CompletionObservation.class::isInstance)
                .map(CompletionObservation.class::cast)
                .findFirst()
                .orElse(null);
        if (observation == null) {
            observation = new CompletionObservation();
            TransactionSynchronizationManager.registerSynchronization(observation);
        }
        observation.operations.add(operation);
        observation.correlations.put(operation, correlation);
        return result;
    }

    private static final class CompletionObservation implements TransactionSynchronization {
        private final EnumSet<Operation> operations = EnumSet.noneOf(Operation.class);
        private final EnumMap<Operation, String> correlations = new EnumMap<>(Operation.class);
        private boolean savepointRollbackObserved;
        private boolean commitMayHaveStarted;

        @Override
        public void beforeCommit(boolean readOnly) {
            // JDBC managers may report ROLLED_BACK after a failed commit acknowledgement,
            // even when the database committed. A later rollback status is not conclusive.
            commitMayHaveStarted = true;
        }

        @Override
        public void savepointRollback(Object savepoint) {
            // No output while a transaction/savepoint is still deciding its outcome.
            savepointRollbackObserved = true;
        }

        @Override
        public void afterCompletion(int status) {
            Diagnostics.Completion completion = savepointRollbackObserved
                    || (commitMayHaveStarted && status != STATUS_COMMITTED)
                    ? Diagnostics.Completion.UNKNOWN
                    : switch (status) {
                        case STATUS_COMMITTED -> Diagnostics.Completion.COMMITTED;
                        case STATUS_ROLLED_BACK -> Diagnostics.Completion.ROLLED_BACK;
                        default -> Diagnostics.Completion.UNKNOWN;
                    };
            for (Operation operation : operations) {
                try (var ignored = Diagnostics.withCorrelation(correlations.get(operation))) {
                    Diagnostics.transaction(operation, completion);
                }
            }
        }
    }
}
