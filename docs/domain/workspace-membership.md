# Workspace Membership

## Purpose

A Workspace Membership represents a User's scoped access relationship with a Workspace.

It keeps membership status, role, granted domain permission scopes, and effective authorization separate from Workspace ownership, commercial rights, and participation in an Organization.

## Responsibilities

A Workspace Membership is responsible for:

- linking a User to a Workspace;
- storing the User's role within the Workspace;
- storing the membership status;
- recording granted permission scopes;
- providing the basis for determining effective Workspace authorization.

## Relationships

A Workspace Membership:

- belongs to exactly one User;
- belongs to exactly one Workspace;
- has exactly one Workspace role;
- has exactly one membership status;
- has zero or more granted permission scopes;
- exists independently from Organization Membership.

## Business Rules

- A User may have multiple Workspace Memberships across different Workspaces.
- A Workspace may have multiple Workspace Memberships.
- A User cannot have more than one Workspace Membership within the same Workspace.
- When a User-owned Workspace is created, the owner receives an ACTIVE Workspace Membership with the role ADMIN.
- An Organization-owned Workspace may be created only by a User with an ACTIVE Organization Membership with the role OWNER in the owning Organization; that User receives the initial ACTIVE Workspace Membership with the role ADMIN.
- A Workspace Membership role must be ADMIN, EDITOR, or VIEWER in MVP.
- OWNER is not a Workspace Membership role; ownership is represented separately by the Workspace owner.
- A Workspace Membership status must be INVITED, ACTIVE, or SUSPENDED in MVP.
- Only an ACTIVE Workspace Membership provides effective Workspace resource access.
- Effective authorization is determined by membership status, Workspace role, the relevant granted permission scope, and rules of the requested domain operation.
- An ACTIVE ADMIN has full administrative access to the Workspace and effective access to all current Workspace permission scopes in MVP without requiring individual scope grants.
- Only an ACTIVE ADMIN may manage Workspace Memberships, assign Workspace roles, or add and remove scope grants, subject to existing administrator-protection rules.
- An ACTIVE EDITOR has read and write capability only within explicitly granted scopes and cannot manage Membership roles or scope grants solely because of the EDITOR role.
- An ACTIVE VIEWER has read-only access only within explicitly granted scopes and never receives write capability.
- A non-ADMIN role alone does not grant access to every Workspace domain area.
- Permission for a scope does not bypass stricter rules or invariants of an individual domain operation.
- PROJECTS is a currently defined permission scope and covers the Project and Revision domain area.
- READY_MADE_PRODUCTS is a currently defined permission scope and covers Ready-Made Product management, including simple MVP available quantity.
- LISTINGS is a currently defined permission scope and covers Listing commercial management, including commercial presentation, pricing information, activation, pause, archive, and current offer terms.
- PROJECTS, READY_MADE_PRODUCTS, and LISTINGS are independent scopes and do not grant access to one another.
- READY_MADE_PRODUCTS does not grant Listing price management, Order management, finance, Warehouse, future Inventory administration, or seller eligibility.
- LISTINGS does not grant Project or Revision editing, Ready-Made Product editing, Order management, finance, payouts, Inventory, Warehouse, manufacturing, or seller eligibility.
- LISTINGS authorization may expose only source information needed to identify and validate a Listing commercial source without granting general source-domain access.
- Future permission scopes are introduced only when their corresponding domain areas are designed.
- A newly introduced permission scope is not granted automatically to existing EDITOR or VIEWER Memberships.
- Existing EDITOR and VIEWER Memberships do not receive READY_MADE_PRODUCTS automatically when the scope is introduced.
- Existing EDITOR and VIEWER Memberships do not receive LISTINGS automatically when the scope is introduced.
- Organization Membership does not automatically create a Workspace Membership, assign a Workspace role, grant Workspace permission scopes, or provide Workspace resource access.
- Workspace Membership roles and scope grants do not provide Workspace ownership or commercial rights.
- A Workspace Membership does not replace Organization Membership.
- If a Workspace is User-owned, the Workspace Membership belonging to the User owner cannot be removed, suspended, or changed from ADMIN while ownership remains unchanged in MVP.
- Protection of the User owner's Workspace Membership applies independently from the last-ACTIVE-ADMIN rule.
- An Organization-owned Workspace has no equivalent permanent individual-owner Membership; ADMIN Users may change as long as the Workspace retains at least one ACTIVE ADMIN.
- Every Organization-owned Workspace must always have at least one User who simultaneously has an ACTIVE Organization Membership with the role OWNER in the owning Organization and an ACTIVE Workspace Membership with the role ADMIN in that Workspace.
- If a Workspace Membership belongs to the last User satisfying both requirements for an Organization-owned Workspace, that Membership cannot be removed, suspended, or changed from ADMIN until that Workspace has another User satisfying both requirements.
- External Users and non-OWNER Organization Users may hold independently granted Workspace ADMIN Memberships, but they cannot be the sole User satisfying the Organization ACTIVE OWNER and Workspace ACTIVE ADMIN requirement.
- The last ACTIVE Workspace Membership with the role ADMIN cannot be removed, suspended, or assigned another role until another active administrator exists.

## Invariants

- Every Workspace Membership always belongs to exactly one User.
- Every Workspace Membership always belongs to exactly one Workspace.
- Every Workspace Membership always has exactly one role.
- Every Workspace Membership always has exactly one status.
- The pair of User and Workspace is always unique.
- A Workspace Membership role is always ADMIN, EDITOR, or VIEWER in MVP.
- A Workspace Membership never has the role OWNER.
- A Workspace Membership status is always INVITED, ACTIVE, or SUSPENDED in MVP.
- Only an ACTIVE Workspace Membership can produce effective Workspace resource access.
- An ACTIVE VIEWER never has write access.
- Effective access for an EDITOR or VIEWER never extends outside explicitly granted scopes.
- Introducing a future permission scope never expands the effective access of existing EDITOR or VIEWER Memberships automatically.
- The User owner of a User-owned Workspace always has an ACTIVE ADMIN Workspace Membership.
- An Organization-owned Workspace always has at least one User who is both an ACTIVE Organization OWNER and an ACTIVE Workspace ADMIN for that Workspace.
- Workspace Membership roles and permission scopes never change Workspace ownership, the business rights holder for Projects, the commercial context of Ready-Made Products, or the source-derived Workspace context of Listings.
- Organization Membership alone never grants a Workspace role, Workspace permission scope, or Workspace resource access.

## Notes

Permission scope is a domain concept within Workspace Membership and is not a separate domain entity in MVP.

The currently defined Workspace permission scopes are:

- PROJECTS for Project and Revision work;
- READY_MADE_PRODUCTS for Ready-Made Product management, including simple MVP available quantity;
- LISTINGS for Listing commercial management.

Exact operation-level authorization may be refined by individual domain specifications, whose lifecycle rules and invariants remain authoritative. Order management and finance are outside the current Workspace permission scopes. Warehouse and Inventory remain future domain concerns. None of these areas is automatically granted by the current scopes.

Domain specialization is expressed through permission scopes rather than additional domain-specific Workspace roles.

In MVP, the Membership role applies uniformly across all granted scopes. More granular per-scope capability levels may be considered later only if concrete domain requirements require them.

INVITED represents pending access and SUSPENDED represents temporarily disabled access. Their allowed transitions and detailed invitation, suspension, and restoration rules remain to be specified.

A User may retain an independently granted Workspace Membership after leaving an Organization, subject to future platform policy. Such a User cannot be the sole User satisfying the Organization ACTIVE OWNER and Workspace ACTIVE ADMIN requirement.

---

Status: DRAFT

Version: 0.7
