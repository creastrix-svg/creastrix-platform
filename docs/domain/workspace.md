# Workspace

## Purpose

A Workspace represents an operational, ownership, and authorization boundary for collaborative work on the Creastrix platform.

A Workspace may support multiple domain access areas under a single owner while separating ownership from participant authorization. Projects and Ready-Made Products are the currently specified resource relationships.

## Responsibilities

A Workspace is responsible for:

- representing a common operational and authorization context for collaborative work;
- grouping Projects and Ready-Made Products within a single operational boundary;
- identifying the platform-recognized business rights holder for Projects inside the Workspace in MVP;
- providing the platform-recognized commercial context in which Ready-Made Products are managed;
- managing participant access through scoped Workspace Membership authorization;
- supporting distinct domain access areas without mixing participant authorization with ownership;
- ensuring that the Workspace remains actively administered.

## Relationships

A Workspace:

- belongs to exactly one owner, which is either one User or one Organization;
- contains zero or more Projects;
- contains zero or more Ready-Made Products;
- has zero or more Workspace Memberships;
- is administered by at least one ACTIVE Workspace Membership with the role ADMIN;
- exists independently from Organization Memberships.

## Business Rules

- A Workspace owner must be either a User or an Organization, but cannot be both.
- A Workspace owner cannot be changed in MVP.
- A Workspace cannot exist without an owner.
- When a User-owned Workspace is created, the owner receives an ACTIVE Workspace Membership with the role ADMIN.
- In a User-owned Workspace, the User owner must remain an ACTIVE ADMIN for as long as the User remains the Workspace owner in MVP.
- The User owner's Workspace Membership cannot be removed, suspended, or changed from ADMIN through normal Workspace Membership administration while ownership remains unchanged.
- When an Organization-owned Workspace is created, the User creating the Workspace on behalf of the Organization receives an ACTIVE Workspace Membership with the role ADMIN.
- In an Organization-owned Workspace, individual ADMIN Users may be replaced as long as the Workspace retains at least one ACTIVE Workspace Membership with the role ADMIN.
- A Workspace cannot be left without an ACTIVE administrator.
- Access to Workspace resources is managed through scoped Workspace Memberships.
- Effective Workspace authorization is determined by Membership status, Workspace role, the relevant permission scope, and rules of the requested domain operation.
- A non-ADMIN Workspace role alone does not provide access to every Workspace resource or domain area.
- Different Workspace domain areas may use different permission scopes.
- A newly introduced permission scope is not granted automatically to existing EDITOR or VIEWER Memberships.
- Organization Membership does not automatically provide access to a Workspace.
- A Workspace is not an Organization and does not replace Organization Membership.
- Ownership of a Workspace and access to a Workspace are separate concepts.
- The Workspace owner is the platform-recognized business rights holder for Projects in the Workspace unless future domain rules explicitly define otherwise.
- In MVP, Projects do not have a separate Business Owner and no exception to Workspace-derived business rights is supported.
- The ownership rule applies only to Projects created or transferred into the Workspace under platform rules and does not apply automatically to all content stored in the Workspace.
- The Workspace owner provides the platform-recognized commercial context in which Ready-Made Products in that Workspace are managed.
- The commercial context of a Ready-Made Product does not establish legal title, physical custody, seller-of-record, manufacturer, supplier, importer, brand ownership, or intellectual-property ownership.
- The Workspace context for Listing authorization is derived from the Listing's immutable commercial source; Listing has no separate Workspace ownership relationship in MVP.

## Invariants

- A Workspace always has exactly one owner.
- A Workspace owner is always either exactly one User or exactly one Organization.
- A Workspace owner remains unchanged throughout the MVP lifecycle of the Workspace.
- A User-owned Workspace always has its owner as an ACTIVE ADMIN.
- A Workspace always has at least one ACTIVE administrator.
- Every Workspace Membership of a Workspace belongs to that Workspace only.
- Workspace Membership roles and permission scopes do not change the owner of the Workspace, the business rights holder for its Projects, or the commercial context of its Ready-Made Products.
- Organization Membership alone never grants access to a Workspace.

## Notes

Every Project belongs to exactly one Workspace and does not have a separate Business Owner in MVP. The Effective Business Rights Holder of a Project is derived from the Workspace owner.

Project Created By records origin and history. It does not automatically provide ownership, publication rights, or royalty rights.

PROJECTS is the permission scope for the Project and Revision domain area. READY_MADE_PRODUCTS is the permission scope for Ready-Made Product management, including simple MVP available quantity. LISTINGS is the permission scope for Listing commercial management. The scopes are independent.

A Workspace is not conceptually limited to design work and does not require DESIGN, STORE, or WAREHOUSE Workspace types merely to separate authorization. A User or Organization may still create multiple Workspaces when separate operational contexts are useful.

Projects and Ready-Made Products are the currently specified resource relationships. No relationship to another future domain entity is implied by the broader Workspace boundary.

The zero-or-more relationship describes the Workspace Membership collection independently, while every valid Workspace must satisfy the invariant requiring at least one active administrator.

---

Status: DRAFT

Version: 0.5
