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
- preserving the current prospective royalty configuration and royalty-rights context where required;
- preserving currently applicable accepted royalty-rights validation context for the exact current royalty configuration where available or required;
- keeping Listing management authorization, publication identity, design-specific publication rights, and royalty-rights validation separate;
- commercially offering Revision-defined personalization capability where applicable;
- preserving the boundary between current offer data and historical commerce.

## Relationships

A Listing:

- has exactly one immutable commercial source, which is either one FINALIZED Revision or one Ready-Made Product;
- has exactly one immutable Created By User;
- derives its Workspace context from its commercial source;
- has exactly one immutable Publication Designer Profile when sourced by a FINALIZED Revision and has no Publication Designer Profile when sourced by a Ready-Made Product;
- preserves sufficient current embedded design-specific publication-rights context or basis when sourced by a FINALIZED Revision;
- may preserve a current prospective royalty configuration when sourced by a FINALIZED Revision, must have a valid explicit configuration before activation, and identifies one current User or Organization royalty beneficiary when a configured positive rate requires one;
- may preserve current embedded accepted royalty-rights validation context for its exact current royalty validation subject without introducing a relationship to a separate validation entity;
- may be referenced by zero or more Order Items;
- may be referenced by Audit Log events in the future.

## Business Rules

- A Listing may be created or managed only by a User with effective write authorization for the LISTINGS scope in the Workspace context derived from its commercial source.
- LISTINGS authorization may expose only the source information required to identify and validate a commercial source and does not grant general PROJECTS or READY_MADE_PRODUCTS access.
- When the Listing lifecycle permits editing, a User with effective LISTINGS write authorization may manage a proposed royalty configuration, including the rate, explicit beneficiary for a positive rate, explicit zero-royalty decision where permitted, and royalty-rights source, basis, or context.
- LISTINGS authorization alone never establishes accepted royalty-rights validation and never makes a proposed configuration eligible for activation, reactivation, effective orderability, or Order confirmation.
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
- A Listing cannot be destructively deleted in MVP in any lifecycle state, including DRAFT, ACTIVE, PAUSED, or ARCHIVED. ARCHIVED is the normal retained permanent closure state for new commerce.
- Destructive cleanup of a never-used DRAFT or PAUSED Listing remains future explicit deletion and retention work. Current MVP does not use conditional hard-delete rules based on Order Item references or other mutable reference counts.
- Activating or reactivating a Listing requires effective LISTINGS authorization, a source that permits commerce, valid current pricing information, applicable business eligibility, and every required publication and royalty validation.
- Activating or reactivating a Listing sourced by a FINALIZED Revision requires authority to act through its immutable Publication Designer Profile, that profile to be VERIFIED with a valid non-empty current public display/studio name, valid current design-specific publication rights for that exact profile and Revision, an explicit valid royalty configuration, and currently applicable platform-accepted royalty-rights validation matching the exact current royalty validation subject.
- For Revision-based Listing commerce, effective LISTINGS write authorization, authority through the immutable Publication Designer Profile, valid design-specific publication rights for the exact Revision and Publication Designer Profile, and accepted royalty-rights validation for the exact current royalty configuration are four independent axes. None substitutes for another.
- LISTINGS authorization, Designer Profile authority or VERIFIED status, design-specific publication rights, Workspace ownership, and Project business rights do not by themselves prove royalty entitlement or determine the royalty beneficiary.
- Only an authorized platform royalty-rights validation workflow may establish accepted royalty-rights validation context.
- A LISTINGS editor, Workspace ADMIN, Workspace owner, Organization OWNER, Designer Profile Holder, or proposed beneficiary does not receive platform royalty-rights validation authority merely through that role or relationship.
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
- Royalty configuration is current prospective Listing data. It is not Royalty accrual, Payout, Payment Allocation, authorship, publication identity, or seller identity.
- A positive royalty rate requires exactly one explicit current royalty beneficiary of type USER or ORGANIZATION, together with the applicable live User or Organization reference, rights identity or context, and source or basis of the royalty right.
- A zero royalty rate is permitted only when applicable business rules explicitly allow the zero-royalty configuration. Such a configuration does not require a monetary beneficiary but must preserve an explicit zero-royalty decision and context.
- The explicit positive-rate beneficiary in the current Listing configuration must equal the beneficiary accepted by the currently applicable matching royalty-rights validation. Royalty beneficiary is never inferred automatically from Project Created By, Revision Created By, Listing Created By, Workspace owner, Project Effective Business Rights Holder, Publication Designer Profile, Designer Profile Holder, Buyer, or Manufacturer Profile.
- Accepted royalty-rights validation is embedded accepted domain context of the Listing and is not a separate entity. A DRAFT or PAUSED Revision-based Listing may have no currently applicable accepted validation.
- Accepted validation exists only after the authorized platform royalty-rights validation workflow accepts the exact royalty validation subject. Evidence may be provided or requested through workflow, but ordinary Listing or profile authority cannot establish the accepted domain fact.
- The exact royalty validation subject is bound to one Listing and preserves correspondence to its identity, immutable FINALIZED Revision source, immutable Publication Designer Profile as publication context, royalty method, rate or explicit zero-rate decision, calculation basis and version, rounding rule and version, positive-rate beneficiary type and identity when applicable, royalty-rights source, basis, or context, and validation policy or rules version.
- Inclusion of the Publication Designer Profile in the exact validation subject only binds validation to the Listing's publication context. It never makes that Profile, its Holder, or its publication eligibility the royalty beneficiary, source of royalty entitlement, or payout recipient.
- Current accepted validation context preserves a stable accepted validation or decision identity, acceptance timestamp, applicable validation policy or rules version, the exact validated subject or sufficient immutable representation of it, and sufficient royalty-rights source or basis context for domain traceability.
- Exact storage representation and full external legal or evidence documents remain implementation and compliance concerns. No Design Right, License, Royalty Right, Rights Validation, Royalty Validation, Validation Decision, or generic Approval entity is introduced in MVP.
- A rejected or failed validation attempt creates no accepted royalty-rights validation context. Rejected-decision and evidence retention may remain workflow, compliance, or future Audit Log concerns without adding Listing validation lifecycle states.
- A Revision-based Listing without currently applicable accepted royalty-rights validation matching its exact current royalty validation subject is not eligible for activation, reactivation, effective orderability, or Order confirmation.
- The fail-closed validation requirement applies to both a positive royalty configuration and an explicitly permitted zero-royalty configuration. A zero rate never bypasses royalty-rights validation.
- Any material change to the validated royalty subject makes the prior accepted validation inapplicable. Material changes include rate, beneficiary identity or type, positive-versus-zero decision, and royalty-rights source, basis, or context.
- Source Revision and Publication Designer Profile are immutable for an existing Listing, and royalty method, calculation basis, and rounding rule are fixed in the current MVP. Any future version that permits a material change to one of those subject components must require new accepted validation.
- Changes only to marketing copy, public presentation, ordinary price or presentation metadata, or unrelated Personalization commercial restrictions do not by themselves invalidate otherwise applicable royalty-rights validation.
- Accepted validation may cease to be currently applicable through an authorized prospective revocation, expiry, policy decision, or determination that the validated rights context no longer satisfies applicable rules.
- If accepted validation ceases to be applicable while a Listing is ACTIVE, the Listing remains ACTIVE but becomes effectively non-orderable and cannot support a new Order confirmation. The event does not automatically pause or archive the Listing, rewrite its royalty configuration, or change historical Order Items or Royalties.
- A new accepted validation for the exact unchanged current royalty validation subject may renew or replace current validation context without changing the royalty configuration or requiring the Listing to be PAUSED. It may restore effective orderability when every other current requirement passes.
- Interactive beneficiary consent is not a mandatory per-Listing invariant in MVP. The accepted royalty-rights context may instead be supported by employment, assignment, contract, or another applicable rights basis under future evidence and policy requirements.
- A beneficiary User's current account status does not determine royalty entitlement and does not rewrite beneficiary identity or accepted historical royalty-rights context. Current Payout and compliance eligibility remains separate.
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
- Order confirmation for a Revision-based Listing requires its immutable Publication Designer Profile to remain VERIFIED with a valid non-empty current public display/studio name, its current design-specific publication rights for that exact source Revision and profile to remain valid, and currently applicable accepted royalty-rights validation whose exact validated subject still matches the current royalty configuration, in addition to all Listing, source, business, pricing, fulfillment, and other applicable orderability rules.
- Order confirmation fails closed when royalty-rights validation is absent, stale, mismatched, or no longer applicable.
- Listing commercial presentation and current terms may change while the Listing is DRAFT, ACTIVE, or PAUSED, subject to authorization and business rules. Changes apply prospectively and never rewrite historical Order Item snapshots.
- Royalty configuration is a narrow commercially sensitive exception and cannot change while a Listing is ACTIVE. Royalty configuration includes method, rate, calculation basis and version, rounding rule, beneficiary, and royalty-rights context or basis.
- Changing royalty configuration requires transition from ACTIVE to PAUSED when applicable, editing the proposed configuration under effective LISTINGS authorization, obtaining new matching platform-accepted royalty-rights validation, and successful reactivation before new commerce. The prior validation becomes inapplicable to the changed subject. A new Listing is not required merely because royalty rate or beneficiary changes.
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
- A Listing is never destructively deleted in MVP.
- A commercial source never has more than one ACTIVE Listing at the same time in MVP.
- A Listing always uses exactly one currency in MVP.
- An ACTIVE FINALIZED Revision Listing always preserves exactly one valid explicit current royalty configuration using PERCENTAGE, NET_ITEM_MERCHANDISE_CONTRIBUTION_V1, and HALF_UP_MINOR_UNIT_V1.
- Whenever a FINALIZED Revision Listing has a royalty configuration, its rate is an integer from zero through 10,000 basis points inclusive.
- Whenever a configured royalty rate is positive, it has exactly one explicit current USER or ORGANIZATION beneficiary, while an explicitly permitted zero-rate configuration may have no monetary beneficiary.
- Royalty configuration never changes while a Listing is ACTIVE.
- Effective orderability of a Revision-based Listing always requires currently applicable accepted royalty-rights validation matching its exact current royalty validation subject.
- Order confirmation never succeeds against absent, stale, mismatched, or no-longer-applicable royalty-rights validation.
- A material change to the royalty validation subject always makes prior accepted validation inapplicable to the changed subject.
- Accepted royalty-rights validation validates the explicit positive-rate beneficiary or explicit zero-rate decision and never infers a beneficiary from other domain relationships.
- Listing changes never rewrite immutable historical Order Item snapshots.

## Notes

Listing lifecycle and effective orderability are separate. Effective orderability is evaluated from current Listing lifecycle, source state, business eligibility, pricing validity, source-specific fulfillment conditions, and Order confirmation rules; it is not a separate persisted Listing state in this specification.

The Workspace context of a Listing is source-derived and is not a legal seller-of-record, tax merchant, payout recipient, manufacturer, or proof of legal ownership. Creastrix-first ready-made selling remains platform policy. Third-party seller eligibility, reseller offers, and any future separate offering commercial context remain future work.

A source may later have canonical or reference media, while Listing owns or selects its public and promotional presentation. Exact media relationships remain future work, and no Media entity is introduced here.

Revision defines immutable technical personalization capability and constraints, while a Revision-sourced Listing determines whether and under what narrower current commercial restrictions that capability is offered. Personalization remains a private buyer object without a permanent Listing relationship. Ordinary ready-made commerce does not include customization requiring fabrication or product-defining production work.

Multi-currency, multiple simultaneously ACTIVE channels, manufacturer-specific offers, detailed quoting, tax and shipping price presentation, public URL or slug behavior, and exact visibility of PAUSED, ARCHIVED, or non-orderable Listings remain future concerns.

Destructive deletion means physical or domain removal of the stable Listing identity such that existing references can no longer resolve it. It is distinct from ARCHIVED lifecycle, UI hiding, and future archival or pseudonymization behavior. Destructive Listing deletion is unsupported in MVP; exact retention duration and any future cleanup of never-used Listings require separate legal, compliance, and domain work.

Actual Royalty accrual is not historical state inside Listing. Order Items preserve the immutable purchased Listing reference and applicable source, Publication Designer Profile identity and publication context, merchandise amounts, currency, royalty calculation, beneficiary and rights context, commercial context, Personalization, fulfillment, and Manufacturer Profile snapshots required for historical commerce. Current Listing, Project, Workspace, or Designer Profile changes never rewrite those confirmed snapshots.

Listing Created By User, activation actor, Publication Designer Profile, and Profile Holder are separate concepts. Exact acting User and activation-time provenance may later be preserved by Audit Log rather than another permanent Listing relationship.

Designer Profile publication eligibility, design-specific publication rights, Project business rights, creative authorship, intellectual-property ownership, and Royalty beneficiary identity are independent concerns. A Listing relationship does not collapse them.

Exact storage of full royalty-right evidence remains future implementation and compliance work. Royalty-rights validation semantics are defined here: platform acceptance must bind to the exact Listing and current royalty validation subject, and both positive-rate and explicit zero-rate configurations fail closed without a currently applicable matching accepted validation.

Concurrent royalty configuration editing, validation acceptance or invalidation, activation or reactivation, and Order confirmation must never permit use of validation accepted for another configuration. The accepted validation subject must exactly match the current Listing royalty subject at each decision boundary. Database locking, version checks, optimistic concurrency, and equivalent implementation mechanisms remain implementation concerns and do not require a Validation Reservation entity.

Significant creation, activation, pause, archive, pricing, and royalty-term events may later be recorded through Audit Log behavior.

Future relational persistence must preserve every confirmed Order Item's purchased Listing relationship and prevent destructive cascade deletion into historical commerce. Exact foreign-key and storage mechanisms remain implementation work.

---

Status: DRAFT

Version: 0.9
