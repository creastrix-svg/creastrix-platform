# User

## Purpose

A User represents a person with a stable platform identity whose account may authenticate and interact with Creastrix according to its current account and access status.

A User is the identity of a real person. Business capabilities such as designing products or manufacturing items are provided through dedicated profiles.

## Responsibilities

A User is responsible for:

- authentication;
- account security;
- representing the current account and access status;
- platform access;
- owning Workspaces;
- participating in Organizations;
- being associated with additional platform profiles.

## Relationships

A User:

- has exactly one User Profile;
- may own zero or more Workspaces;
- may be a member of zero or more Workspaces;
- may belong to zero or more Organizations;
- may own zero or more Personalizations;
- may be the Buyer of zero or more Orders;
- may directly hold zero or one personal Designer Profile in MVP;
- may hold zero or one personal Manufacturer Profile.

## Business Rules

- A User has exactly one account and access status: ACTIVE, SUSPENDED, or DEACTIVATED.
- User status is account and access state. It is not deletion, Membership state, Organization state, Workspace state, beneficiary state, or Profile state.
- A newly created User starts with the status ACTIVE.
- An ACTIVE User may authenticate and perform ordinary User-driven or delegated operations, subject to every other applicable authorization and domain rule.
- ACTIVE status alone does not grant organization, Workspace, profile, publication, commerce, or other business authority.
- A SUSPENDED User preserves identity, history, and relationships, but ordinary User-driven and delegated authority is disabled while suspended.
- A DEACTIVATED User preserves identity, history, and relationships, but ordinary User-driven and delegated authority is disabled. Deactivation is not deletion.
- Supported status transitions are ACTIVE to SUSPENDED, SUSPENDED to ACTIVE, ACTIVE to DEACTIVATED, and SUSPENDED to DEACTIVATED.
- Ordinary reactivation from DEACTIVATED to ACTIVE is not supported. Any future exceptional reactivation requires an explicitly defined policy.
- Only an applicable authorized account, security, or platform workflow may change User status. An Organization OWNER cannot change a User's account status merely through Organization authority.
- Every ordinary operation performed by or delegated through a User requires the User to be ACTIVE in addition to all other applicable Membership, role, permission-scope, profile, eligibility, and domain-operation requirements.
- A SUSPENDED or DEACTIVATED User cannot exercise ordinary authority through an Organization Membership, Workspace Membership, Designer Profile, Manufacturer Profile, buyer relationship, or other delegated context even when that relationship remains active or otherwise eligible.
- A User status change does not automatically mutate Organization Membership role or status, Workspace Membership role, status, or permission scopes, Workspace ownership, Organization identity, Designer Profile or Manufacturer Profile Holder identity, Created By, Buyer, Reviewer, beneficiary identity, or historical snapshots.
- User status constrains authority exercised by that User. It does not by itself block independently authorized platform workflows such as security or moderation, financial reconciliation, Payment evidence processing, Payout or finance processing, internal Ready-Made Product fulfillment, or Organization recovery.
- A User may create Projects only within Workspaces where the User has the effective authorization required for PROJECTS operations.
- A User may create or manage Ready-Made Products only within Workspaces where the User has the effective authorization required for READY_MADE_PRODUCTS operations.
- A User may create or manage Listings only when the User has effective LISTINGS authorization in the Workspace context derived from the Listing's commercial source.
- LISTINGS authorization does not replace the business eligibility required to activate or publish a Listing.
- A User may create, view, edit, reuse, and discard the User's own Personalizations subject to Personalization rules and future retention requirements.
- Access to an owned Personalization is governed by User authorization and does not require Workspace Membership or a Workspace permission scope.
- The owning User and Created By User are the same when a Personalization is created in MVP, and Personalization ownership cannot be transferred between Users in MVP.
- Personalization ownership represents platform control of a private saved object and does not establish legal or intellectual-property ownership.
- A User may act under a personal account or on behalf of an Organization, depending on the current context.
- A User may directly hold no more than one personal Designer Profile in MVP.
- An authorized User may act through one or more Organization-held Designer Profiles in the respective Organization contexts. Those Profiles remain Organization-held, do not create a User-held Designer Profile relationship, and do not count against the User's personal Designer Profile cardinality.
- A User may publish a Listing targeting a FINALIZED Revision only when acting in a context backed by an associated Designer Profile that satisfies the future applicable verification rules.
- Eligibility to publish Listings for other commercial source types is defined by the corresponding domain rules.
- A User may accept made-to-order manufacturing work only when acting in a context backed by a VERIFIED Manufacturer Profile and satisfying the authorization rules of that Profile Holder context.
- In a personal manufacturing context, the Manufacturer Profile is held by the User. In an Organization manufacturing context, the Manufacturer Profile is held by the Organization and the User must be authorized to act on behalf of that Organization.
- A User may access the User's own Orders through User and customer authorization without requiring Workspace Membership or a Workspace permission scope.
- When an Order Item uses Personalization in MVP, that Personalization must belong to the Buyer User of the Order.

## Invariants

- A User always has exactly one identity.
- A User always has exactly one account and access status, which is ACTIVE, SUSPENDED, or DEACTIVATED.
- A SUSPENDED or DEACTIVATED User never supplies ordinary User-driven or delegated authority.
- Changing User status never changes the User's identity or automatically rewrites the User's domain relationships or historical references.
- A User may own multiple Workspaces.
- A User may belong to multiple Organizations.
- A User cannot directly hold more than one personal Designer Profile in MVP.
- A User cannot hold more than one personal Manufacturer Profile in MVP.
- A User cannot exist without exactly one User Profile.

## Notes

Authentication and identity data belong to User, while personal information belongs to User Profile.

Account and access status belongs to User, not User Profile.

SUSPENDED and DEACTIVATED Users remain valid historical and economic identities. This specification does not decide whether a SUSPENDED or DEACTIVATED beneficiary is currently eligible for Payout.

A User may act through Organization-held Designer Profiles when authorized. Those profiles are independent from the User's personal Designer Profile and do not count against its cardinality.

A User may act through Organization-held Manufacturer Profiles when authorized. Those profiles are independent from the User's personal Manufacturer Profile and do not count against its cardinality.

Being the Buyer of an Order represents the customer context of a confirmed purchase and does not establish legal ownership of commerce or its underlying products.

---

Status: APPROVED
Version: 1.8
