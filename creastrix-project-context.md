# Creastrix Project Context

## Purpose

This document is the technical memory of the Creastrix project.

It describes: - product vision; - approved domain decisions; - current
state; - next steps.

GitHub approved specifications are the source of truth.

## Product Vision

Creastrix connects designers, buyers, manufacturers and AI-assisted
personalization with physical production.

The goal is to move an idea from design to a real product while
protecting creator ownership and customer trust.

## Approved Domain Entities

-   User
-   User Profile
-   Organization
-   Organization Membership

## Domain Principles

-   Domain first.
-   Clear responsibilities.
-   Small steps.
-   Document decisions.
-   Commit approved changes.

## Important Decisions

-   User represents identity.
-   User Profile stores personal information.
-   Organization is a first-class business participant.
-   Organization Membership is a real domain entity.

## Product Rules

-   Order and Order Item are different entities.
-   One Order may contain items from different manufacturers.
-   One Order Item has one manufacturer.
-   Project, Revision, Listing and Personalization are different
    concepts.
-   Revision is created for product changes, not text corrections.
-   Royalty rules originate from Listing and are fixed in order context.

## Next Steps

1.  Update domain README.
2.  Create Workspace specification.
3.  Create Workspace Membership specification.
4.  Continue domain modeling.
