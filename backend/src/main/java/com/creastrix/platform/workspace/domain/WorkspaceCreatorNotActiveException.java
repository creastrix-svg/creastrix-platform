package com.creastrix.platform.workspace.domain;

import java.util.UUID;

import com.creastrix.platform.user.domain.UserStatus;

/**
 * Raised when ordinary Workspace creation is attempted by a creator User whose
 * current status is not ACTIVE.
 */
public class WorkspaceCreatorNotActiveException extends RuntimeException {

    private final UUID creatorUserId;
    private final UserStatus creatorStatus;

    public WorkspaceCreatorNotActiveException(UUID creatorUserId, UserStatus creatorStatus) {
        super("Workspace creation requires an ACTIVE creator User, but User %s is %s"
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
