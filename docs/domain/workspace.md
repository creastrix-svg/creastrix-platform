# Workspace

## Purpose

A Workspace represents an operational and commercial context for collaborative work on the Creastrix platform.

A Workspace groups Projects under a single owner while separating ownership from participant access.

## Responsibilities

A Workspace is responsible for:

- representing the operational context in which collaborative work takes place;
- grouping Projects under a single owner;
- identifying the platform-recognized business rights holder for Projects inside the Workspace in MVP;
- managing participant access through Workspace Memberships;
- ensuring that the Workspace remains actively administered.

## Relationships

A Workspace:

- belongs to exactly one owner, which is either one User or one Organization;
- contains zero or more Projects;
- has zero or more Workspace Memberships;
- is administered by at least one active Workspace Membership with the role ADMIN;
- exists independently from Organization Memberships.

## Business Rules

- A Workspace owner must be either a User or an Organization, but cannot be both.
- A Workspace owner cannot be changed in MVP.
- A Workspace cannot exist without an owner.
- When a User-owned Workspace is created, the owner receives an active Workspace Membership with the role ADMIN.
- When an Organization-owned Workspace is created, the User creating the Workspace on behalf of the Organization receives an active Workspace Membership with the role ADMIN.
- A Workspace cannot be left without an active administrator.
- Access to a Workspace is managed through Workspace Memberships.
- Organization Membership does not automatically provide access to a Workspace.
- A Workspace is not an Organization and does not replace Organization Membership.
- Ownership of a Workspace and access to a Workspace are separate concepts.
- The Workspace owner is the platform-recognized business rights holder for Projects in the Workspace unless future domain rules explicitly define otherwise.
- In MVP, Projects do not have a separate Business Owner and no exception to Workspace-derived business rights is supported.
- The ownership rule applies only to Projects created or transferred into the Workspace under platform rules and does not apply automatically to all content stored in the Workspace.

## Invariants

- A Workspace always has exactly one owner.
- A Workspace owner is always either exactly one User or exactly one Organization.
- A Workspace owner remains unchanged throughout the MVP lifecycle of the Workspace.
- A Workspace always has at least one active administrator.
- Every Workspace Membership of a Workspace belongs to that Workspace only.
- Workspace Membership does not change the owner of the Workspace or the business rights holder for its Projects.
- Organization Membership alone never grants access to a Workspace.

## Notes

For the future Project specification, every Project belongs to exactly one Workspace and does not have a separate Business Owner in MVP. The Effective Business Rights Holder of a Project is derived from the Workspace owner.

Project Created By records origin and history. It does not automatically provide ownership, publication rights, or royalty rights.

The zero-or-more relationship describes the Workspace Membership collection independently, while every valid Workspace must satisfy the invariant requiring at least one active administrator.

The exact Workspace Membership role-to-permission matrix remains to be approved in the Workspace Membership specification.

---

Status: DRAFT

Version: 0.1
