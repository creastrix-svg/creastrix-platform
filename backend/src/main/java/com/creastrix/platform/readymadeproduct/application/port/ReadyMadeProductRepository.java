package com.creastrix.platform.readymadeproduct.application.port;

import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;

/**
 * Outbound application port for Ready-Made Product persistence.
 *
 * <p>Only the operations required by the implemented foundation exist:
 * creation, lookup by identity, and the bounded lifecycle command. There are
 * deliberately no quantity mutation, deletion, generic CRUD, paging,
 * list-by-Workspace, or search operations.
 *
 * <p>No implementation detail (SQL, JDBC, Spring types) belongs to this
 * contract.
 */
public interface ReadyMadeProductRepository {

    /**
     * Persists exactly one new Ready-Made Product row with the given values.
     *
     * <p>This operation does not own a transaction boundary: it runs inside the
     * calling application service transaction, whose commit is the linearized
     * creation point validated by the database creation gate.
     */
    void create(
            UUID readyMadeProductId,
            UUID workspaceId,
            UUID createdByUserId,
            long availableQuantity);

    Optional<ReadyMadeProduct> findById(UUID readyMadeProductId);

    /**
     * Performs one authorized expected-state lifecycle transition and returns
     * the complete updated Product.
     *
     * <p>The calling application service owns the transaction. The persistence
     * adapter must acquire locks in the exact order Workspace Membership,
     * exact READY_MADE_PRODUCTS scope grant for an ACTIVE EDITOR, actor User,
     * then Ready-Made Product. A non-locking Product read may precede those
     * locks solely to obtain its immutable Workspace identity. The persisted
     * current status is re-read under the Product lock and must equal {@code
     * expectedStatus}; the update itself is also expected-state constrained.
     *
     * <p>The operation reports missing actor User, inactive actor, missing or
     * ineffective authorization, missing Product, and invalid status through
     * their typed domain exceptions. It validates the represented actor on the
     * supported application path; it does not claim that arbitrary raw SQL is
     * actor-authorized.
     *
     * <p>Future Workspace Membership/scope mutation workflows must acquire
     * Membership before scope. A scope-to-Membership workflow would oppose
     * this canonical order and could deadlock. Future quantity operations must
     * likewise acquire authorization rows before Product. If PostgreSQL emits
     * SQLSTATE 40P01, the whole transaction must be retried by a future retry
     * policy; this slice does not implement retry.
     */
    ReadyMadeProduct transitionStatus(
            UUID readyMadeProductId,
            UUID actorUserId,
            ReadyMadeProductStatus expectedStatus,
            ReadyMadeProductStatus targetStatus);
}
