package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

/** Raised when a requested Ready-Made Product lifecycle transition is invalid. */
public class InvalidReadyMadeProductStatusTransitionException extends RuntimeException {

    private final UUID readyMadeProductId;
    private final ReadyMadeProductStatus currentStatus;
    private final ReadyMadeProductStatus targetStatus;

    public InvalidReadyMadeProductStatusTransitionException(
            UUID readyMadeProductId,
            ReadyMadeProductStatus currentStatus,
            ReadyMadeProductStatus targetStatus) {
        super("Ready-Made Product %s cannot transition from %s to %s"
                .formatted(readyMadeProductId, currentStatus, targetStatus));
        this.readyMadeProductId = readyMadeProductId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public UUID readyMadeProductId() {
        return readyMadeProductId;
    }

    public ReadyMadeProductStatus currentStatus() {
        return currentStatus;
    }

    public ReadyMadeProductStatus targetStatus() {
        return targetStatus;
    }
}
