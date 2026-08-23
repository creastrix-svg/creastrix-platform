package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

import com.creastrix.platform.user.domain.UserStatus;

/** Raised when a lifecycle actor User exists but is not ACTIVE. */
public class ReadyMadeProductActorNotActiveException extends RuntimeException {

    private final UUID actorUserId;
    private final UserStatus actualStatus;

    public ReadyMadeProductActorNotActiveException(UUID actorUserId, UserStatus actualStatus) {
        super("Ready-Made Product lifecycle transition requires an ACTIVE actor User, "
                + "but User %s is %s".formatted(actorUserId, actualStatus));
        this.actorUserId = actorUserId;
        this.actualStatus = actualStatus;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public UserStatus actualStatus() {
        return actualStatus;
    }
}
