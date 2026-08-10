# Organization Membership

## Purpose

An Organization Membership represents the relationship between a User and an Organization.

It represents the User's participation in an Organization, including the assigned role and membership status.

An Organization Membership exists independently from the User's memberships in Workspaces.

## Responsibilities

An Organization Membership is responsible for:

- linking a User to an Organization;
- storing the User's role within the Organization;
- storing the membership status.

## Relationships

An Organization Membership:

- belongs to exactly one User;
- belongs to exactly one Organization;
- has exactly one organizational role;
- has exactly one membership status.

## Business Rules

- A User may have multiple Organization Memberships.
- An Organization may have multiple Organization Memberships.
- A User cannot have more than one Organization Membership within the same Organization.
- An Organization Membership role determines permissions only within the Organization context.
- OWNER is the only organizational role whose domain semantics are defined by this specification version.
- An ACTIVE Organization Membership with the role OWNER contributes to the Organization's required ACTIVE OWNER invariant.
- Additional organizational roles and their permissions require explicit future specification before use.
- ACTIVE is the only Organization Membership status whose semantics are defined and required by this specification version.
- Only an ACTIVE Organization Membership with the role OWNER satisfies the Organization owner-preservation requirement.
- Additional status values and lifecycle transitions require explicit future specification before use.
- An ACTIVE Organization Membership with the role OWNER is the only currently defined structural source of general organization-level authority when no more specific domain delegation rule exists.
- An ordinary operation performed through OWNER authority requires both an ACTIVE User and that User's ACTIVE Organization Membership with the role OWNER, in addition to every more specific domain requirement.
- ACTIVE User status alone does not grant Organization authority.
- A SUSPENDED or DEACTIVATED User cannot exercise ordinary authority through an Organization Membership even when that Membership remains ACTIVE with the role OWNER.
- A User account status change never automatically changes Organization Membership status or role. An ACTIVE OWNER Membership continues to satisfy structural owner-preservation requirements regardless of whether its User is currently actionable.
- An ACTIVE OWNER may satisfy an explicit domain requirement to act on behalf of the Organization only when the associated User is ACTIVE and all other requirements are met.
- Future domain-specific delegation may authorize a non-OWNER actor only after that delegation is explicitly specified.
- An operation that would remove an ACTIVE OWNER Organization Membership, change its role from OWNER, or transition it out of ACTIVE may proceed only when every affected Workspace owned by the Organization retains at least one other User who simultaneously has an ACTIVE Organization Membership with the role OWNER in that Organization and an ACTIVE Workspace Membership with the role ADMIN in that Workspace. Different affected Workspaces may be protected by different replacement Users.
- Organization Membership does not automatically create a Workspace Membership, assign a Workspace role, grant any Workspace permission scope, or provide Workspace resource access.
- When no User has both ACTIVE User status and an ACTIVE OWNER Organization Membership, the Organization is operationally orphaned without any Membership being rewritten.
- After independent identity and business-control verification, an exceptional platform Organization recovery workflow may establish or restore the minimum actionable OWNER representation even though no ordinary Organization actor is available.
- Exceptional Organization recovery is a platform authority and is not granted by any Organization Membership role itself.

## Invariants

- Every Organization Membership belongs to exactly one User.
- Every Organization Membership belongs to exactly one Organization.
- Every Organization Membership always has exactly one role.
- Every Organization Membership always has exactly one status.
- An Organization Membership cannot exist without both its User and its Organization.
- Organization Membership status and role are never derived from the associated User's account status.
- Organization Membership alone never grants a Workspace role, Workspace permission scope, or Workspace resource access.

## Notes

Organization Membership represents the business relationship between a User and an Organization rather than a simple association.

A User's ordinary permissions within an Organization require ACTIVE User status and are then determined by the assigned organizational role and any more specific domain rules.

The Organization ACTIVE OWNER invariant is structural. It deliberately does not require the associated User to be ACTIVE, so an independently authorized security workflow may suspend or deactivate a malicious sole OWNER immediately without first rewriting Organization Memberships.

Workspace access is governed independently by Workspace Membership status, role, and permission scopes.

---

Status: APPROVED
Version: 1.4
