package com.creastrix.platform.user.domain;

import java.util.UUID;

/** Raised when no User exists for the requested identity. */
public class UserNotFoundException extends RuntimeException {

    private final UUID userId;

    public UserNotFoundException(UUID userId) {
        super("User %s not found".formatted(userId));
        this.userId = userId;
    }

    public UUID userId() {
        return userId;
    }
}
