package com.creastrix.platform.user.domain;

import java.util.UUID;

/** Raised when a requested User status transition is not supported. */
public class InvalidUserStatusTransitionException extends RuntimeException {

    private final UUID userId;
    private final UserStatus currentStatus;
    private final UserStatus targetStatus;

    public InvalidUserStatusTransitionException(UUID userId, UserStatus currentStatus, UserStatus targetStatus) {
        super("Unsupported User status transition %s -> %s for User %s"
                .formatted(currentStatus, targetStatus, userId));
        this.userId = userId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public UUID userId() {
        return userId;
    }

    public UserStatus currentStatus() {
        return currentStatus;
    }

    public UserStatus targetStatus() {
        return targetStatus;
    }
}
