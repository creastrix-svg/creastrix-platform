# Workspace Membership

## Purpose

A Workspace Membership represents a User's access relationship with a Workspace.

It keeps access to collaborative work separate from Workspace ownership, commercial rights, and participation in an Organization.

## Responsibilities

A Workspace Membership is responsible for:

- linking a User to a Workspace;
- storing the User's role within the Workspace;
- storing the membership status;
- providing the basis for determining the User's access within the Workspace.

## Relationships

A Workspace Membership:

- belongs to exactly one User;
- belongs to exactly one Workspace;
- has exactly one Workspace role;
- has exactly one membership status;
- exists independently from Organization Membership.

## Business Rules

- A User may have multiple Workspace Memberships across different Workspaces.
- A Workspace may have multiple Workspace Memberships.
- A User cannot have more than one Workspace Membership within the same Workspace.
- When a User-owned Workspace is created, the owner receives an active Workspace Membership with the role ADMIN.
- When an Organization-owned Workspace is created, the User creating the Workspace on behalf of the Organization receives an active Workspace Membership with the role ADMIN.
- A Workspace Membership role must be ADMIN, EDITOR, or VIEWER in MVP.
- OWNER is not a Workspace Membership role.
- Ownership is represented separately by the Workspace owner.
- A Workspace Membership status must be INVITED, ACTIVE, or SUSPENDED in MVP.
- Only an ACTIVE Workspace Membership provides Workspace access.
- Whether a Workspace Membership provides access depends on its role and status.
- Organization Membership does not automatically create a Workspace Membership or provide Workspace access.
- A Workspace Membership does not provide ownership or commercial rights.
- A Workspace Membership does not replace Organization Membership.
- The last active Workspace Membership with the role ADMIN cannot be removed, suspended, or assigned another role until another active administrator exists.

## Invariants

- Every Workspace Membership always belongs to exactly one User.
- Every Workspace Membership always belongs to exactly one Workspace.
- Every Workspace Membership always has exactly one role.
- Every Workspace Membership always has exactly one status.
- The pair of User and Workspace is always unique.
- A Workspace Membership role is always ADMIN, EDITOR, or VIEWER in MVP.
- A Workspace Membership never has the role OWNER.
- A Workspace Membership status is always INVITED, ACTIVE, or SUSPENDED in MVP.
- A Workspace Membership cannot exist without both its User and its Workspace.
- A Workspace Membership never changes Workspace ownership or the business rights holder for Projects.

## Notes

The exact permission matrix for ADMIN, EDITOR, and VIEWER remains to be approved.

For MVP, Workspace Membership represents both pending invitations and temporary access suspension.

The MVP statuses are:

- INVITED;
- ACTIVE;
- SUSPENDED.

The allowed status transitions and the rules for accepting invitations, suspending access, and restoring access remain to be approved.

The policy for external Users in Organization-owned Workspaces remains to be approved.

---

Status: DRAFT

Version: 0.1
