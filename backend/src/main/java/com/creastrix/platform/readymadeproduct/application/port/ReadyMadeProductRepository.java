package com.creastrix.platform.readymadeproduct.application.port;

import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;

/**
 * Outbound application port for Ready-Made Product persistence.
 *
 * <p>Only the two operations required by this slice exist: creation of one new
 * Ready-Made Product and lookup by identity. There are deliberately no
 * lifecycle or quantity mutation, deletion, generic CRUD, paging,
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
}
