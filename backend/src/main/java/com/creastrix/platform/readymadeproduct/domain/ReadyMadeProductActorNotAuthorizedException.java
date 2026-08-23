package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

/** Raised when a lifecycle actor lacks effective Workspace-layer write access. */
public class ReadyMadeProductActorNotAuthorizedException extends RuntimeException {

    private final UUID readyMadeProductId;
    private final UUID workspaceId;
    private final UUID actorUserId;

    public ReadyMadeProductActorNotAuthorizedException(
            UUID readyMadeProductId, UUID workspaceId, UUID actorUserId) {
        super(("Ready-Made Product %s lifecycle transition requires effective "
                + "READY_MADE_PRODUCTS write authorization in Workspace %s, "
                + "but actor User %s has none")
                .formatted(readyMadeProductId, workspaceId, actorUserId));
        this.readyMadeProductId = readyMadeProductId;
        this.workspaceId = workspaceId;
        this.actorUserId = actorUserId;
    }

    public UUID readyMadeProductId() {
        return readyMadeProductId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID actorUserId() {
        return actorUserId;
    }
}
