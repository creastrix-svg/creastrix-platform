# Creastrix Project Context

## Purpose

This document is the technical memory of the Creastrix project.

It describes the product vision, approved domain decisions, current state, and next steps.

GitHub approved specifications are the source of truth.

## Product Vision

Creastrix connects designers, buyers, manufacturers and AI-assisted
personalization with physical production.

The goal is to move an idea from design to a real product while
protecting creator ownership and customer trust.

## Approved Domain Entities

- User
- User Profile
- Organization
- Organization Membership

## Current Draft Domain Specifications

The following specifications are DRAFT and are not yet part of the validated domain model:

- Workspace
- Workspace Membership
- Ready-Made Product
- Project
- Revision
- Listing

## Domain Principles

- Domain first.
- Clear responsibilities.
- Small steps.
- Document decisions.
- Commit approved changes.

## Important Decisions

- User represents identity.
- User Profile stores personal information.
- Organization is a first-class business participant.
- Organization Membership is a real domain entity.
- Workspace belongs to exactly one User or Organization.
- Workspace remains a common operational and access boundary and is not limited to design work.
- Workspace ownership and Workspace access are separate concepts.
- In a User-owned Workspace, the User owner remains an ACTIVE ADMIN in MVP and cannot lose administrative access through normal Workspace membership changes.
- Workspace Membership authorization combines membership status, Workspace role, the relevant permission scope, and rules of the requested domain operation.
- An ACTIVE ADMIN has full Workspace access in MVP, while EDITOR and VIEWER operate only within explicitly granted scopes.
- PROJECTS is the Workspace permission scope for Project and Revision work.
- READY_MADE_PRODUCTS is the Workspace permission scope for Ready-Made Product management, including simple MVP available quantity.
- LISTINGS is the Workspace permission scope for Listing commercial management.
- PROJECTS, READY_MADE_PRODUCTS, and LISTINGS are independent scopes and do not grant access to one another.
- Future domain areas may introduce additional permission scopes without automatically expanding existing EDITOR or VIEWER access.
- Organization Membership does not automatically grant Workspace access or Workspace permission scopes.
- Scoped Workspace permissions never grant ownership or business rights.
- Project belongs to exactly one Workspace.
- Project has no separate Business Owner in MVP.
- Project Effective Business Rights Holder derives from the Workspace owner.
- Ready-Made Product is the stable identity of one independently stocked physical product configuration and belongs to exactly one Workspace.
- The Workspace owner provides the platform-recognized commercial context in which a Ready-Made Product is managed; this does not prove legal ownership, physical custody, seller-of-record, manufacturer, or supplier status.
- Ready-Made Product has the ACTIVE and ARCHIVED lifecycle and may transition in either direction.
- Ready-Made Product uses simple non-negative available quantity in MVP; an allocation may be confirmed only when sufficient quantity is available at confirmation, and the same available stock capacity cannot be confirmed for more than one buyer. Lifecycle remains independent from stock availability.
- One independently stocked physical configuration is one Ready-Made Product in MVP; no Product Variant entity exists.
- Ready-Made Product exists independently from Listing and is never published directly.
- Ready-Made Product does not require a Manufacturer Profile.
- Project is the stable identity of a manufacturable product concept and may have multiple Revisions.
- Revision itself carries the DRAFT and FINALIZED lifecycle; no separate Project Draft entity exists.
- Multiple DRAFT Revisions may be developed in parallel.
- A FINALIZED Revision has immutable product-defining content.
- Listing represents the commercial and public offer of exactly one immutable commercial source: a FINALIZED Revision or a Ready-Made Product, never both.
- Listing Workspace context is derived from its commercial source, and source write permission is not required merely to manage Listing commercial data.
- LISTINGS authorization and business eligibility are separate requirements.
- A source may have multiple Listings over time but no more than one ACTIVE Listing at the same time in MVP.
- Listing has the DRAFT, ACTIVE, PAUSED, and ARCHIVED lifecycle; ACTIVE does not guarantee effective orderability.
- Source archive or loss of required business eligibility makes an existing Listing non-orderable without changing Listing lifecycle automatically.
- A Project cannot move between Workspaces while a Listing targeting any of its Revisions is ACTIVE.
- A Ready-Made Product Listing uses a fixed unit sale price, while a Revision-based Listing may use base or display pricing before the final Order Item price is determined.
- Every Listing uses one currency in MVP.
- A Revision-based Listing requires explicit applicable royalty terms before activation; a Ready-Made Product Listing does not create designer royalty automatically.
- Listing is manufacturer-independent; a Manufacturer for made-to-order commerce is selected later for Order Item.
- Seller-of-record remains future work, and historical Order Item snapshots are never rewritten by later Listing changes.
- Personalization remains separate from Revision.
- Royalty rules originate from Listing, and historical commercial context is snapshotted later in Order Item.

## Product Rules

- Order and Order Item are different entities.
- One Order may contain multiple Order Items with different fulfillment paths.
- A made-to-order Order Item has exactly one manufacturer.
- Ready-made fulfillment allocates existing stock and does not require a Manufacturer merely because the Order Item is ready-made; detailed seller and fulfillment semantics remain future work.
- Project, Revision, Listing, and Personalization are different concepts.
- Revision is created for product-defining changes, not text corrections.

## Product Direction

- The MVP must support ready-made physical products in addition to Project and Revision-based made-to-order products.
- Creastrix may initially act as the seller of ready-made products.
- Third-party seller self-service may be introduced later.
- The architecture should remain extensible for a future seller marketplace without implementing that marketplace now.
- Creastrix-first selling is platform policy rather than an invariant of Ready-Made Product.

## Next Steps

1. Review and finalize the Listing draft.
2. Model the Personalization boundary where needed for Revision-based Listings.
3. Model Order and Order Item with distinct made-to-order and ready-made fulfillment paths.
4. Continue with Shipment, Payment, Payment Allocation, and Royalty as their dependencies become clear.
