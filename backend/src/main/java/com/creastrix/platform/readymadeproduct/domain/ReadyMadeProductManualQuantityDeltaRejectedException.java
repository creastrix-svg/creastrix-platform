package com.creastrix.platform.readymadeproduct.domain;

import java.util.Objects;

/** Internal checked-arithmetic decision used to persist a terminal rejection. */
public class ReadyMadeProductManualQuantityDeltaRejectedException extends RuntimeException {

    private final ManualQuantityDeltaRejectionReason reason;
    private final long observedAvailableQuantity;

    public ReadyMadeProductManualQuantityDeltaRejectedException(
            ManualQuantityDeltaRejectionReason reason, long observedAvailableQuantity) {
        super("Manual quantity delta rejected: " + Objects.requireNonNull(reason));
        this.reason = reason;
        this.observedAvailableQuantity = observedAvailableQuantity;
    }

    public ManualQuantityDeltaRejectionReason reason() {
        return reason;
    }

    public long observedAvailableQuantity() {
        return observedAvailableQuantity;
    }
}
