package com.creastrix.platform.readymadeproduct.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable application read model for one Product-scoped manual delta.
 *
 * <p>This is an internal command result, not a new core domain entity or an
 * inventory movement. Its nullable fields follow the same closed outcome
 * shape enforced structurally by Flyway V8.
 */
public record ManualQuantityDeltaResult(
        UUID readyMadeProductId,
        UUID commandId,
        long delta,
        ManualQuantityDeltaCommandState state,
        Long resultingAvailableQuantity,
        ManualQuantityDeltaRejectionReason rejectionReason,
        Long observedAvailableQuantity) {

    public ManualQuantityDeltaResult {
        Objects.requireNonNull(readyMadeProductId, "Ready-Made Product id must not be null");
        Objects.requireNonNull(commandId, "Manual quantity-delta command id must not be null");
        Objects.requireNonNull(state, "Manual quantity-delta command state must not be null");
        if (delta == 0) {
            throw new IllegalArgumentException("Manual quantity delta must not be zero");
        }

        boolean validShape = switch (state) {
            case REGISTERED -> resultingAvailableQuantity == null
                    && rejectionReason == null
                    && observedAvailableQuantity == null;
            case APPLIED -> resultingAvailableQuantity != null
                    && resultingAvailableQuantity >= 0
                    && rejectionReason == null
                    && observedAvailableQuantity == null;
            case REJECTED -> resultingAvailableQuantity == null
                    && rejectionReason != null
                    && observedAvailableQuantity != null
                    && observedAvailableQuantity >= 0;
        };
        if (!validShape) {
            throw new IllegalArgumentException(
                    "Manual quantity-delta result does not match state " + state);
        }
        if (state == ManualQuantityDeltaCommandState.REJECTED
                && ((delta < 0 && rejectionReason != ManualQuantityDeltaRejectionReason.UNDERFLOW)
                    || (delta > 0 && rejectionReason != ManualQuantityDeltaRejectionReason.OVERFLOW))) {
            throw new IllegalArgumentException(
                    "Manual quantity-delta rejection reason does not match delta sign");
        }
    }

    public static ManualQuantityDeltaResult registered(
            UUID readyMadeProductId, UUID commandId, long delta) {
        return new ManualQuantityDeltaResult(
                readyMadeProductId, commandId, delta,
                ManualQuantityDeltaCommandState.REGISTERED,
                null, null, null);
    }

    public static ManualQuantityDeltaResult applied(
            UUID readyMadeProductId, UUID commandId, long delta, long resultingQuantity) {
        return new ManualQuantityDeltaResult(
                readyMadeProductId, commandId, delta,
                ManualQuantityDeltaCommandState.APPLIED,
                resultingQuantity, null, null);
    }

    public static ManualQuantityDeltaResult rejected(
            UUID readyMadeProductId,
            UUID commandId,
            long delta,
            ManualQuantityDeltaRejectionReason reason,
            long observedQuantity) {
        return new ManualQuantityDeltaResult(
                readyMadeProductId, commandId, delta,
                ManualQuantityDeltaCommandState.REJECTED,
                null, reason, observedQuantity);
    }
}
