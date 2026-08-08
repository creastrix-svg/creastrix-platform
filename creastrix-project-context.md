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
- Workspace ownership and Workspace access are separate concepts.
- Workspace Membership controls access.
- Organization Membership does not automatically grant Workspace access.
- Project belongs to exactly one Workspace.
- Project has no separate Business Owner in MVP.
- Project Effective Business Rights Holder derives from the Workspace owner.
- Project is the stable identity of a manufacturable product concept and may have multiple Revisions.
- Revision itself carries the DRAFT and FINALIZED lifecycle; no separate Project Draft entity exists.
- Multiple DRAFT Revisions may be developed in parallel.
- A FINALIZED Revision has immutable product-defining content.
- Listing will target a FINALIZED Revision, not Project directly.
- Personalization remains separate from Revision.
- Royalty rules originate from Listing, and historical commercial context is snapshotted later in Order Item.

## Product Rules

- Order and Order Item are different entities.
- One Order may contain items from different manufacturers.
- One Order Item has one manufacturer.
- Project, Revision, Listing, and Personalization are different concepts.
- Revision is created for product-defining changes, not text corrections.

## Next Steps

1. Complete and review the Revision draft.
2. Continue domain modeling with Listing.
3. Review related draft specifications together before promoting them to APPROVED.
4. Keep project context and domain index synchronized when architectural decisions change.
