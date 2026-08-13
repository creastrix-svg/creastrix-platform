package com.creastrix.platform.workspace.domain;

/**
 * Role of a User within a Workspace.
 *
 * <p>ADMIN, EDITOR, and VIEWER are the only Workspace roles defined by the
 * approved specification (Workspace Membership APPROVED 1.0). OWNER is
 * deliberately not a Workspace role: ownership is represented separately by
 * the Workspace owner.
 */
public enum WorkspaceRole {
    ADMIN,
    EDITOR,
    VIEWER
}
