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
- preserving its immutable Publication Designer Profile when Revision-based;
- preserving current design-specific publication-rights context when Revision-based;
- defining the current applicable royalty configuration and royalty-rights context where required;
- commercially offering Revision-defined personalization capability where applicable;
- preserving the boundary between current offer data and historical commerce.

## Relationships

A Listing:

- has exactly one immutable commercial source, which is either one FINALIZED Revision or one Ready-Made Product;
- has exactly one immutable Created By User;
- derives its Workspace context from its commercial source;
- has exactly one immutable Publication Designer Profile when sourced by a FINALIZED Revision and has no Publication Designer Profile when sourced by a Ready-Made Product;
- preserves sufficient current embedded design-specific publication-rights context or basis when sourced by a FINALIZED Revision;
- may preserve a current royalty configuration when sourced by a FINALIZED Revision, must have a valid explicit configuration before activation, and identifies one current User or Organization royalty beneficiary when a configured positive rate requires one;
- may be referenced by zero or more Order Items;
- may be referenced by Audit Log events in the future.

## Business Rules

- A Listing may be created or managed only by a User with effective write authorization for the LISTINGS scope in the Workspace context derived from its commercial source.
- LISTINGS authorization may expose only the source information required to identify and validate a commercial source and does not grant general PROJECTS or READY_MADE_PRODUCTS access.
- A new Listing may be created for a FINALIZED Revision only while its parent Project permits new commercialization.
- Creating a Listing for a FINALIZED Revision requires selection of exactly one Publication Designer Profile at creation and authority to act through that profile in addition to effective LISTINGS write authorization. The selected profile does not need to be VERIFIED merely to create the DRAFT Listing.
- A new Listing may be created for a Ready-Made Product only while that product is ACTIVE.
- A Ready-Made Product Listing has no Publication Designer Profile and does not require Designer Profile authority.
- The commercial source of a Listing cannot be changed. Offering another Revision or Ready-Made Product requires a new Listing.
- A commercial source may have zero or more Listings over time, but no more than one Listing for that source may be ACTIVE at the same time in MVP.
- Created By identifies only the User who created the Listing record in Creastrix and does not determine source ownership, Workspace ownership, seller-of-record, business rights, creative authorship, royalty recipient, manufacturer, Publication Designer Profile, Profile Holder, activation actor, or publication eligibility.
- Deactivation of the Created By User does not rewrite the Created By relationship.
- A Listing has the lifecycle state DRAFT, ACTIVE, PAUSED, or ARCHIVED.
- A Listing may transition from DRAFT to ACTIVE or ARCHIVED, from ACTIVE to PAUSED or ARCHIVED, and from PAUSED to ACTIVE or ARCHIVED.
- A DRAFT Listing is being prepared, is not active for public commerce, is not orderable, and may be edited according to LISTINGS authorization.
- An ACTIVE Listing has commercial activation enabled and may be publicly presented, but ACTIVE alone does not guarantee effective orderability.
- A PAUSED Listing is temporarily disabled for new commerce, is not orderable, retains its commercial settings and history, and may return to ACTIVE.
- An ARCHIVED Listing is permanently closed for new commerce in MVP, retains historical references, and does not return to ACTIVE.
- Activating or reactivating a Listing requires effective LISTINGS authorization, a source that permits commerce, valid current pricing information, applicable business eligibility, and any required publication and royalty validation.
- Activating or reactivating a Listing sourced by a FINALIZED Revision requires authority to act through its immutable Publication Designer Profile, that profile to be VERIFIED with a valid non-empty current public display/studio name, valid current design-specific publication rights for that profile and Revision, an explicit valid royalty configuration, and applicable royalty-rights validation.
- Effective LISTINGS write authorization in the source-derived Workspace and authority to act through the selected Publication Designer Profile are independent authorization axes. Neither substitutes for the other.
- A User-held Publication Designer Profile is used through authority of its Holder User. An Organization-held Publication Designer Profile is used by a User with an ACTIVE Organization Membership with the role OWNER in the Profile Holder Organization unless a future explicit delegation rule authorizes another actor.
- Generic Organization Membership, Workspace Membership, and LISTINGS authorization alone do not provide authority to act through an Organization-held Designer Profile.
- Designer Profile publication eligibility and Royalty beneficiary identity are separate concerns and must not be collapsed. Designer Profile is not a royalty beneficiary, payout account, payment account, or monetary recipient merely because it enabled publication.
- VERIFIED Designer Profile status establishes only profile-level publication eligibility and does not prove authorship, intellectual-property ownership, design-specific publication rights, or royalty entitlement.
- A FINALIZED Revision Listing preserves sufficient current embedded design-specific publication-rights context or basis to evaluate activation, reactivation, effective orderability, and Order confirmation and to support historical Order Item snapshotting. That context applies specifically to the Listing's immutable source Revision and immutable Publication Designer Profile and cannot be reused or interpreted as rights context for another Revision or Designer Profile. Exact rights evidence storage remains future work and no Design Right or License entity is introduced in MVP.
- Design-specific publication-rights evidence or context may be renewed or revalidated prospectively, but every activation, reactivation, and Order confirmation requires valid current rights for the same immutable source Revision and Publication Designer Profile. Such changes do not change Listing lifecycle automatically or rewrite historical Order Item snapshots.
- The Publication Designer Profile relationship is immutable for the entire Listing lifetime, including while DRAFT, ACTIVE, PAUSED, or ARCHIVED. Publishing the same Revision through another Designer Profile requires another Listing.
- The Publication Designer Profile referenced by any existing Revision-based Listing cannot be destructively deleted, regardless of whether the Listing is DRAFT, ACTIVE, PAUSED, or ARCHIVED.
- A Designer Profile may serve as Publication Designer Profile for Listings from multiple Workspaces. Each Listing independently requires effective LISTINGS authorization, profile authority, and all applicable source, design-rights, royalty, and business validation; Workspace ownership and Profile Holder identity need not match.
- If the Publication Designer Profile becomes UNVERIFIED or SUSPENDED, or current design-specific publication rights cease to be valid, an ACTIVE Listing retains its lifecycle state but becomes effectively non-orderable for new purchases.
- Recovery of the same profile's VERIFIED status or of valid design-specific publication rights may restore effective orderability when all other current requirements pass. Neither loss nor recovery changes Listing lifecycle automatically.
- Loss or recovery of profile or design-specific rights eligibility does not mutate saved Personalizations, historical Orders, immutable Order Item snapshots, or existing Royalties.
- A FINALIZED Revision Listing uses exactly one MVP royalty calculation method, PERCENTAGE, with an integer rate from zero through 10,000 basis points inclusive.
- The royalty calculation basis is NET_ITEM_MERCHANDISE_CONTRIBUTION_V1, and the rounding rule is HALF_UP_MINOR_UNIT_V1. Fixed royalty amounts and generic royalty calculation expressions are unsupported in MVP.
- A positive royalty rate requires exactly one explicit current royalty beneficiary of type USER or ORGANIZATION, together with the applicable live User or Organization reference, rights identity or context, and source or basis of the royalty right.
- A zero royalty rate is permitted only when applicable business rules explicitly allow the zero-royalty configuration. Such a configuration does not require a monetary beneficiary but must preserve an explicit zero-royalty decision and context.
- Royalty beneficiary is established only through validated royalty-rights context. It is not inferred automatically from Project Created By, Revision Created By, Listing Created By, Workspace owner, Project Effective Business Rights Holder, Buyer, Manufacturer Profile, or Designer Profile.
- A Listing sourced by a FINALIZED Revision may commercially offer Personalization only when that Revision defines technical personalization capability.
- A Listing determines whether and under what current commercial restrictions Revision-defined personalization capability is offered. It may narrow but never expand the Revision's technical personalization constraints.
- Initial buyer creation of a Personalization requires a suitable ACTIVE Listing targeting its FINALIZED Revision base and does not require the buyer to have LISTINGS authorization.
- A Personalization does not retain the Listing used during creation as a permanent relationship.
- A saved Personalization may later be used through another suitable Listing targeting the same FINALIZED Revision after current technical and commercial revalidation.
- A Listing becoming PAUSED or ARCHIVED does not mutate a saved Personalization.
- Effective orderability with Personalization requires a currently valid Personalization under the applicable Revision and Listing restrictions in addition to the other Listing, source, business, fulfillment, and Order confirmation rules.
- A Ready-Made Product Listing does not have or require a Publication Designer Profile, Manufacturer Profile, or designer royalty merely because of its source type; seller eligibility remains separate from LISTINGS authorization.
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
- The confirmed Order Item unit merchandise price and line merchandise amount are known and snapshotted at Order confirmation; the detailed quoting workflow remains future commerce work.
- Order confirmation for a Revision-based Listing requires its immutable Publication Designer Profile to remain VERIFIED with a valid non-empty current public display/studio name and its current design-specific publication rights for that exact source Revision and profile to remain valid in addition to all Listing, source, business, pricing, fulfillment, royalty, and other applicable orderability rules.
- Listing commercial presentation and current terms may change while the Listing is DRAFT, ACTIVE, or PAUSED, subject to authorization and business rules. Changes apply prospectively and never rewrite historical Order Item snapshots.
- Royalty configuration is a narrow commercially sensitive exception and cannot change while a Listing is ACTIVE. Royalty configuration includes method, rate, calculation basis and version, rounding rule, beneficiary, and royalty-rights context or basis.
- Changing royalty configuration requires transition from ACTIVE to PAUSED when applicable, editing under effective LISTINGS authorization and applicable rights rules, and successful reactivation validation before new commerce. A new Listing is not required merely because royalty rate or beneficiary changes.
- Royalty configuration changes apply only to future Order confirmations. Existing Order Item royalty snapshots and Royalties remain unchanged.
- A Listing owns public commercial presentation rather than source product identity, product-defining content, manufacturing files, stock mechanics, Workspace ownership, authentication, or fulfillment history.
- Listing is manufacturer-independent in MVP. The assigned Manufacturer identity for made-to-order commerce is a Manufacturer Profile selected through pre-confirmation workflow and assigned to Order Item at confirmation.
- A VERIFIED Manufacturer Profile is a prerequisite for new made-to-order work but does not by itself establish item-specific suitability, capacity, or acceptance.

## Invariants

- A Listing always has one stable domain identity.
- A Listing always has exactly one commercial source.
- A Listing source is always either exactly one FINALIZED Revision or exactly one Ready-Made Product, never both.
- The commercial source of a Listing never changes.
- A Listing always has exactly one immutable Created By User.
- The Workspace context of a Listing is always derived from its immutable commercial source in MVP.
- A Listing never has an independent Workspace ownership relationship in MVP.
- A FINALIZED Revision Listing always has exactly one immutable Publication Designer Profile selected at creation.
- A Ready-Made Product Listing never has a Publication Designer Profile in MVP.
- The Publication Designer Profile of a Listing never changes.
- Every FINALIZED Revision Listing always preserves sufficient current design-specific publication-rights context or basis to determine current eligibility and provide historical snapshot context.
- The design-specific publication-rights context of a FINALIZED Revision Listing always pertains to that Listing's immutable source Revision and immutable Publication Designer Profile.
- A Listing always has exactly one lifecycle state: DRAFT, ACTIVE, PAUSED, or ARCHIVED.
- An ARCHIVED Listing never returns to active commerce in MVP.
- A commercial source never has more than one ACTIVE Listing at the same time in MVP.
- A Listing always uses exactly one currency in MVP.
- An ACTIVE FINALIZED Revision Listing always preserves exactly one valid explicit current royalty configuration using PERCENTAGE, NET_ITEM_MERCHANDISE_CONTRIBUTION_V1, and HALF_UP_MINOR_UNIT_V1.
- Whenever a FINALIZED Revision Listing has a royalty configuration, its rate is an integer from zero through 10,000 basis points inclusive.
- Whenever a configured royalty rate is positive, it has exactly one explicit current USER or ORGANIZATION beneficiary, while an explicitly permitted zero-rate configuration may have no monetary beneficiary.
- Royalty configuration never changes while a Listing is ACTIVE.
- Listing changes never rewrite immutable historical Order Item snapshots.

## Notes

Listing lifecycle and effective orderability are separate. Effective orderability is evaluated from current Listing lifecycle, source state, business eligibility, pricing validity, source-specific fulfillment conditions, and Order confirmation rules; it is not a separate persisted Listing state in this specification.

The Workspace context of a Listing is source-derived and is not a legal seller-of-record, tax merchant, payout recipient, manufacturer, or proof of legal ownership. Creastrix-first ready-made selling remains platform policy. Third-party seller eligibility, reseller offers, and any future separate offering commercial context remain future work.

A source may later have canonical or reference media, while Listing owns or selects its public and promotional presentation. Exact media relationships remain future work, and no Media entity is introduced here.

Revision defines immutable technical personalization capability and constraints, while a Revision-sourced Listing determines whether and under what narrower current commercial restrictions that capability is offered. Personalization remains a private buyer object without a permanent Listing relationship. Ordinary ready-made commerce does not include customization requiring fabrication or product-defining production work.

Multi-currency, multiple simultaneously ACTIVE channels, manufacturer-specific offers, detailed quoting, tax and shipping price presentation, deletion and retention, public URL or slug behavior, and exact visibility of PAUSED, ARCHIVED, or non-orderable Listings remain future concerns.

Actual Royalty accrual is not historical state inside Listing. Order Items preserve the immutable purchased Listing reference and applicable source, Publication Designer Profile identity and publication context, merchandise amounts, currency, royalty calculation, beneficiary and rights context, commercial context, Personalization, fulfillment, and Manufacturer Profile snapshots required for historical commerce. Current Listing, Project, Workspace, or Designer Profile changes never rewrite those confirmed snapshots.

Listing Created By User, activation actor, Publication Designer Profile, and Profile Holder are separate concepts. Exact acting User and activation-time provenance may later be preserved by Audit Log rather than another permanent Listing relationship.

Designer Profile publication eligibility, design-specific publication rights, Project business rights, creative authorship, intellectual-property ownership, and Royalty beneficiary identity are independent concerns. A Listing relationship does not collapse them.

Exact storage of royalty-right evidence remains future domain and implementation work. A positive-rate Listing nevertheless requires enough validated rights context to establish its explicit current User or Organization beneficiary before activation or reactivation.

Significant creation, activation, pause, archive, pricing, and royalty-term events may later be recorded through Audit Log behavior.

---

Status: DRAFT

Version: 0.7
