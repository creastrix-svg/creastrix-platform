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

- [User](user.md)
- [User Profile](user-profile.md)
- [Organization](organization.md)
- [Organization Membership](organization-membership.md)

## Draft specifications

These entities have active draft specifications. They represent active architecture work and are not independently approved.

- [Workspace](workspace.md) — DRAFT 0.7
- [Workspace Membership](workspace-membership.md) — DRAFT 0.7
- [Ready-Made Product](ready-made-product.md) — DRAFT 0.5
- [Project](project.md) — DRAFT 0.5
- [Revision](revision.md) — DRAFT 0.4
- [Listing](listing.md) — DRAFT 0.5
- [Personalization](personalization.md) — DRAFT 0.4
- [Manufacturer Profile](manufacturer-profile.md) — DRAFT 0.3
- [Order](order.md) — DRAFT 0.3
- [Order Item](order-item.md) — DRAFT 0.4
- [Payment](payment.md) — DRAFT 0.1
- [Payment Allocation](payment-allocation.md) — DRAFT 0.1
- [Shipment](shipment.md) — DRAFT 0.2

## Planned entities

These domain concepts are recognized, but do not yet have active specifications.

- Organization Profile
- Designer Profile
- Royalty
- Payout
- Designer Review
- Manufacturer Review
- Notification
- Conversation
- Message
- Audit Log
