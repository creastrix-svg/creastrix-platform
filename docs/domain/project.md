# Project

## Purpose

A Project represents the stable domain identity of one manufacturable product concept.

A Project retains its identity while different technical or design variants of the product are created as Revisions.

## Responsibilities

A Project is responsible for:

- representing the stable identity of a manufacturable product concept;
- grouping the Revisions that represent variants of that product concept;
- preserving its relationship with the Workspace where the Project exists;
- recording the User who created the Project record;
- managing the Project lifecycle;
- enforcing Workspace movement restrictions in MVP.

## Relationships

A Project:

- belongs to exactly one Workspace;
- has exactly one Created By User;
- has one or more Revisions;
- may be commercialized indirectly through Listings targeting FINALIZED Revisions;
- may be referenced by Audit Log events.

## Business Rules

- A Project may be created only by a User with effective write authorization for the PROJECTS scope.
- When a Project is created, its first DRAFT Revision is created together with it.
- A Project may have multiple DRAFT Revisions and multiple FINALIZED Revisions simultaneously and does not have one mandatory current Revision.
- A Project does not have a separate Business Owner in MVP. Its Effective Business Rights Holder is derived from the Workspace owner.
- Created By identifies only the User who created the Project record in Creastrix and does not provide creative authorship, ownership, business rights, royalty rights, or publication authority.
- An ACTIVE Project permits development and Revision finalization subject to effective PROJECTS-scope authorization and rules of the requested domain operation.
- An ARCHIVED Project does not permit new DRAFT Revisions, editing or finalization of existing DRAFT Revisions, or new commercialization.
- An ARCHIVED Project may return to ACTIVE in MVP.
- A DELETED Project is logically soft-deleted, does not permit development or new commercialization, and cannot be restored through normal user actions in MVP.
- A Project may transition from ACTIVE to ARCHIVED, from ARCHIVED to ACTIVE, from ACTIVE to DELETED, or from ARCHIVED to DELETED.
- A Project may move between Workspaces only when both Workspaces have the same owner; the move preserves its identity and Revisions.
- A Project cannot move between Workspaces while any Listing targeting any of its Revisions is ACTIVE. DRAFT or PAUSED Listings do not by themselves block an otherwise permitted same-owner move.
- After a Workspace move, Project access is determined by the Membership status, role, and relevant PROJECTS-scope authorization in the target Workspace and is not inherited automatically from the previous Workspace. Every Listing targeting a Revision of that Project continues to derive its Workspace context from its immutable source and therefore from the target Workspace. Further management of DRAFT or PAUSED Listings requires effective LISTINGS authorization there; ARCHIVED Listings remain closed for new commerce. Required historical Order Item snapshots remain unchanged.
- Cross-owner Workspace movement is forbidden in MVP.
- Workspace roles and permission scopes do not change the Effective Business Rights Holder of a Project.
- A Project is never published directly; commercialization occurs through Listings targeting FINALIZED Revisions.
- If a Project becomes ARCHIVED or DELETED, existing Listings targeting its Revisions retain their lifecycle status but become non-orderable. Orderability may recover after a return from ARCHIVED to ACTIVE when all other conditions hold.
- A new Revision represents a technical or design variant while the identity of the product remains the same.
- A new Project is required when the resulting object no longer represents the same product concept because its core identity, purpose, or essential function has changed.
- Buyer-specific customization remains Personalization and does not become a Project or public Revision automatically.

## Invariants

- A Project always belongs to exactly one Workspace.
- A Project always has exactly one immutable Created By User.
- A Project always has at least one Revision.
- Every Revision of a Project belongs to that Project only.
- The Effective Business Rights Holder of a Project always corresponds to the owner of its Workspace in MVP.
- A Project always has exactly one lifecycle status: ACTIVE, ARCHIVED, or DELETED.
- A Project never has PUBLISHED as a lifecycle status.
- Project lifecycle changes never destroy required historical references.

## Notes

A Project is not a production file, Revision, Listing, Personalization, or Workspace. No separate mutable working state belongs directly to Project.

Detailed Revision lifecycle, finalization, numbering, provenance, and immutable product-data rules belong to the Revision specification.

Significant Project creation, lifecycle, and Workspace-movement events are recorded through Audit Log.

Different FINALIZED Revisions are different Listing commercial sources and may each have an ACTIVE Listing simultaneously. Detailed Listing lifecycle and orderability rules belong to the Listing specification.

Royalty allocation and immutable commercial snapshots belong to the Listing and future Order Item and Royalty specifications. Required historical commercial records must not be retroactively rewritten by later Project lifecycle or Workspace changes.

Creative authorship is not modeled by Created By and will require separate future domain decisions if introduced.

Manufacturing requirements and technology relationships will be modeled separately in future domain specifications. Project has no Technology relationship in MVP.

Any future cross-owner transfer requires a separate explicit domain decision and must not be implemented as a simple Workspace reference change.

PROJECTS is the permission scope for the Project and Revision domain area. Exact operation-level authorization may be refined where necessary, and Project lifecycle rules and invariants remain authoritative even when the scope is available.

---

Status: DRAFT

Version: 0.3
