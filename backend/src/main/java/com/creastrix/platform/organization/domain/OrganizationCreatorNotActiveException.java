package com.creastrix.platform.organization.domain;

import java.util.UUID;

import com.creastrix.platform.user.domain.UserStatus;

/**
 * Raised when ordinary Organization creation is attempted by a creator User
 * whose current status is not ACTIVE.
 */
public class OrganizationCreatorNotActiveException extends RuntimeException {

    private final UUID creatorUserId;
    private final UserStatus creatorStatus;

    public OrganizationCreatorNotActiveException(UUID creatorUserId, UserStatus creatorStatus) {
        super("Organization creation requires an ACTIVE creator User, but User %s is %s"
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
