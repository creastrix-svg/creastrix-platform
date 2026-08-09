# Listing

## Purpose

A Listing represents the commercial and public offer of exactly one immutable commercial source.

It answers how a specific source is offered to buyers while the source itself defines what is being offered.

## Responsibilities

A Listing is responsible for:

- representing a stable commercial offer identity;
- preserving its immutable commercial source;
- managing public commercial presentation;
- managing current pricing and currency context;
- controlling commercial activation through its lifecycle;
- recording the User who created the record;
- defining applicable royalty terms where required;
- commercially offering Revision-defined personalization capability where applicable;
- preserving the boundary between current offer data and historical commerce.

## Relationships

A Listing:

- has exactly one immutable commercial source, which is either one FINALIZED Revision or one Ready-Made Product;
- has exactly one immutable Created By User;
- derives its Workspace context from its commercial source;
- may later be referenced by zero or more Order Items;
- may be referenced by Audit Log events in the future.

## Business Rules

- A Listing may be created or managed only by a User with effective write authorization for the LISTINGS scope in the Workspace context derived from its commercial source.
- LISTINGS authorization may expose only the source information required to identify and validate a commercial source and does not grant general PROJECTS or READY_MADE_PRODUCTS access.
- A new Listing may be created for a FINALIZED Revision only while its parent Project permits new commercialization.
- A new Listing may be created for a Ready-Made Product only while that product is ACTIVE.
- The commercial source of a Listing cannot be changed. Offering another Revision or Ready-Made Product requires a new Listing.
- A commercial source may have zero or more Listings over time, but no more than one Listing for that source may be ACTIVE at the same time in MVP.
- Created By identifies only the User who created the Listing record in Creastrix and does not determine source ownership, Workspace ownership, seller-of-record, business rights, creative authorship, royalty recipient, manufacturer, or publication eligibility.
- Deactivation of the Created By User does not rewrite the Created By relationship.
- A Listing has the lifecycle state DRAFT, ACTIVE, PAUSED, or ARCHIVED.
- A Listing may transition from DRAFT to ACTIVE or ARCHIVED, from ACTIVE to PAUSED or ARCHIVED, and from PAUSED to ACTIVE or ARCHIVED.
- A DRAFT Listing is being prepared, is not active for public commerce, is not orderable, and may be edited according to LISTINGS authorization.
- An ACTIVE Listing has commercial activation enabled and may be publicly presented, but ACTIVE alone does not guarantee effective orderability.
- A PAUSED Listing is temporarily disabled for new commerce, is not orderable, retains its commercial settings and history, and may return to ACTIVE.
- An ARCHIVED Listing is permanently closed for new commerce in MVP, retains historical references, and does not return to ACTIVE.
- Activating a Listing requires effective LISTINGS authorization, a source that permits commerce, valid current pricing information, applicable business eligibility, and any required royalty terms.
- Activating a Listing sourced by a FINALIZED Revision requires an acting context backed by a verified Designer Profile and explicitly defined applicable royalty terms, which may be zero only when applicable business rules permit.
- A Listing sourced by a FINALIZED Revision may commercially offer Personalization only when that Revision defines technical personalization capability.
- A Listing determines whether and under what current commercial restrictions Revision-defined personalization capability is offered. It may narrow but never expand the Revision's technical personalization constraints.
- Initial buyer creation of a Personalization requires a suitable ACTIVE Listing targeting its FINALIZED Revision base and does not require the buyer to have LISTINGS authorization.
- A Personalization does not retain the Listing used during creation as a permanent relationship.
- A saved Personalization may later be used through another suitable Listing targeting the same FINALIZED Revision after current technical and commercial revalidation.
- A Listing becoming PAUSED or ARCHIVED does not mutate a saved Personalization.
- Effective orderability with Personalization requires a currently valid Personalization under the applicable Revision and Listing restrictions in addition to the other Listing, source, business, fulfillment, and future Order rules.
- A Ready-Made Product Listing does not require a Designer Profile, Manufacturer Profile, or designer royalty merely because of its source type; seller eligibility remains separate from LISTINGS authorization.
- A Ready-Made Product Listing does not support product-defining Personalization requiring fabrication, cutting, engraving, or other production customization in ordinary MVP fulfillment.
- Loss of required business eligibility does not change Listing lifecycle automatically but makes an existing Listing effectively non-orderable.
- A source lifecycle change does not change Listing lifecycle automatically. An ARCHIVED or DELETED parent Project, or an ARCHIVED Ready-Made Product, makes an existing Listing non-orderable under current source rules.
- If an ARCHIVED Project or Ready-Made Product returns to ACTIVE, effective orderability may recover when all other conditions hold.
- Ready-Made Product quantity changes do not change Listing lifecycle; an ACTIVE Listing with insufficient available quantity remains ACTIVE but is non-orderable.
- A Project cannot move between Workspaces while any Listing targeting any of its Revisions is ACTIVE. DRAFT and PAUSED Listings do not by themselves block an otherwise permitted same-owner move.
- After a permitted Project move, Listings targeting its Revisions continue to derive their Workspace context from their immutable sources and therefore from the target Workspace. Further management of DRAFT or PAUSED Listings requires effective LISTINGS authorization there; ARCHIVED Listings remain closed for new commerce.
- A Listing uses exactly one currency in MVP.
- A Ready-Made Product Listing uses a fixed unit sale price in MVP.
- A FINALIZED Revision Listing may use base, display, or indicative pricing; when further calculation is required, that price must not be represented as a guaranteed final price.
- The final customer price is established and snapshotted in Order Item before order confirmation; the detailed quoting workflow remains future commerce work.
- Listing commercial presentation and current terms may change while the Listing is DRAFT, ACTIVE, or PAUSED, subject to authorization and business rules. Changes apply prospectively and never rewrite historical Order Item snapshots.
- A Listing owns public commercial presentation rather than source product identity, product-defining content, manufacturing files, stock mechanics, Workspace ownership, authentication, or fulfillment history.
- Listing is manufacturer-independent in MVP. The future assigned Manufacturer identity for made-to-order commerce is a Manufacturer Profile selected and assigned later in the Order or Order Item flow.
- A VERIFIED Manufacturer Profile is a prerequisite for new made-to-order work but does not by itself establish item-specific suitability, capacity, or acceptance.

## Invariants

- A Listing always has one stable domain identity.
- A Listing always has exactly one commercial source.
- A Listing source is always either exactly one FINALIZED Revision or exactly one Ready-Made Product, never both.
- The commercial source of a Listing never changes.
- A Listing always has exactly one immutable Created By User.
- The Workspace context of a Listing is always derived from its immutable commercial source in MVP.
- A Listing never has an independent Workspace ownership relationship in MVP.
- A Listing always has exactly one lifecycle state: DRAFT, ACTIVE, PAUSED, or ARCHIVED.
- An ARCHIVED Listing never returns to active commerce in MVP.
- A commercial source never has more than one ACTIVE Listing at the same time in MVP.
- A Listing always uses exactly one currency in MVP.
- Listing changes never rewrite immutable historical Order Item snapshots.

## Notes

Listing lifecycle and effective orderability are separate. Effective orderability is evaluated from current Listing lifecycle, source state, business eligibility, pricing validity, source-specific fulfillment conditions, and future Order rules; it is not a separate persisted Listing state in this specification.

The Workspace context of a Listing is source-derived and is not a legal seller-of-record, tax merchant, payout recipient, manufacturer, or proof of legal ownership. Creastrix-first ready-made selling remains platform policy. Third-party seller eligibility, reseller offers, and any future separate offering commercial context remain future work.

A source may later have canonical or reference media, while Listing owns or selects its public and promotional presentation. Exact media relationships remain future work, and no Media entity is introduced here.

Revision defines immutable technical personalization capability and constraints, while a Revision-sourced Listing determines whether and under what narrower current commercial restrictions that capability is offered. Personalization remains a private buyer object without a permanent Listing relationship. Ordinary ready-made commerce does not include customization requiring fabrication or product-defining production work.

Multi-currency, multiple simultaneously ACTIVE channels, manufacturer-specific offers, detailed quoting, tax and shipping price presentation, royalty representation format, deletion and retention, public URL or slug behavior, and exact visibility of PAUSED, ARCHIVED, or non-orderable Listings remain future concerns.

Actual Royalty accrual is not historical state inside Listing. Future Order Items preserve the applicable Listing, source, final price, currency, royalty, commercial context, Personalization, fulfillment, and Manufacturer Profile snapshots required for historical commerce.

Significant creation, activation, pause, archive, pricing, and royalty-term events may later be recorded through Audit Log behavior.

---

Status: DRAFT

Version: 0.3
