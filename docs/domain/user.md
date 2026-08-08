# User

## Purpose

A User represents a person who can authenticate and interact with the Creastrix platform.

A User is the identity of a real person. Business capabilities such as designing products or manufacturing items are provided through dedicated profiles.

## Responsibilities

A User is responsible for:

- authentication;
- account security;
- platform access;
- owning Workspaces;
- participating in Organizations;
- being associated with additional platform profiles.

## Relationships

A User may:

- own zero or more Workspaces;
- be a member of zero or more Workspaces;
- belong to zero or more Organizations;
- be associated with zero or one Designer Profile;
- be associated with zero or one Manufacturer Profile.

## Business Rules

- A User may create Projects only within Workspaces where the User has sufficient permissions.
- A User may create or manage Ready-Made Products only within Workspaces where the User has the effective authorization required for READY_MADE_PRODUCTS operations.
- A User may create or manage Listings only when the User has effective LISTINGS authorization in the Workspace context derived from the Listing's commercial source.
- LISTINGS authorization does not replace the business eligibility required to activate or publish a Listing.
- A User may act under a personal account or on behalf of an Organization, depending on the current context.
- A User may publish a Listing targeting a FINALIZED Revision only when acting in a context backed by an associated verified Designer Profile.
- Eligibility to publish Listings for other commercial source types is defined by the corresponding domain rules.
- A User may accept manufacturing orders only when acting in a context backed by an associated verified Manufacturer Profile.

## Invariants

- A User always has exactly one identity.
- A User may own multiple Workspaces.
- A User may belong to multiple Organizations.
- A User cannot have more than one Designer Profile.
- A User cannot have more than one Manufacturer Profile.
- A User cannot exist without exactly one User Profile.

## Notes

Authentication and identity data belong to User, while personal information belongs to User Profile.

---

Status: APPROVED
Version: 1.3
