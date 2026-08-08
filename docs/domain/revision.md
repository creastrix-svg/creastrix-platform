# Revision

## Purpose

A Revision represents a technical or design variant of a Project.

A Revision has a stable domain identity from creation while its product-defining content evolves from a mutable DRAFT into an immutable FINALIZED variant.

## Responsibilities

A Revision is responsible for:

- representing one technical or design variant within a Project;
- holding mutable product-defining work while in DRAFT;
- preserving finalized product-defining content;
- managing the DRAFT-to-FINALIZED lifecycle;
- receiving a human-readable revision number at finalization;
- preserving creation and optional Base Revision provenance;
- defining optional technical personalization capability and constraints as product-defining content.

## Relationships

A Revision:

- belongs to exactly one Project;
- has exactly one Created By User;
- may reference zero or one Base Revision;
- may be targeted by zero or more Listings when FINALIZED;
- may be the immutable technical base for zero or more Personalizations when FINALIZED;
- may be referenced by Audit Log events.

## Business Rules

- A Revision is created in the DRAFT state.
- When a Project is created, its first DRAFT Revision is created together with it.
- A DRAFT Revision is mutable and may be repeatedly saved or autosaved by Users with effective write authorization for the PROJECTS scope.
- Normal persistence or autosave does not finalize a Revision.
- A Project may contain multiple DRAFT Revisions and multiple FINALIZED Revisions simultaneously and does not have one mandatory current Revision.
- Finalization is an explicit transition from DRAFT to FINALIZED; no reverse transition exists in MVP.
- Finalization makes product-defining content immutable and assigns the Revision a human-readable revision number.
- A human-readable revision number is unique within its Project, remains immutable, represents finalization order, and does not imply lineage. Gapless numbering is not required as a business rule.
- A FINALIZED Revision may be targeted by Listings, but finalization does not create a Listing and does not make the Revision automatically published or manufacturing-approved.
- A DRAFT Revision may be created without a Base Revision or may reference one Base Revision for provenance.
- A Base Revision does not establish a mandatory linear revision chain, and multiple-base merge is not supported in MVP.
- Created By identifies only the User who created the Revision record or work variant in Creastrix.
- Created By, participation in editing, and PROJECTS-scope authorization do not determine creative authorship, ownership, business rights, royalty rights, or publication authority within the Creastrix domain model.
- Revision creation and finalization require effective PROJECTS-scope authorization appropriate to the operation and remain subject to the lifecycle of the parent Project. In particular, having the scope does not permit editing or finalizing DRAFT Revisions of an ARCHIVED Project or developing Revisions of a DELETED Project.
- A new Revision is appropriate when the product remains the same concept while its product-defining technical or design variant changes, including construction, geometry, assembly method, engineering dimensions, manufacturing files, material thickness requiring different production files, or a creator-made public decorative or design variant.
- A new Project is required when the core identity, purpose, or essential function changes enough that the result is no longer the same product concept.
- Buyer-specific customization remains Personalization and does not become a public Revision automatically.
- A Revision may define technical personalization capability and constraints as part of its product-defining content, but not every Revision is required to support Personalization.
- Personalization values may vary within the constraints of a FINALIZED Revision and may produce physically different buyer-specific output without creating a new Revision.
- Finalization makes any personalization capability and constraints of the Revision immutable. Changing personalization zones, parameter structure, allowed ranges, shared technical rules, or other reusable personalization constraints requires a new Revision.
- A Personalization never mutates its FINALIZED Revision, and buyer-specific changes outside Revision-defined constraints cannot bypass the Revision lifecycle.
- A new Revision is not required for changes limited to typo corrections, marketing copy, general descriptions, photographs, catalog metadata, or instructions that do not alter the actual product.
- A Revision does not have a separate Business Owner in MVP. Its commercial context derives through its Project, the Project's Workspace, and the Workspace owner.

## Invariants

- A Revision always belongs to exactly one Project.
- A Revision always retains the same stable entity identity from creation.
- A Revision always has exactly one immutable Created By User.
- A Revision always has exactly one lifecycle state: DRAFT or FINALIZED.
- A Revision never has PUBLISHED as a lifecycle state.
- A DRAFT Revision never has a finalized human-readable revision number.
- A FINALIZED Revision always has exactly one immutable human-readable revision number that is unique within its Project.
- Product-defining content of a FINALIZED Revision never changes.
- Any personalization capability and constraints of a FINALIZED Revision never change.
- A FINALIZED Revision never returns to DRAFT in MVP.
- If a Base Revision exists, it belongs to the same Project, is FINALIZED, and is not the Revision itself.
- Only a FINALIZED Revision may be targeted by a Listing.
- Only a FINALIZED Revision may be the technical base of a Personalization.

## Notes

Revision is not a Project Draft, and no separate Project Draft entity exists.

Descriptive, catalog, marketing, photographic, and other non-product-defining content belongs outside immutable Revision product data and will be modeled by appropriate future domain entities.

Significant Revision creation and finalization events are recorded through future Audit Log behavior. Whether a separate Finalized By relationship is needed remains unresolved.

Detailed Listing, Royalty, and Order Item rules remain outside Revision. Revision does not calculate or store actual Royalty allocation, and later changes to the surrounding Project or Workspace context must not retroactively rewrite immutable historical commercial snapshots.

Project itself is never published directly. Multiple FINALIZED Revisions of the same Project may have ACTIVE Listings simultaneously, as defined by the Listing specification.

Manufacturing requirements and technology relationships will be modeled separately in future domain specifications. Revision has no Technology relationship in MVP, and this specification does not decide whether manufacturing requirements belong to Revision or a future Manufacturing Specification.

PROJECTS is the permission scope for the Project and Revision domain area. Exact operation-level authorization may be refined where necessary, while Project and Revision lifecycle rules and invariants remain authoritative even when the scope is available.

DRAFT abandonment or discard behavior, additional DRAFT statuses, multiple-Revision merge, contributor or co-author modeling, manufacturing validation or approval, and detailed retention policy remain unresolved.

---

Status: DRAFT

Version: 0.3
