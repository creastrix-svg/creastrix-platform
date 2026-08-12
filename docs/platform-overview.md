# Platform Overview

Creastrix connects buyers, designers, manufacturers, and AI-assisted product creation with physical production. It supports both design-driven made-to-order and ready-made product journeys.

This is a conceptual product overview. It does not define entity invariants, establish specification approval, or describe complete implementation coverage. See the [Domain Specifications Index](domain/README.md) for current statuses, the [Project Context](../creastrix-project-context.md) for current decisions and delivery sequence, and the [Repository README](../README.md) for current implementation state.

## Participants

- **Users** are people with platform identities and may act in different domain roles and contexts.
- **Buyers** are Users acting in a purchasing role rather than a separate core entity.
- **Organizations** represent shared business participants. Organization participation does not by itself determine seller, merchant, beneficiary, payment recipient, or payout identity.
- **Designers:** Designer is a role term rather than a separate core entity. Designer Profiles are held by Users or Organizations and represent public professional design identity and publication capability; they are not required for every Project or Revision action and do not by themselves establish legal authorship, intellectual-property ownership, design-specific publication rights, or monetary beneficiary identity.
- **Manufacturers** are represented through Manufacturer Profiles held by Users or Organizations for made-to-order manufacturing capability. Manufacturer is role shorthand rather than a separate core entity, and a Manufacturer Profile does not by itself determine seller or monetary beneficiary identity.
- **Creastrix** operates the platform and is the single buyer-facing seller-of-record and merchant-of-record for current MVP Orders across both design-driven made-to-order and ready-made fulfillment paths. This records the current MVP direction; it does not claim that commerce is implemented or independently approved by this overview.

## Product Journeys

### Design-Driven / Made-to-Order

At a conceptual level, a product may progress from design work through Project and Revision context, a publication context, confirmed commerce, and physical manufacturing for the purchase. The relevant DRAFT specifications describe provisional architecture and remain subject to independent approval.

### Ready-Made

A Ready-Made Product represents an existing-stock product configuration that may be offered through commerce. Its ordinary fulfillment uses existing stock and does not require a new manufacturing step after purchase.

Future third-party seller self-service remains future work. See the [Domain Specifications Index](domain/README.md) for approval status, the [Project Context](../creastrix-project-context.md) for current decisions and delivery sequence, and the [Repository README](../README.md) for implementation coverage.
