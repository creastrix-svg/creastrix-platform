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
- own zero or more Personalizations;
- be the Buyer of zero or more Orders;
- be associated with zero or one Designer Profile;
- hold zero or one personal Manufacturer Profile.

## Business Rules

- A User may create Projects only within Workspaces where the User has the effective authorization required for PROJECTS operations.
- A User may create or manage Ready-Made Products only within Workspaces where the User has the effective authorization required for READY_MADE_PRODUCTS operations.
- A User may create or manage Listings only when the User has effective LISTINGS authorization in the Workspace context derived from the Listing's commercial source.
- LISTINGS authorization does not replace the business eligibility required to activate or publish a Listing.
- A User may create, view, edit, reuse, and discard the User's own Personalizations subject to Personalization rules and future retention requirements.
- Access to an owned Personalization is governed by User authorization and does not require Workspace Membership or a Workspace permission scope.
- The owning User and Created By User are the same when a Personalization is created in MVP, and Personalization ownership cannot be transferred between Users in MVP.
- Personalization ownership represents platform control of a private saved object and does not establish legal or intellectual-property ownership.
- A User may act under a personal account or on behalf of an Organization, depending on the current context.
- A User may publish a Listing targeting a FINALIZED Revision only when acting in a context backed by an associated verified Designer Profile.
- Eligibility to publish Listings for other commercial source types is defined by the corresponding domain rules.
- A User may accept made-to-order manufacturing work only when acting in a context backed by a VERIFIED Manufacturer Profile and satisfying the authorization rules of that Profile Holder context.
- In a personal manufacturing context, the Manufacturer Profile is held by the User. In an Organization manufacturing context, the Manufacturer Profile is held by the Organization and the User must be authorized to act on behalf of that Organization.
- A User may access the User's own Orders through User and customer authorization without requiring Workspace Membership or a Workspace permission scope.
- When an Order Item uses Personalization in MVP, that Personalization must belong to the Buyer User of the Order.

## Invariants

- A User always has exactly one identity.
- A User may own multiple Workspaces.
- A User may belong to multiple Organizations.
- A User cannot have more than one Designer Profile.
- A User cannot hold more than one personal Manufacturer Profile in MVP.
- A User cannot exist without exactly one User Profile.

## Notes

Authentication and identity data belong to User, while personal information belongs to User Profile.

A User may act through Organization-held Manufacturer Profiles when authorized. Those profiles are independent from the User's personal Manufacturer Profile and do not count against its cardinality.

Being the Buyer of an Order represents the customer context of a confirmed purchase and does not establish legal ownership of commerce or its underlying products.

---

Status: APPROVED
Version: 1.6
