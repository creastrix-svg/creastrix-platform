package com.creastrix.platform.readymadeproduct.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A Ready-Made Product: the stable domain identity of one independently
 * stocked physical product configuration that can be fulfilled from existing
 * stock.
 *
 * <p>This slice models exactly the structural fields defined by the approved
 * specification for the foundation: the stable identity, the single Workspace
 * it belongs to, its immutable Created By User, its lifecycle state, and its
 * simple available quantity. Name, description, SKU, brand, model, media,
 * dimensions, weight, shipping data, Manufacturer or Supplier relationships,
 * and Product Variants are not part of the approved MVP model and therefore do
 * not belong to this type.
 *
 * <p>Available quantity is the number of whole physical units currently
 * sellable and free for new allocation. Zero is a valid value, and quantity is
 * independent from the lifecycle state.
 *
 * <p>Created By identifies only the User who created the record. It does not
 * determine ownership, commercial context, seller, manufacturer, supplier,
 * brand ownership, or publication authority.
 */
public record ReadyMadeProduct(
        UUID id,
        UUID workspaceId,
        UUID createdByUserId,
        ReadyMadeProductStatus status,
        long availableQuantity) {

    public ReadyMadeProduct {
        Objects.requireNonNull(id, "Ready-Made Product id must not be null");
        Objects.requireNonNull(workspaceId, "Ready-Made Product workspaceId must not be null");
        Objects.requireNonNull(createdByUserId, "Ready-Made Product createdByUserId must not be null");
        Objects.requireNonNull(status, "Ready-Made Product status must not be null");
        if (availableQuantity < 0) {
            throw new IllegalArgumentException(
                    "Ready-Made Product availableQuantity must not be negative, but was %d"
                            .formatted(availableQuantity));
        }
    }

    /**
     * Returns this Ready-Made Product in the requested lifecycle state while
     * preserving identity, Workspace, Created By, and available quantity.
     *
     * @throws InvalidReadyMadeProductStatusTransitionException if the
     *         requested transition is not supported
     */
    public ReadyMadeProduct transitionTo(ReadyMadeProductStatus target) {
        Objects.requireNonNull(target, "Target Ready-Made Product status must not be null");
        if (!status.canTransitionTo(target)) {
            throw new InvalidReadyMadeProductStatusTransitionException(id, status, target);
        }
        return new ReadyMadeProduct(id, workspaceId, createdByUserId, target, availableQuantity);
    }

    /**
     * Applies one non-zero delta using boundary checks before addition.
     *
     * <p>The current integrated schema has no Order Item allocation facts, so
     * outstanding allocated quantity is zero and accounted physical quantity
     * equals this locked available quantity. A future approved commerce slice
     * must extend that calculation and serialize through the same Product row.
     * No negation of {@link Long#MIN_VALUE} is performed.
     */
    public ReadyMadeProduct applyManualQuantityDelta(long delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("Manual quantity delta must not be zero");
        }
        if (delta < 0 && delta < -availableQuantity) {
            throw new ReadyMadeProductManualQuantityDeltaRejectedException(
                    ManualQuantityDeltaRejectionReason.UNDERFLOW, availableQuantity);
        }
        if (delta > 0 && availableQuantity > Long.MAX_VALUE - delta) {
            throw new ReadyMadeProductManualQuantityDeltaRejectedException(
                    ManualQuantityDeltaRejectionReason.OVERFLOW, availableQuantity);
        }

        long resultingQuantity = availableQuantity + delta;
        return new ReadyMadeProduct(
                id, workspaceId, createdByUserId, status, resultingQuantity);
    }
}
