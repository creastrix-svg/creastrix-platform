package com.creastrix.platform.readymadeproduct.domain;

/** The authorized Product-scoped command pair has no durable registration. */
public class ReadyMadeProductManualQuantityDeltaNotRegisteredException extends RuntimeException {

    public ReadyMadeProductManualQuantityDeltaNotRegisteredException() {
        super("Manual quantity-delta command is not registered");
    }
}
