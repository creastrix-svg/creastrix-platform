# Organization Membership

## Purpose

An Organization Membership represents the relationship between a User and an Organization.

It defines the User's participation in an Organization, including the assigned role and membership status.

An Organization Membership exists independently from the User's memberships in Workspaces.

## Responsibilities

An Organization Membership is responsible for:

- linking a User to an Organization;
- defining the User's role within the Organization;
- tracking the membership status.

## Relationships

An Organization Membership:

- belongs to exactly one User;
- belongs to exactly one Organization;
- has exactly one organizational role;
- has exactly one membership status.

## Business Rules

- A User may have multiple Organization Memberships.
- An Organization may have multiple Organization Memberships.
- Every Organization Membership has exactly one role.
- Every Organization Membership has exactly one status.

## Invariants

- Every Organization Membership belongs to exactly one User.
- Every Organization Membership belongs to exactly one Organization.
- Every Organization Membership always has exactly one role.
- Every Organization Membership always has exactly one status.
- An Organization Membership cannot exist without both its User and its Organization.

## Notes

Organization Membership represents the business relationship between a User and an Organization rather than a simple association.

Roles and membership statuses are currently implemented as enumerations and may evolve into dedicated domain concepts in future versions.

---

Status: DRAFT

Version: 0.1
