package com.creastrix.platform.workspace.domain;

/**
 * Status of a Workspace Membership.
 *
 * <p>INVITED, ACTIVE, and SUSPENDED are the only Workspace Membership statuses
 * defined by the approved specification (Workspace Membership APPROVED 1.0).
 * INVITED represents pending access and SUSPENDED represents temporarily
 * disabled access; their transitions and invitation, suspension, and
 * restoration rules remain to be specified and are not implemented here.
 */
public enum WorkspaceMembershipStatus {
    INVITED,
    ACTIVE,
    SUSPENDED
}
