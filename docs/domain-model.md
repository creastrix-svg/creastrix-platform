# Creastrix Domain Documentation Map

> **Supporting map only.** This document is a navigational and conceptual view of the current domain documentation. It is not authoritative for invariants, does not approve DRAFT specifications, and does not represent implementation coverage.

Use the [Domain Specifications Index](domain/README.md) for current specification statuses and the individual specifications for authoritative accepted decisions. See the [Repository README](../README.md) for implementation orientation and [Creastrix Project Context](../creastrix-project-context.md) for technical memory and current direction. The previous exploratory model remains available through Git history.

## Identity and shared operating context

These specifications describe individual and business participants together with the shared contexts and access relationships through which they work.

- [User](domain/user.md)
- [User Profile](domain/user-profile.md)
- [Organization](domain/organization.md)
- [Organization Membership](domain/organization-membership.md)
- [Workspace](domain/workspace.md)
- [Workspace Membership](domain/workspace-membership.md)

## Design and publication

These specifications describe proposed design creation, revision history, professional publication identity, review, commercial publication, and buyer-specific configuration.

- [Project](domain/project.md)
- [Revision](domain/revision.md)
- [Designer Profile](domain/designer-profile.md)
- [Designer Review](domain/designer-review.md)
- [Listing](domain/listing.md)
- [Personalization](domain/personalization.md)

## Manufacturing, stock, and fulfillment

These specifications describe Ready-Made Product architecture together with proposed manufacturing capability and physical-delivery grouping and history.

- [Ready-Made Product](domain/ready-made-product.md)
- [Manufacturer Profile](domain/manufacturer-profile.md)
- [Shipment](domain/shipment.md)

## Commerce and finance

These specifications describe proposed confirmed purchases, collection and attribution of buyer funds, royalty accrual, and outbound transfer attempts.

- [Order](domain/order.md)
- [Order Item](domain/order-item.md)
- [Payment](domain/payment.md)
- [Payment Allocation](domain/payment-allocation.md)
- [Royalty](domain/royalty.md)
- [Payout](domain/payout.md)

## Selected Conceptual Connections

Most connections below involve DRAFT specifications. They are conceptual and provisional, remain subject to approval in their own specifications, and do not assert implementation.

- User and Organization provide individual or business-participant context; Memberships and Workspace access remain separate concepts.
- Workspace is the shared operational context for Project and Ready-Made Product areas, while ownership and participant authorization remain distinct.
- Project and Revision may exist before commerce; in the current proposed commerce path, an eligible FINALIZED Revision proceeds through a Revision-based Listing carrying the applicable publication context, then a confirmed Order Item, and then made-to-order fulfillment.
- A Ready-Made Product may exist independently without a Listing; when it enters the current proposed commerce path, it proceeds through a Ready-Made Product Listing, then a confirmed Order Item, and then existing-stock fulfillment.
- Order groups Order Items.
- Shipment represents fulfillment grouping and history rather than payment or ownership.
- Payment records buyer-funds collection attempts; Payment Allocation records captured-funds attribution; Royalty records separate designer-rights accrual; Payout records outbound transfer attempts.
- Designer Profile is not required for every design action.
- Manufacturer Profile assignment does not by itself create seller or beneficiary identity.
