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
- Project
- Revision

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
- PROJECTS is the currently defined permission scope for Project and Revision work.
- Future domain areas may introduce additional permission scopes without automatically expanding existing EDITOR or VIEWER access.
- Organization Membership does not automatically grant Workspace access or Workspace permission scopes.
- Scoped Workspace permissions never grant ownership or business rights.
- Project belongs to exactly one Workspace.
- Project has no separate Business Owner in MVP.
- Project Effective Business Rights Holder derives from the Workspace owner.
- Project is the stable identity of a manufacturable product concept and may have multiple Revisions.
- Revision itself carries the DRAFT and FINALIZED lifecycle; no separate Project Draft entity exists.
- Multiple DRAFT Revisions may be developed in parallel.
- A FINALIZED Revision has immutable product-defining content.
- For Project-based commerce, a Listing targets a FINALIZED Revision rather than the Project directly.
- The commercial source or target model for ready-made physical products remains to be specified before Listing is finalized.
- Personalization remains separate from Revision.
- Royalty rules originate from Listing, and historical commercial context is snapshotted later in Order Item.

## Product Rules

- Order and Order Item are different entities.
- One Order may contain multiple Order Items with different fulfillment paths.
- A made-to-order Order Item has exactly one manufacturer.
- Ready-made Order Item seller and fulfillment semantics remain to be defined with the ready-made product domain.
- Project, Revision, Listing, and Personalization are different concepts.
- Revision is created for product-defining changes, not text corrections.

## Product Direction

- The MVP must support ready-made physical products in addition to Project and Revision-based made-to-order products.
- Creastrix may initially act as the seller of ready-made products.
- Third-party seller self-service may be introduced later.
- The architecture should remain extensible for a future seller marketplace without implementing that marketplace now.
- The ready-made product domain and its final entity names are not yet specified.

## Next Steps

1. Model the ready-made physical product domain.
2. Define how ready-made products relate to Workspace ownership and scoped authorization.
3. Design Listing so it supports Project-based commerce without being locked to Revision-only commerce.
4. Continue with Order, Order Item, and fulfillment after both commercial source models are clear.
