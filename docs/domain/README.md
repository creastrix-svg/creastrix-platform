# Domain Specifications

Every domain entity must follow the structure defined in `entity-template.md`.

Approved specifications are independently accepted current architecture for their entities.

## Specification status

Specification status applies independently to each specification.

- APPROVED means that the responsibilities, relationships, business rules, and invariants of this specification version have been accepted as the current architecture for this entity.
- DRAFT means that the specification is active architecture work and may still change.
- PLANNED means that the domain concept is recognized, but no active specification exists for it yet.

An APPROVED specification may reference a DRAFT or PLANNED concept to define a provisional integration direction or boundary for the approved entity.

Such a reference does not promote the referenced specification to APPROVED and does not validate unresolved details of the referenced concept.

If a DRAFT or PLANNED dependency changes incompatibly with an APPROVED specification, the affected APPROVED specification must be explicitly reviewed, updated, versioned, and approved before the change is accepted as consistent.

## Approved entities

- [User](user.md) — APPROVED 1.8
- [User Profile](user-profile.md) — APPROVED 1.0
- [Organization](organization.md) — APPROVED 1.5
- [Organization Membership](organization-membership.md) — APPROVED 1.4

## Draft specifications

These entities have active draft specifications. They represent active architecture work and are not independently approved.

- [Workspace](workspace.md) — DRAFT 0.8
- [Workspace Membership](workspace-membership.md) — DRAFT 0.8
- [Ready-Made Product](ready-made-product.md) — DRAFT 0.5
- [Project](project.md) — DRAFT 0.5
- [Revision](revision.md) — DRAFT 0.4
- [Designer Profile](designer-profile.md) — DRAFT 0.2
- [Designer Review](designer-review.md) — DRAFT 0.1
- [Listing](listing.md) — DRAFT 0.8
- [Personalization](personalization.md) — DRAFT 0.4
- [Manufacturer Profile](manufacturer-profile.md) — DRAFT 0.3
- [Order](order.md) — DRAFT 0.5
- [Order Item](order-item.md) — DRAFT 0.8
- [Payment](payment.md) — DRAFT 0.4
- [Payment Allocation](payment-allocation.md) — DRAFT 0.2
- [Royalty](royalty.md) — DRAFT 0.2
- [Payout](payout.md) — DRAFT 0.3
- [Shipment](shipment.md) — DRAFT 0.2

## Planned entities

These domain concepts are recognized, but do not yet have active specifications.

- Organization Profile
- Manufacturer Review
- Notification
- Conversation
- Message
- Audit Log
