package com.creastrix.platform.workspace.domain;

import java.util.UUID;

/** Raised when no Workspace exists for the requested identity. */
public class WorkspaceNotFoundException extends RuntimeException {

    private final UUID workspaceId;

    public WorkspaceNotFoundException(UUID workspaceId) {
        super("Workspace %s not found".formatted(workspaceId));
        this.workspaceId = workspaceId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }
}
