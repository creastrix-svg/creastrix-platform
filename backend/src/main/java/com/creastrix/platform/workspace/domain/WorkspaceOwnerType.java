package com.creastrix.platform.workspace.domain;

/**
 * Type of the single Workspace owner.
 *
 * <p>USER and ORGANIZATION are the only owner types defined by the approved
 * specification (Workspace APPROVED 1.0). A Workspace always has exactly one
 * owner of exactly one of these types.
 */
public enum WorkspaceOwnerType {
    USER,
    ORGANIZATION
}
