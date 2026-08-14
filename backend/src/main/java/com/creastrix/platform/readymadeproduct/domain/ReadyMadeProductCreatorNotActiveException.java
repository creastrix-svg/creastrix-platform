package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

import com.creastrix.platform.user.domain.UserStatus;

/**
 * Raised when Ready-Made Product creation is attempted by a creator User whose
 * current status is not ACTIVE. Both SUSPENDED and DEACTIVATED creators are
 * rejected, and the actual status is preserved.
 */
public class ReadyMadeProductCreatorNotActiveException extends RuntimeException {

    private final UUID creatorUserId;
    private final UserStatus creatorStatus;

    public ReadyMadeProductCreatorNotActiveException(UUID creatorUserId, UserStatus creatorStatus) {
        super("Ready-Made Product creation requires an ACTIVE creator User, but User %s is %s"
                .formatted(creatorUserId, creatorStatus));
        this.creatorUserId = creatorUserId;
        this.creatorStatus = creatorStatus;
    }

    public UUID creatorUserId() {
        return creatorUserId;
    }

    public UserStatus creatorStatus() {
        return creatorStatus;
    }
}
