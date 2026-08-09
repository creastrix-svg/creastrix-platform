# Personalization

## Purpose

A Personalization represents a private buyer-specific configuration derived from exactly one FINALIZED Revision for potential use in one or more purchases.

It answers which buyer-specific values and generated output a User wants within the allowed personalization space of that finalized product design.

## Responsibilities

A Personalization is responsible for:

- representing a stable private buyer configuration identity;
- preserving its immutable FINALIZED Revision base;
- representing buyer-selected values within Revision-defined constraints;
- holding mutable manual or AI-assisted generated artifacts where needed;
- remaining a reusable saved configuration while its values are edited;
- providing the configuration data required for immutable Order Item snapshots.

## Relationships

A Personalization:

- belongs to exactly one User;
- has exactly one immutable Created By User;
- is based on exactly one immutable FINALIZED Revision;
- may be referenced by zero or more Order Items for traceability;
- has no direct relationship to a Workspace, Listing, Ready-Made Product, or Manufacturer Profile in MVP.

## Business Rules

- A Personalization is private to its owning User by default in MVP.
- Workspace ownership, Workspace Membership, Organization Membership, and the PROJECTS, READY_MADE_PRODUCTS, or LISTINGS scopes do not automatically grant access to a buyer's Personalization.
- The owning User and Created By User are the same when a Personalization is created in MVP.
- Personalization ownership cannot be transferred between Users in MVP.
- Created By records only the User who created the Personalization record. It does not establish legal ownership, intellectual-property ownership, creative authorship, rights to the base Revision, seller rights, Workspace rights, or royalty rights.
- A new buyer Personalization may be created through a suitable ACTIVE Listing only when that Listing targets its FINALIZED Revision base, the Revision defines personalization capability, and the Listing and current business rules offer that capability.
- Buyer creation of a Personalization through a Listing does not require LISTINGS authorization.
- The Listing used during creation is operation context and is not retained as a Personalization relationship.
- After creation, the owning User may continue editing the Personalization when the original Listing is PAUSED or ARCHIVED or when no ACTIVE Listing currently targets the Revision.
- A FINALIZED Revision defines the immutable technical personalization capability and constraints for its Personalizations.
- A Listing may commercially offer or narrow the personalization space defined by its FINALIZED Revision source but never expand those technical constraints.
- Personalization values and generated artifacts may be edited repeatedly and may temporarily be invalid while editing.
- Personalization has no lifecycle state in MVP. Current validity is evaluated separately from persistence.
- A Personalization may be used by a confirmed Order Item only when the actual configuration passes required current technical, commercial, and Order confirmation validation.
- When used by an Order Item in MVP, the Personalization must belong to the Buyer User of that Order.
- A Personalization remains mutable and may be reused for multiple purchases. Each Order Item snapshots the configuration used for that purchase independently.
- A Personalization never mutates its FINALIZED Revision and never moves automatically to another Revision. Adapting it to another Revision requires a new Personalization through a future explicit workflow.
- AI-assisted generation does not change Personalization identity or Created By.
- Project and Listing lifecycle changes do not mutate Personalization. They may affect whether it can currently be purchased.
- Personalization does not use Ready-Made Product as its technical base in MVP and does not represent ordinary ready-made fulfillment information.
- Buyer-selected values may provide inputs to price calculation, while confirmed merchandise amounts and currency belong to the Order Item snapshot.
- Personalization does not select or own a Manufacturer Profile and does not own final sale price, Listing pricing terms, currency, tax, shipping price, or royalty terms.
- Manufacturer Profile selection, assignment, and item-specific manufacturing feasibility belong to the made-to-order Order Item confirmation and fulfillment workflow.
- Discard or deletion of a saved Personalization remains subject to future retention rules and never changes historical Order Item snapshots.

## Invariants

- A Personalization always has one stable identity.
- A Personalization always belongs to exactly one User in MVP.
- The owning User of a Personalization never changes in MVP.
- A Personalization always has exactly one immutable Created By User.
- The owning User and Created By User are always the same at creation in MVP.
- A Personalization always has exactly one immutable FINALIZED Revision base.
- A Personalization never directly belongs to a Workspace or Listing in MVP.
- A Personalization never uses a Ready-Made Product as its technical base in MVP.
- A Personalization never has a Manufacturer Profile relationship in MVP.
- A Personalization has no lifecycle state in MVP.
- A Personalization is never published or converted to a Revision automatically.
- Later Personalization changes never rewrite immutable historical Order Item snapshots.

## Notes

Personalization is not a Project, Revision, public design variant, Listing, Ready-Made Product, Order, Order Item, manufacturing job, or generic product variant.

Physical output may differ between buyers without creating a new Revision when buyer-specific values remain within the predefined constraints of the same FINALIZED Revision. A reusable design change or a change to personalization zones, parameter structure, ranges, construction, shared geometry, manufacturing files, or structural and safety assumptions requires a new Revision.

Lightweight validation may occur while editing. Authoritative purchase validation occurs before Order confirmation, and all item-specific Manufacturer eligibility required to confirm an Order Item must succeed before confirmation. Additional production-time feasibility revalidation may occur later before production, but it does not replace confirmation-time eligibility. If production-time revalidation later fails, resolution belongs to future fulfillment, failure, and cancellation policy.

Generated SVG, geometry, text layout, image composition, decorative pattern, or other manufacturing-oriented output may remain mutable artifacts of the Personalization workflow. Whether those artifacts are persisted or regenerated remains unresolved, while an Order Item must preserve or reference the reproducible output actually used for production.

Hard versus soft deletion, retention, Organization-owned or shared Personalizations, support-assisted creation, copy or adaptation to another Revision, media and file architecture, buyer-content intellectual-property rules, feasibility for an assigned Manufacturer Profile, and hybrid Ready-Made Product customization remain future concerns.

---

Status: DRAFT

Version: 0.4
