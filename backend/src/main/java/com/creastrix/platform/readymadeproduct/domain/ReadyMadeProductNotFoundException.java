package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

/** Raised when no Ready-Made Product exists for the requested identity. */
public class ReadyMadeProductNotFoundException extends RuntimeException {

    private final UUID readyMadeProductId;

    public ReadyMadeProductNotFoundException(UUID readyMadeProductId) {
        super("Ready-Made Product %s not found".formatted(readyMadeProductId));
        this.readyMadeProductId = readyMadeProductId;
    }

    public UUID readyMadeProductId() {
        return readyMadeProductId;
    }
}
