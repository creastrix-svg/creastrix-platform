package com.creastrix.platform.readymadeproduct.domain;

import java.util.UUID;

/**
 * Raised when Ready-Made Product creation is attempted by a creator User who
 * has no effective Workspace-layer write authorization for the
 * READY_MADE_PRODUCTS scope in the target Workspace.
 *
 * <p>This covers a missing Membership, a non-ACTIVE Membership, a VIEWER
 * Membership, and an EDITOR Membership without an explicit
 * READY_MADE_PRODUCTS grant. The exception deliberately carries only the
 * Workspace and the creator identity: the concrete reason is not exposed as
 * domain payload.
 */
public class ReadyMadeProductCreatorNotAuthorizedException extends RuntimeException {

    private final UUID workspaceId;
    private final UUID creatorUserId;

    public ReadyMadeProductCreatorNotAuthorizedException(UUID workspaceId, UUID creatorUserId) {
        super(("Ready-Made Product creation requires effective READY_MADE_PRODUCTS write "
                + "authorization in Workspace %s, but User %s has none")
                .formatted(workspaceId, creatorUserId));
        this.workspaceId = workspaceId;
        this.creatorUserId = creatorUserId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID creatorUserId() {
        return creatorUserId;
    }
}
