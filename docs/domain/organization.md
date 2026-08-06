# Organization

## Purpose

An Organization represents a company, studio, workshop, team, or other business entity that operates on the Creastrix platform.

An Organization allows multiple Users to collaborate under a shared business identity.

An Organization acts as a single business participant on the Creastrix platform, regardless of its legal form.

Business capabilities such as designing products, manufacturing items, owning Workspaces and Projects, and receiving payments are provided through dedicated platform profiles.

## Responsibilities

An Organization is responsible for:

- representing a shared business identity;
- owning Workspaces and Projects;
- managing Organization Memberships;
- collaborating through shared Workspaces;
- participating in commercial activities through dedicated platform profiles.

## Relationships

An Organization may:

- own zero or more Workspaces;
- have zero or more Organization Memberships;
- be associated with zero or one Designer Profile;
- be associated with zero or one Manufacturer Profile;
- own zero or more Projects.

## Business Rules

- An Organization may have one or more active Organization Memberships with the role OWNER.
- The creator of an Organization receives an active Organization Membership with the role OWNER.
- The last active OWNER cannot leave the Organization, be removed, be suspended, or be assigned another role until another active OWNER exists.
- An Organization cannot exist without at least one Organization Membership.

## Invariants

- An Organization always has at least one active Organization Membership with the role OWNER.
- Every Organization Membership of an Organization belongs to that Organization only.

## Notes

An Organization is a first-class business participant of the platform and may own domain objects independently from individual Users.

Business capabilities are exercised through dedicated platform profiles rather than directly by the Organization.

----

Status: APPROVED
Version: 1.0
