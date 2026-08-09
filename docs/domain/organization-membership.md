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
- An ACTIVE Organization Membership with the role OWNER is the only currently defined source of general organization-level authority for an operation performed on behalf of the Organization when no more specific domain delegation rule exists.
- An ACTIVE OWNER may satisfy an explicit domain requirement to act on behalf of the Organization.
- Future domain-specific delegation may authorize a non-OWNER actor only after that delegation is explicitly specified.
- An operation that would remove an ACTIVE OWNER Organization Membership, change its role from OWNER, or transition it out of ACTIVE may proceed only when every affected Workspace owned by the Organization retains at least one other User who simultaneously has an ACTIVE Organization Membership with the role OWNER in that Organization and an ACTIVE Workspace Membership with the role ADMIN in that Workspace. Different affected Workspaces may be protected by different replacement Users.
- Organization Membership does not automatically create a Workspace Membership, assign a Workspace role, grant any Workspace permission scope, or provide Workspace resource access.

## Invariants

- Every Organization Membership belongs to exactly one User.
- Every Organization Membership belongs to exactly one Organization.
- Every Organization Membership always has exactly one role.
- Every Organization Membership always has exactly one status.
- An Organization Membership cannot exist without both its User and its Organization.
- Organization Membership alone never grants a Workspace role, Workspace permission scope, or Workspace resource access.

## Notes

Organization Membership represents the business relationship between a User and an Organization rather than a simple association.

A User's permissions within an Organization are determined by the assigned organizational role.

Workspace access is governed independently by Workspace Membership status, role, and permission scopes.

---

Status: APPROVED
Version: 1.3
