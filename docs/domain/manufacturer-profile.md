# Manufacturer Profile

## Purpose

A Manufacturer Profile represents the stable Creastrix manufacturing-capability identity of exactly one User or Organization whose eligibility to accept new made-to-order manufacturing work may be verified by the platform.

It identifies which User or Organization business context is recognized by Creastrix as a manufacturing participant.

## Responsibilities

A Manufacturer Profile is responsible for:

- representing a stable manufacturing participant identity;
- preserving its immutable Profile Holder relationship;
- recording its eligibility status;
- describing declared manufacturing capabilities at a high level;
- recording the User who created the profile record;
- remaining stable for required historical references.

## Relationships

A Manufacturer Profile:

- belongs to exactly one immutable Profile Holder, which is either one User or one Organization;
- has exactly one immutable Created By User;
- may be assigned to zero or more made-to-order Order Items;
- has no mandatory Workspace relationship.

## Business Rules

- A Manufacturer Profile Holder must be either one User or one Organization, but cannot be both.
- A User may hold zero or one personal Manufacturer Profile in MVP.
- An Organization may hold zero or one Manufacturer Profile in MVP.
- The Profile Holder cannot be changed in MVP. A materially different business context requires a separate Manufacturer Profile or a future explicit migration workflow.
- Created By records the User who created the profile record and does not determine the Profile Holder, manufacturer identity, profile management rights, work acceptance rights, verification, manufacturing ownership, payout rights, or legal seller identity.
- A Manufacturer Profile is created with the eligibility status UNVERIFIED.
- A Manufacturer Profile eligibility status must be UNVERIFIED, VERIFIED, or SUSPENDED in MVP.
- UNVERIFIED means that the profile does not currently have the valid platform verification required for new made-to-order work.
- VERIFIED means that the required profile-level platform verification is currently valid and the profile may be considered for new made-to-order work.
- SUSPENDED means that the platform currently prevents the profile from being considered for new work regardless of other profile data.
- The allowed status transitions in MVP are UNVERIFIED to VERIFIED; VERIFIED to UNVERIFIED or SUSPENDED; and SUSPENDED to VERIFIED or UNVERIFIED.
- Status changes are governed by platform verification and moderation rules. Ordinary profile management does not authorize the Profile Holder to set VERIFIED or SUSPENDED status freely.
- Only a VERIFIED Manufacturer Profile may be considered eligible for assignment to new made-to-order manufacturing work.
- VERIFIED status is necessary but does not establish item-specific capability, available capacity, acceptance, price, lead time, or product compliance.
- A Manufacturer Profile may describe declared manufacturing capabilities for future manufacturer discovery and item-specific eligibility, but those declarations do not guarantee suitability for a particular Order Item.
- A User-held Manufacturer Profile is managed through authorization of its holder User.
- An Organization-held Manufacturer Profile is managed by authorized Users acting on behalf of the Organization under Organization rules.
- Organization Membership alone does not automatically authorize Manufacturer Profile management or acceptance of manufacturing work.
- Workspace Membership and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not automatically authorize Manufacturer Profile management, verification, or acceptance of manufacturing work.
- A made-to-order Order Item has exactly one assigned Manufacturer Profile at confirmation.
- The assigned Manufacturer Profile must be VERIFIED at confirmation, while item-specific eligibility and required Manufacturer acceptance must also succeed.
- Actual item-specific eligibility, pricing, capacity validation, acceptance, and assignment belong to the Order Item confirmation workflow.
- Manufacturer acceptance is specific to made-to-order work, is not Manufacturer Profile state, and does not change the profile eligibility status.
- Manufacturer Profile assignment is immutable after Order confirmation.
- A later profile status change does not rewrite an existing Manufacturer Profile assignment or automatically reassign or cancel an existing Order Item.
- A Manufacturer Profile required by historical references cannot be destructively deleted.

## Invariants

- A Manufacturer Profile always has one stable identity.
- A Manufacturer Profile always has exactly one Profile Holder.
- The Profile Holder is always either exactly one User or exactly one Organization, never both.
- The Profile Holder remains unchanged in MVP.
- A User never holds more than one personal Manufacturer Profile in MVP.
- An Organization never holds more than one Manufacturer Profile in MVP.
- A Manufacturer Profile always has exactly one immutable Created By User.
- A Manufacturer Profile always has exactly one eligibility status.
- The eligibility status is always UNVERIFIED, VERIFIED, or SUSPENDED in MVP.
- Only a VERIFIED Manufacturer Profile can be assigned to new confirmed made-to-order work.
- Later profile changes never rewrite historical Manufacturer Profile references.

## Notes

Manufacturer may be used as domain shorthand for an assigned Manufacturer Profile. No separate Manufacturer entity exists in MVP.

Profile verification, item-specific manufacturing eligibility, available capacity, and Manufacturer acceptance are separate concepts.

Detailed technologies, machines, materials, dimensions, capacity, lead times, certifications, facilities, service regions, and verification evidence remain future modeling. No separate capability entity is introduced in this specification.

The exact Organization permission matrix, voluntary profile closure, deletion before first historical use, retention periods, and post-confirmation failure or replacement workflow remain future concerns.

Manufacturer Profile is a domain capability identity, not a Workspace, factory, facility, supplier, seller, seller-of-record, tax merchant, payout account, bank account, payment recipient, Order, Order Item, or manufacturing job.

A future public manufacturer presentation may expose selected profile or capability information, while internal verification information is not automatically public. Exact presentation and visibility remain future work.

Manufacturer Profile is suitable as the stable subject of a future Manufacturer Review grounded in an actual fulfilled Order Item, but review rules remain future work.

Ordinary Ready-Made Product fulfillment does not require a Manufacturer Profile merely because the product was historically manufactured.

Manufacturer Profile does not depend on Organization Profile or Designer Profile.

---

Status: DRAFT

Version: 0.2
