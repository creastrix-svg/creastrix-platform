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

Roles and membership statuses are currently implemented as enumerations and may evolve into dedicated domain concepts in future versions.

A User's permissions within an Organization are determined by the assigned organizational role.

Workspace access is governed independently by Workspace Membership status, role, and permission scopes.

---

Status: APPROVED
Version: 1.1
