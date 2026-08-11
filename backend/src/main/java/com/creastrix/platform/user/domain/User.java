package com.creastrix.platform.user.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A User: a stable platform identity with exactly one account and access
 * status.
 *
 * <p>This slice models only the identity and the status lifecycle. No
 * authentication or authorization data belongs to this type.
 */
public record User(UUID id, UserStatus status) {

    public User {
        Objects.requireNonNull(id, "User id must not be null");
        Objects.requireNonNull(status, "User status must not be null");
    }

    /**
     * Returns this User with the requested status, preserving identity.
     *
     * @throws InvalidUserStatusTransitionException if the requested transition
     *                                              is not supported
     */
    public User transitionTo(UserStatus target) {
        Objects.requireNonNull(target, "Target User status must not be null");
        if (!status.canTransitionTo(target)) {
            throw new InvalidUserStatusTransitionException(id, status, target);
        }
        return new User(id, target);
    }
}
