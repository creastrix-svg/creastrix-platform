package com.creastrix.platform.workspace.domain;

/**
 * A Workspace permission scope: a domain access area a Workspace Membership
 * may be granted access to.
 *
 * <p>PROJECTS, READY_MADE_PRODUCTS, and LISTINGS are exactly the permission
 * scopes recognized by the approved specification (Workspace Membership
 * APPROVED 1.0). The scopes are independent and never grant access to one
 * another. A future scope may be introduced only through a future versioned
 * Workspace architecture decision.
 *
 * <p>Permission scope is a domain concept within Workspace Membership, not a
 * separate domain entity.
 */
public enum WorkspacePermissionScope {
    PROJECTS,
    READY_MADE_PRODUCTS,
    LISTINGS
}
