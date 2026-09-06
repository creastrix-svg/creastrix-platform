package com.creastrix.platform.readymadeproduct.domain;

/** The supplied delta differs from the immutable binding of the exact pair. */
public class ReadyMadeProductManualQuantityDeltaPayloadMismatchException extends RuntimeException {

    public ReadyMadeProductManualQuantityDeltaPayloadMismatchException() {
        super("Manual quantity-delta command payload does not match its durable binding");
    }
}
