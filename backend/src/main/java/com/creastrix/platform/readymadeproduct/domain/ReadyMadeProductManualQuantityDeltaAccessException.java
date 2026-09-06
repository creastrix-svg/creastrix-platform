package com.creastrix.platform.readymadeproduct.domain;

/**
 * Opaque supported-path failure used before any command lookup or disclosure.
 *
 * <p>The exception deliberately carries no Product, Workspace, actor, command,
 * binding, state, or outcome data. A future HTTP boundary must choose its own
 * response contract rather than treating this internal type as that contract.
 */
public class ReadyMadeProductManualQuantityDeltaAccessException extends RuntimeException {

    public ReadyMadeProductManualQuantityDeltaAccessException() {
        super("Manual quantity-delta operation is not accessible");
    }
}
