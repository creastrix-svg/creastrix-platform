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
- preserving the required structural administrative representation.

## Relationships

A Workspace:

- belongs to exactly one owner, which is either one User or one Organization;
- contains zero or more Projects;
- contains zero or more Ready-Made Products;
- has one or more Workspace Memberships;
- retains at least one ACTIVE Workspace Membership with the role ADMIN;
- exists independently from Organization Memberships.

## Business Rules

- A Workspace owner must be either a User or an Organization, but cannot be both.
- A Workspace owner cannot be changed in MVP.
- A Workspace cannot exist without an owner.
- Workspace creation and creation of its initial ACTIVE ADMIN Workspace Membership are atomic.
- A User-owned Workspace may be created only by its owner while that owner is an ACTIVE User acting for that same User.
- Creating a User-owned Workspace owned by another User is unsupported in MVP.
- When a User-owned Workspace is created, the owner receives an ACTIVE Workspace Membership with the role ADMIN.
- In a User-owned Workspace, the User owner must remain an ACTIVE ADMIN for as long as the User remains the Workspace owner in MVP.
- The User owner's Workspace Membership cannot be removed, suspended, or changed from ADMIN through normal Workspace Membership administration while ownership remains unchanged.
- An Organization-owned Workspace may be created only by an ACTIVE User who has an ACTIVE Organization Membership with the role OWNER in the owning Organization.
- The ACTIVE OWNER creating an Organization-owned Workspace receives the initial ACTIVE Workspace Membership with the role ADMIN.
- Every Organization-owned Workspace must always retain at least one User who simultaneously has an ACTIVE Organization Membership with the role OWNER in the owning Organization and an ACTIVE Workspace Membership with the role ADMIN in that Workspace.
- The Organization OWNER and Workspace ADMIN intersection is structural and does not require the User account itself to be ACTIVE. Ordinary Workspace administration through that intersection additionally requires the User to be ACTIVE.
- In an Organization-owned Workspace, individual ADMIN Users may be replaced as long as the Workspace retains at least one ACTIVE Workspace Membership with the role ADMIN and at least one User satisfying the Organization ACTIVE OWNER and Workspace ACTIVE ADMIN requirement.
- If a User is the last User satisfying both requirements for an Organization-owned Workspace, no operation may cause that User to stop satisfying either requirement until that Workspace has another User satisfying both.
- External Users and non-OWNER Organization Users may hold independently granted Workspace ADMIN Memberships, but they cannot be the only remaining administrative representation of the owning Organization.
- A Workspace cannot be left without an ACTIVE administrator.
- Access to Workspace resources is managed through scoped Workspace Memberships.
- Effective ordinary Workspace authorization requires ACTIVE User status, ACTIVE Workspace Membership status, the required Workspace role, the relevant permission scope, and satisfaction of the requested domain operation's rules.
- ACTIVE User status alone does not provide Workspace access or business authority.
- A SUSPENDED or DEACTIVATED User cannot exercise ordinary Workspace authority even when the User's Workspace Membership remains ACTIVE.
- A User account status change does not automatically mutate Workspace Membership status, role, or permission scopes and does not change Workspace ownership.
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
- If the User owner of a User-owned Workspace is SUSPENDED or DEACTIVATED, the owner remains the structurally ACTIVE ADMIN and Workspace ownership remains unchanged, but ordinary Workspace authority through that User is unavailable until the User becomes ACTIVE again.
- User-owned Workspace ownership cannot be transferred through account suspension, deactivation, or Workspace Membership administration in MVP. No special personal Workspace recovery or ownership-transfer mechanism is introduced by this specification version.
- Workspace deletion and Workspace archival are unsupported in MVP.
- This specification introduces no Workspace deletion or archival lifecycle state.
- An Organization-owned Workspace may remain structurally valid while its required Organization OWNER and Workspace ADMIN representation is not actionable because the associated User is not ACTIVE.
- Exceptional platform Organization recovery may establish the minimum actionable Organization OWNER and Workspace ADMIN representation for an affected Organization-owned Workspace after the required independent verification.
- User status constrains authority exercised by that User but does not by itself block independently authorized platform workflows, including internal security, moderation, reconciliation, finance, Ready-Made Product fulfillment, or Organization recovery workflows.

## Invariants

- A Workspace always has exactly one owner.
- A Workspace owner is always either exactly one User or exactly one Organization.
- A Workspace owner remains unchanged throughout the MVP lifecycle of the Workspace.
- A User-owned Workspace always has its owner as an ACTIVE ADMIN.
- A Workspace always has at least one ACTIVE administrator.
- An Organization-owned Workspace always has at least one User who is both an ACTIVE Organization OWNER and an ACTIVE Workspace ADMIN for that Workspace.
- Every Workspace Membership of a Workspace belongs to that Workspace only.
- Workspace Membership roles and permission scopes do not change the owner of the Workspace, the business rights holder for its Projects, or the commercial context of its Ready-Made Products.
- Organization Membership alone never grants access to a Workspace.

## Notes

Every Project belongs to exactly one Workspace and does not have a separate Business Owner in MVP. The Effective Business Rights Holder of a Project is derived from the Workspace owner.

Project Created By records origin and history. It does not automatically provide ownership, publication rights, or royalty rights.

PROJECTS is the permission scope for the Project and Revision domain area. READY_MADE_PRODUCTS is the permission scope for Ready-Made Product management, including simple MVP available quantity. LISTINGS is the permission scope for Listing commercial management. The scopes are independent.

A Workspace is not conceptually limited to design work and does not require DESIGN, STORE, or WAREHOUSE Workspace types merely to separate authorization. A User or Organization may still create multiple Workspaces when separate operational contexts are useful.

Projects and Ready-Made Products are the currently specified resource relationships. No relationship to another future domain entity is implied by the broader Workspace boundary.

Workspace creation and its initial ACTIVE ADMIN Membership are atomic, so every valid Workspace has one or more Workspace Memberships.

ACTIVE ADMIN requirements in this specification are structural Membership invariants. Effective ordinary administration additionally requires an ACTIVE associated User.

A User may retain an independently granted Workspace Membership after leaving an Organization, subject to future platform policy. Such a User cannot be the sole User satisfying the Organization ACTIVE OWNER and Workspace ACTIVE ADMIN invariant.

Any future Workspace deletion, archival, or retention policy requires separate architecture work. It must preserve stable referenced identities and historical context and must not implicitly introduce destructive cascading into Projects, Ready-Made Products, Listings, confirmed commerce, or immutable historical snapshots.

---

Status: APPROVED

Version: 1.0
