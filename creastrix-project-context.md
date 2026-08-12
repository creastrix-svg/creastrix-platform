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

The following specifications are DRAFT. They represent active architecture work and are not independently approved.

- Workspace
- Workspace Membership
- Ready-Made Product
- Project
- Revision
- Designer Profile
- Designer Review
- Listing
- Personalization
- Manufacturer Profile
- Order
- Order Item
- Payment
- Payment Allocation
- Royalty
- Payout
- Shipment

## Current Implementation State

Specification approval and implementation coverage are separate dimensions. An APPROVED specification records accepted architecture for its entity; it does not mean that every approved rule has been implemented.

The `main` branch currently contains these backend foundations:

- backend bootstrap;
- User and User Profile foundation;
- User repository port with an explicit JDBC adapter;
- Organization and Organization Membership foundation.

This is not a full product implementation and does not mean that all behavior in the approved specifications has been delivered.

Workspace, Workspace Membership, and the other DRAFT domains are not approved for implementation merely because their current decisions are summarized in this document. They remain subject to their own architecture review and independent specification approval.

## Domain Principles

- Domain first.
- Clear responsibilities.
- Small steps.
- Document decisions.
- Commit approved changes.

## Important Decisions

- User represents identity and has exactly one account and access status: ACTIVE, SUSPENDED, or DEACTIVATED. A new User starts ACTIVE.
- Ordinary User-driven or delegated authority requires ACTIVE User status in addition to every other applicable authorization and domain rule. ACTIVE status alone grants no business authority.
- User suspension or deactivation preserves identity, history, Memberships, ownership, Profile Holder identity, provenance, buyer, reviewer, beneficiary, and historical snapshot relationships without mutating them automatically.
- User status constrains authority exercised by that User but does not by itself block independently authorized platform workflows.
- Structural ACTIVE Organization OWNER and ACTIVE Workspace ADMIN Membership invariants remain separate from User actionability.
- User Profile stores personal information.
- Organization is a first-class business participant.
- Organization Membership is a real domain entity.
- An ACTIVE Organization Membership with the role OWNER is the structural source of general organization-level authority when no more specific delegation rule exists. Ordinary OWNER authority additionally requires the associated User to be ACTIVE.
- An Organization is operationally orphaned as a derived condition when it has no User who is both ACTIVE and an ACTIVE Organization OWNER. No ORPHANED state or separate recovery entity exists.
- After independent identity and business-control verification, exceptional platform Organization recovery may restore or establish the minimum actionable OWNER and affected Workspace ADMIN representation without rewriting Organization or Workspace ownership, domain history, or prior Membership history.
- Organization does not by itself determine seller-of-record, merchant identity, economic beneficiary, payment recipient, or payout identity. Creastrix is the single buyer-facing seller-of-record and merchant-of-record for current MVP Orders.
- Workspace belongs to exactly one User or Organization.
- Workspace remains a common operational and access boundary and is not limited to design work.
- Workspace ownership and Workspace access are separate concepts.
- In a User-owned Workspace, the User owner remains an ACTIVE ADMIN in MVP and cannot lose administrative access through normal Workspace membership changes.
- Effective ordinary Workspace Membership authorization combines ACTIVE User status, ACTIVE membership status, Workspace role, the relevant permission scope, and rules of the requested domain operation.
- For an ACTIVE User, an ACTIVE ADMIN has full Workspace access in MVP, while EDITOR and VIEWER operate only within explicitly granted scopes.
- PROJECTS is the Workspace permission scope for Project and Revision work.
- READY_MADE_PRODUCTS is the Workspace permission scope for Ready-Made Product management, including simple MVP available quantity.
- LISTINGS is the Workspace permission scope for Listing commercial management.
- PROJECTS, READY_MADE_PRODUCTS, and LISTINGS are independent scopes and do not grant access to one another.
- Future domain areas may introduce additional permission scopes without automatically expanding existing EDITOR or VIEWER access.
- Organization Membership does not automatically grant Workspace access or Workspace permission scopes.
- Every Organization-owned Workspace structurally retains at least one User who is both an ACTIVE Organization OWNER and an ACTIVE Workspace ADMIN. User account actionability is evaluated separately.
- Suspending or deactivating a User does not mutate Workspace Membership or ownership. A User-owned Workspace remains owned by its User and retains that User's structurally ACTIVE ADMIN Membership, while ordinary access is unavailable until the User becomes ACTIVE again; ownership transfer and special personal Workspace recovery are unsupported in MVP.
- Scoped Workspace permissions never grant ownership or business rights.
- Project belongs to exactly one Workspace.
- Project has no separate Business Owner in MVP.
- Project Effective Business Rights Holder derives from the Workspace owner.
- Project DELETED is logical soft deletion. It retains Project identity and never cascade-deletes or mutates Revisions, Listings, Personalizations, confirmed commerce, or immutable historical snapshots.
- Moving a Project requires PROJECTS write authorization in both source and target Workspaces; DRAFT or PAUSED Listings additionally require LISTINGS write authorization in both.
- Ready-Made Product is the stable identity of one independently stocked physical product configuration and belongs to exactly one Workspace.
- The Workspace owner provides the platform-recognized commercial context in which a Ready-Made Product is managed; this does not prove legal ownership, physical custody, seller-of-record, manufacturer, or supplier status.
- Ready-Made Product has the ACTIVE and ARCHIVED lifecycle and may transition in either direction.
- Ready-Made Product cannot be destructively deleted in MVP. ARCHIVED is its retained non-active state, while exact future deletion and retention policy remains separate work.
- Ready-Made Product uses simple non-negative available quantity in MVP; an allocation may be confirmed only when sufficient quantity is available at confirmation, and the same available stock capacity cannot be confirmed for more than one Order Item. Eligible pre-dispatch cancellation may release allocation under the applicable release rule, while post-dispatch terminal non-delivery cancellation never restores the dispatched allocation to available quantity. Lifecycle remains independent from stock availability.
- One independently stocked physical configuration is one Ready-Made Product in MVP; no Product Variant entity exists.
- Ready-Made Product exists independently from Listing and is never published directly.
- Ready-Made Product does not require a Manufacturer Profile.
- Project is the stable identity of a manufacturable product concept and may have multiple Revisions.
- Revision itself carries the DRAFT and FINALIZED lifecycle; no separate Project Draft entity exists.
- Multiple DRAFT Revisions may be developed in parallel.
- A FINALIZED Revision has immutable product-defining content.
- A FINALIZED Revision cannot be destructively deleted in MVP, protecting Listing sources, Personalization bases, Base Revision provenance, and historical Order Item source traceability. DRAFT Revision discard or deletion remains unresolved future work.
- Designer Profile is the stable public professional design identity and platform-verified publication capability of exactly one User or Organization.
- A Designer Profile has exactly one immutable Profile Holder of type USER or ORGANIZATION, never both. A User may directly hold no more than one personal Designer Profile, and an Organization may hold no more than one Designer Profile in MVP.
- Organization-held Designer Profiles through which a User is authorized to act remain Organization-held and do not count against the User's personal Designer Profile cardinality.
- Designer Profile Created By is immutable record-creation provenance and does not determine the Profile Holder, authorship, intellectual-property ownership, publication rights, Royalty beneficiary, payout identity, or permanent management authority.
- Designer Profile eligibility status is UNVERIFIED, VERIFIED, or SUSPENDED. Profile-level verification is separate from design-specific publication-rights validation.
- Every Revision-based Listing selects exactly one immutable Publication Designer Profile at creation and preserves sufficient current design-specific publication-rights context or basis. A Ready-Made Product Listing has no Publication Designer Profile.
- Creating or publishing a Revision-based Listing requires effective LISTINGS authorization in its source-derived Workspace and independent authority to act through the selected Designer Profile. Activation, reactivation, and effective orderability additionally require current profile and design-specific rights eligibility.
- Designer Profile has no mandatory Workspace relationship and is independent from Project ownership, Project business rights, legal authorship, intellectual-property ownership, Manufacturer Profile capability, and Royalty beneficiary identity.
- Loss of Designer Profile or design-specific rights eligibility leaves Listing lifecycle unchanged but makes an ACTIVE Revision-based Listing non-orderable while the loss continues.
- A Revision-based Order Item preserves an immutable historical publication-context snapshot without creating a second authoritative live Designer Profile relationship.
- Designer Review is a dedicated purchase-backed evaluation of the Designer Profile publication identity preserved by exactly one qualifying FULFILLED Revision-based Order Item.
- A Designer Review has exactly one immutable Reviewer User equal to the Buyer User of the qualifying Order, exactly one immutable qualifying Order Item, and exactly one immutable target Designer Profile derived from that Item's publication-context snapshot.
- One Order Item may have at most one Designer Review even when quantity is greater than one, and a WITHDRAWN Review continues to occupy that slot. Another distinct qualifying Order Item may support another Review of the same Designer Profile.
- Designer Review uses one required integer rating from one through five and an optional meaningful textual body. It has the PUBLISHED, HIDDEN, and terminal WITHDRAWN lifecycle, with moderation controlling hide and republish and the Reviewer controlling withdrawal.
- Once an Item legitimately reaches FULFILLED, later partial or full refund does not remove Designer Review eligibility or rewrite Review history. Designer Review remains independent from Payment, Refund Allocation, Manufacturer compensation, Royalty, and Payout.
- Only PUBLISHED Designer Reviews contribute to derived Designer Profile average rating and review count. Designer Profile does not own authoritative rating aggregate fields.
- Designer Review DRAFT 0.1 preserves no public Reviewer name or User Profile snapshot. Public presentation uses only safe non-identifying or explicitly approved attribution and never exposes private Order, Payment, delivery, fulfillment, or Personalization data through the qualifying Item relationship.
- Designer Review is not a generic Review and does not evaluate Manufacturer Profile. Future Manufacturer Review remains a separate domain because its qualifying relationship, target, eligibility, and meaning differ.
- Listing represents the commercial and public offer of exactly one immutable commercial source: a FINALIZED Revision or a Ready-Made Product, never both.
- Listing Workspace context is derived from its commercial source, and source write permission is not required merely to manage Listing commercial data.
- LISTINGS authorization and business eligibility are separate requirements.
- A source may have multiple Listings over time but no more than one ACTIVE Listing at the same time in MVP.
- Listing has the DRAFT, ACTIVE, PAUSED, and ARCHIVED lifecycle; ACTIVE does not guarantee effective orderability.
- Listing cannot be destructively deleted in MVP in any lifecycle state. ARCHIVED is the retained terminal commercial state for new commerce.
- Source archive or loss of required business eligibility makes an existing Listing non-orderable without changing Listing lifecycle automatically.
- A Project cannot move between Workspaces while a Listing targeting any of its Revisions is ACTIVE.
- A Ready-Made Product Listing uses a fixed unit sale price, while a Revision-based Listing may use base or display pricing before confirmed Order Item merchandise amounts are determined.
- Every Listing uses one currency in MVP.
- A Revision-based Listing requires explicit applicable royalty terms before activation; a Ready-Made Product Listing does not create designer royalty automatically.
- For a Revision-based Listing, effective LISTINGS write authorization, authority through its immutable Publication Designer Profile, valid design-specific publication rights for that exact Revision and profile, and accepted royalty-rights validation for the exact current royalty configuration are four independent axes. None substitutes for another.
- LISTINGS authorization permits proposing and editing royalty configuration when lifecycle rules allow, but only an authorized platform royalty-rights validation workflow may establish accepted validation. Accepted validation is embedded Listing domain context rather than a separate entity.
- Accepted royalty-rights validation is bound to one Listing and its exact current royalty validation subject, including the immutable Revision and Publication Designer Profile context, calculation terms, positive-rate beneficiary or explicit zero-rate decision, royalty-rights source or basis, and validation policy version.
- Revision-based Listing activation, reactivation, effective orderability, and Order confirmation fail closed when matching accepted validation is absent, stale, mismatched, or no longer applicable, including for an explicitly permitted zero-rate configuration.
- A material royalty-subject change makes prior accepted validation inapplicable. An ACTIVE Listing whose validation expires or is revoked remains ACTIVE but non-orderable; revalidating the exact unchanged subject may restore orderability without changing its royalty configuration or lifecycle.
- A confirmed Revision-based Order Item freezes the matching accepted validation context for historical traceability while its existing immutable royalty snapshot remains the sole monetary calculation truth. Later Listing or validation changes never rewrite confirmed commerce or existing Royalty history.
- Listing is manufacturer-independent; a Manufacturer Profile for made-to-order commerce is selected through pre-confirmation workflow and assigned to Order Item at confirmation.
- Listing and its source do not determine seller-of-record in MVP; Creastrix is the single buyer-facing seller-of-record and merchant-of-record at Order level, and historical Order Item snapshots are never rewritten by later Listing changes.
- Personalization is a private, reusable buyer-specific configuration with exactly one immutable FINALIZED Revision base.
- Personalization belongs to exactly one User in MVP and preserves immutable Created By provenance.
- Personalization has no direct Workspace or permanent Listing relationship, and no PERSONALIZATIONS Workspace permission scope exists.
- Workspace ownership, Membership, and current Workspace permission scopes do not automatically expose private buyer Personalization.
- Revision defines immutable technical personalization capability and constraints; Listing may commercially offer or narrow but never expand those constraints.
- Initial buyer creation occurs through a suitable ACTIVE Revision-sourced Listing, after which the saved Personalization remains independent from that Listing.
- Personalization has no lifecycle in MVP. Validity is evaluated separately, and the saved object may be temporarily invalid while editing.
- Personalization remains mutable and reusable after purchase, while Order Item snapshots the purchased configuration immutably.
- A future approved physical Personalization deletion may sever only the optional live Order Item traceability relationship. The immutable purchased Personalization snapshot remains authoritative and unchanged, and deletion never cascades into confirmed commerce.
- AI-assisted generation and generated artifacts remain workflow inside Personalization and do not change Created By.
- Personalization never mutates or automatically becomes a Revision.
- Ordinary Ready-Made Product fulfillment does not use Personalization in MVP.
- Confirmed merchandise amounts, currency, royalty terms, and Manufacturer Profile selection remain outside Personalization and are snapshotted through Order Item.
- Royalty rules originate from Listing, and historical commercial context is snapshotted in Order Item.
- Manufacturer Profile is the stable manufacturing-capability identity of exactly one Profile Holder, which is either one User or one Organization, never both.
- A Manufacturer Profile Holder is immutable in MVP, and a User or Organization may hold no more than one Manufacturer Profile. A User's personal profile remains independent from Organization-held profiles through which that User may be authorized to act.
- Manufacturer Profile has exactly one immutable Created By User as historical provenance.
- Manufacturer Profile eligibility status is UNVERIFIED, VERIFIED, or SUSPENDED. Only a VERIFIED profile may be considered for new made-to-order work.
- VERIFIED status is necessary but does not establish item-specific suitability, available capacity, pricing, or Manufacturer acceptance.
- Manufacturer Profile may describe generic declared manufacturing capabilities without defining detailed machines, technologies, facilities, or capacity in MVP.
- An Organization-held Manufacturer Profile is currently managed and used by an ACTIVE Organization OWNER subject to Manufacturer Profile rules; a future explicit delegation may authorize another actor.
- Manufacturer Profile has no mandatory Workspace relationship. Workspace Membership and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not grant Manufacturer Profile management, verification, or work-acceptance authority.
- A made-to-order Order Item has exactly one assigned Manufacturer Profile. Manufacturer is domain shorthand for that assigned profile; no separate Manufacturer entity exists in MVP.
- Actual Manufacturer acceptance belongs to the Order Item confirmation workflow. Later Manufacturer Profile status changes never rewrite historical assignments or automatically cancel existing Order Items.
- Ordinary Ready-Made Product fulfillment does not require a Manufacturer Profile.
- Manufacturer Profile is a manufacturing capability identity, not a seller-of-record, payout account, payment recipient, or tax merchant.
- Order represents one confirmed purchase for exactly one Buyer User, contains one or more Order Items, and uses exactly one currency.
- Order and its fixed Order Item collection are created atomically only at successful confirmation. No DRAFT Order exists in MVP, and pre-confirmation checkout and Manufacturer acceptance remain workflow rather than current entities.
- Creastrix is the single buyer-facing contractual seller-of-record and merchant-of-record for every MVP Order, subject to production legal, tax, invoicing, consumer-protection, VAT, acquiring, KYC, AML, and PSP validation.
- One Order may continue to mix ready-made and made-to-order Items from multiple Workspaces and Manufacturer Profiles because Creastrix provides one buyer-facing seller and merchant context in MVP. Future third-party seller or merchant contexts may require checkout to split selections into separate Orders before confirmation.
- Order preserves exactly one immutable checkout delivery-destination snapshot in MVP, and later User Profile changes do not rewrite it.
- Order has no Workspace relationship, and no ORDERS Workspace permission scope exists in MVP.
- Order confirmed merchandise subtotal equals the sum of immutable Order Item line merchandise amounts.
- Order preserves one immutable confirmation-time seller and merchant context and one immutable monetary snapshot containing merchandise subtotal, buyer-facing shipping charge, tax, aggregate discount, confirmed payable total, and currency.
- The current MVP payable-total rule is merchandise subtotal plus shipping charge plus tax minus aggregate discount; cancellation, refund, or later commercial changes never rewrite the original snapshot.
- Order aggregate lifecycle is CONFIRMED, COMPLETED, or CANCELLED and is derived canonically from Order Item states.
- Order Item represents one confirmed purchased line and is the immutable commercial, source, Personalization, royalty-context, and fulfillment-line snapshot boundary.
- Order Item lifecycle is CONFIRMED, IN_FULFILLMENT, FULFILLED, or CANCELLED. FULFILLED and CANCELLED are terminal.
- Starting Order Item fulfillment is path-specific: made-to-order uses the assigned Manufacturer Profile context, while ready-made uses internal platform fulfillment authorization.
- An immutable snapshotted FINALIZED Revision source uses made-to-order fulfillment, while a Ready-Made Product source uses existing-stock fulfillment in MVP.
- A made-to-order Order Item requires exactly one VERIFIED and item-eligible Manufacturer Profile whose acceptance was obtained before confirmation. Assignment is immutable afterward.
- Every confirmed made-to-order Order Item preserves an immutable, authoritative confirmation-time fact that required Manufacturer acceptance was obtained.
- Confirmation of a ready-made Order Item establishes allocation of its full quantity without overselling.
- An Order Item Personalization snapshot is immutable and authoritative even when the referenced Personalization later changes or is deleted.
- Order Item preserves immutable Listing, source, source Workspace, commercial context, merchandise amounts, and applicable royalty terms without establishing seller-of-record.
- Payment state remains separate from Order confirmation and lifecycle.
- Payment represents one durable buyer-funds collection attempt for exactly one existing Order. An Order may have several Payments over time for retries, but split tender, intentional partial capture, and overpayment are unsupported in MVP.
- Before the first external pre-Order authorization command, payment and checkout integration workflow durably preserves a submission commitment containing stable confirmation-attempt and provider identities, provider correlation context, Buyer, amount and currency, and sufficient frozen prospective purchase intent. The commitment is workflow or integration persistence rather than a core entity.
- Unknown authorization outcome is reconciled through the same stable provider submission identity; an unrelated second authorization and unsafe blind retry are forbidden.
- Accepted pre-Order authorization may be consumed into at most one confirmed Order and one AUTHORIZED Payment. Successful local confirmation atomically creates the Order, Items, applicable ready-made stock allocations, immutable snapshots and Payment and records durable one-time consumption correlation.
- The confirmed purchase must match the commitment's frozen Buyer, selected commercial configurations and quantities, applicable Personalization and delivery intent, amount, currency, and other materially purchase-defining inputs while all current domain prerequisites are revalidated normally.
- A crash after successful local commit resolves to the existing consumed Order and Payment. When local commit outcome is unknown, local persistence is reconciled before compensation so an authorization already consumed by a committed purchase is never voided merely because the caller missed the response.
- Accepted authorization not consumed because local confirmation failed enters durable economically idempotent void or release workflow using one stable compensation identity and is never reused for a changed or later Order.
- Core Payment still cannot exist without Order. Pre-Order direct capture is unsupported in the current MVP, and no Checkout, Authorization, Payment Intent, Collection Intent, Compensation, or other pre-Order core entity is introduced.
- Payment uses the PENDING, AUTHORIZED, CAPTURED, FAILED, and CANCELLED lifecycle. Provider status is evidence rather than automatic domain authority, and duplicate economic events are recognized only once.
- For the positive-payable card MVP, full accepted capture of the confirmed payable total is required before fulfillment may start. A zero-payable Order is payment-ready without Payment, and payment readiness is not an Order or Order Item lifecycle state.
- Payment failure and timeout do not directly mutate Order Item state or ready-made stock. After a bounded resolution window, commerce workflow may cancel eligible Items, and successful applicable ready-made Item cancellation releases stock under existing rules.
- If any Order Item is cancelled before the first accepted Payment capture for a positive-payable Order, that immutable Order is permanently closed to further buyer payment collection in MVP; remaining desired selections require a new Order.
- Payment Allocation is an immutable accounting attribution or reversal of accepted captured buyer funds for one Payment and is not payout, settlement, Royalty, seller identity, or a profit ledger.
- Payment Allocation kinds are ORIGINAL and REVERSAL. A CAPTURED Payment and its complete ORIGINAL Allocation set are recognized atomically, every captured minor unit is attributed exactly once, and accepted refunds append REVERSAL facts without rewriting originals.
- Current ORIGINAL Allocation purposes are ITEM_PROCEEDS, MANUFACTURING_COMPENSATION, SHIPPING_CHARGE, and TAX. ITEM_PROCEEDS is merchant-side proceeds rather than automatically recognized platform profit, and no ROYALTY Allocation purpose exists in the current draft.
- Manufacturer compensation is attributed to the confirmation-time User or Organization Profile Holder context only when explicit accepted economic terms established the amount and beneficiary before Order confirmation. Manufacturer Profile assignment alone never creates compensation or makes the profile a payee.
- Confirmed aggregate discount is merchandise-level in MVP, never exceeds confirmed merchandise subtotal, and is attributed deterministically across authoritative Order Item line amounts. It reduces Royalty basis, does not reduce confirmed Manufacturer compensation, and is otherwise borne within Creastrix merchant economics.
- Payment Allocation beneficiary context may identify PLATFORM, USER, or ORGANIZATION and preserves immutable historical identity where applicable. It identifies the party associated with captured-funds attribution without proving that an amount has been earned, become due, become payout-eligible, or been transferred.
- Royalty is a separate captured-payment-triggered financial accrual domain. It does not change Payment Allocation conservation or imply that an amount has been earned, become payout-eligible, or been paid.
- Designer Profile enables publication eligibility but is not a Royalty beneficiary, payout account, or monetary identity.
- A positive MVP Royalty has exactly one USER or ORGANIZATION beneficiary established by validated royalty-rights context; Created By, Workspace ownership, Project business rights, Buyer, Manufacturer Profile, and Designer Profile do not determine it automatically.
- MVP Royalty uses a percentage rate in basis points against net item merchandise contribution after confirmed discount, with deterministic half-up rounding at the authoritative Order Item line level.
- Royalty calculation terms, basis, beneficiary context, currency, and original amount freeze in the Order Item snapshot at confirmation. Manufacturer compensation and Royalty remain independent, but their combined confirmed amounts cannot exceed net item merchandise contribution.
- A qualifying positive Royalty is recognized durably and idempotently after accepted full Payment capture. Temporary Royalty-processing failure does not change CAPTURED Payment state, and reconciliation ensures exactly-once business recognition.
- Accepted refunds create append-only Royalty reversal facts based on cumulative refunded royalty basis attributed to the Order Item; cancellation alone does not reverse Royalty.
- Royalty reversal recognition is durable, idempotent, and reconcilable. Accepted refund economics immediately make an affected Royalty payout-ineligible until recorded cumulative reversal equals the authoritative cumulative target derived from applicable Payment Allocation REVERSALS.
- Payout is one durable outbound transfer execution attempt for exactly one USER or ORGANIZATION beneficiary; it is not a balance, settlement ledger, accounting entry, or legal earning determination.
- Payout freezes one currency, one beneficiary context, one non-sensitive destination snapshot, and one or more embedded source portions. No Payout Profile, Payout Account, Payout Source, Payout Item, Balance, Hold, or Reserve entity exists in the current draft.
- Current Payout sources are an ORIGINAL MANUFACTURING_COMPENSATION Payment Allocation and Royalty. Sources from multiple Orders and both source types may be aggregated only for the same beneficiary and currency.
- Payout uses full-source portions only. A FULFILLED Order Item makes an associated source a release candidate, while current release or hold policy, compliance and provider capability, destination validity, positive outstanding amount, and absence of conflicting reservation or consumption determine payout eligibility.
- Payout release-policy evaluation is an independent mandatory prerequisite at prospective source selection and Payout creation and again before PENDING-to-PROCESSING submission. Every source requires an explicit successful result under one identified recognized, approved, versioned, and applicable policy basis; missing, ambiguous, unavailable, unknown, indeterminate, or negative evaluation fails closed and makes payout-available amount zero.
- If any selected source fails that policy gate or another prerequisite, the complete proposed aggregate Payout creation attempt fails atomically without portions or reservations. A later different source set is a new prospective selection attempt and undergoes complete validation again.
- Every source portion snapshots the exact release-policy basis and version under which it passed. A newer version alone does not invalidate an older snapshot that remains recognized, approved, and applicable; a revoked, unapproved, or inapplicable snapshot blocks submission without rewriting portions or switching policy versions.
- PENDING and PROCESSING Payouts reserve every included source amount. Creation of the PENDING Payout, its portions, and all source reservations is atomic; retries are new Payouts.
- Payout lifecycle is PENDING, PROCESSING, SUCCEEDED, FAILED, or CANCELLED. Policy and all other source gates are revalidated inside the local PENDING-to-PROCESSING commitment, no provider command is sent before that commitment succeeds, and later policy changes never rewrite PROCESSING or SUCCEEDED history. Ambiguous or partial provider outcomes remain PROCESSING with reservations active.
- Source reversal and PENDING Payout submission use one serialized ordering. PENDING to PROCESSING is a durable submission commitment that atomically revalidates all sources; if reversal commits first, the stale Payout is cancelled and cannot be submitted.
- Outbound Payout execution is economically idempotent: one Payout can cause at most one outbound economic transfer, and lost provider responses are reconciled through the same stable submission identity rather than an independent transfer.
- Reversal during PROCESSING or after SUCCEEDED never rewrites the attempt; derived recovery exposure may result.
- Recovery, provider-return treatment, and any balance domain remain future production policy and architecture work and must preserve immutable Payout history.
- A concrete approved, versioned Payout release policy is a hard prerequisite before any functional Payout implementation milestone. `PAYOUT_RELEASE_POLICY_V1` is only a working name for a future first candidate and is not yet defined or approved; its inputs, timing, and legal semantics require separate architecture, legal, compliance, finance, and product work.
- This fail-closed documentation hardening completes corrective change set B2 / F-03 without defining the concrete release policy or implementing Payout.
- The approved deterministic MVP Refund Allocation Selection Policy is PLATFORM_FIRST_WITH_ROYALTY_NO_SUBSIDY_SAFETY_FLOOR_V1. It translates immutable buyer-facing refund components into exact Payment Allocation REVERSALS independently from mutable Listing, Profile, Manufacturer Profile, or Workspace state.
- No economically unscoped accepted refund exists in MVP. Allowed embedded refund component types are ITEM_MERCHANDISE for one exact Order Item, Order-level SHIPPING, and Order-level TAX; their positive Payment-currency amounts sum exactly to the accepted refund amount.
- An accepted Payment refund preserves its immutable normalized component snapshot and policy version. The accepted fact, snapshot, and complete positive REVERSAL set are recognized atomically, and the REVERSALS sum exactly to both component total and accepted refund amount without creating a Refund or Refund Component entity.
- Ready-made ITEM_MERCHANDISE refund reverses only its Item's ITEM_PROCEEDS. Made-to-order refund follows cumulative platform-first targets while retaining enough ITEM_PROCEEDS to support authoritative remaining Royalty and reversing MANUFACTURING_COMPENSATION only when the safety floor requires it.
- After every cumulative accepted made-to-order Item refund prefix, Manufacturer compensation outstanding plus authoritative Royalty outstanding cannot exceed unreversed net item merchandise amount. The authoritative Royalty amount derives from immutable Order Item royalty terms and accepted refund economics rather than asynchronous Royalty entity recognition.
- Payment Allocation refund targets and Royalty reversal use compatible cumulative calculations and the same canonical accepted-refund order of immutable platform-accepted timestamp followed by refund-event identity, preventing path-dependent rounding across sequential refunds.
- Before the first provider refund command, a durable workflow or integration commitment freezes one stable submission identity, Payment and provider context, amount, currency, exact component instruction, and policy version. MVP permits at most one active or economically unknown refund submission per Payment, reserves its capacities, and reconciles the same provider operation rather than blindly retrying.
- Durable refund commitment and PENDING-to-PROCESSING Payout submission use one serialized ordering for sources the frozen instruction would economically reduce. Unrelated sources are not blocked; a Payout submission that commits first proceeds under existing PROCESSING and recovery semantics.
- TAX refund amount is explicit input from an approved authoritative tax or refund workflow. Core refund allocation does not calculate VAT or jurisdictional tax and never adds tax or shipping implicitly to ITEM_MERCHANDISE.
- Cancellation preserves all confirmed snapshots, and partial-quantity cancellation is unsupported in MVP.
- Later Listing, source, Personalization, Workspace access, Designer verification, Manufacturer Profile, or royalty-term changes never rewrite confirmed commerce.
- No Deleted Listing, source tombstone, Personalization tombstone, generic historical-source, or retention entity is introduced for C1; existing lifecycles, stable identities, and immutable Order Item snapshots are sufficient.
- Order owns one immutable confirmed delivery destination, and every Shipment of that Order uses it without an independent divergent destination.
- Shipment belongs to one Order and groups full-quantity Order Items from that Order. Partial-quantity shipment and Shipment Item are unsupported in MVP.
- Shipment preserves one immutable fulfillment-context snapshot established at creation: made-to-order context captures one Manufacturer Profile identity through Order Items, ready-made context contains an opaque platform-controlled context value, and the paths never mix or switch; the snapshot is an embedded domain value rather than a separate entity.
- Shipment lifecycle is PREPARING, SHIPPED, DELIVERED, UNDELIVERED, or CANCELLED. UNDELIVERED is a terminal state reachable only from SHIPPED after platform acceptance of sufficiently definitive evidence that the dispatched Shipment did not and will not reach the immutable Order destination.
- Delay, tracking silence, first failed attempt, unsupported Buyer report, or ambiguous provider outcome leaves Shipment SHIPPED. Provider evidence is input rather than automatic domain truth.
- UNDELIVERED preserves dispatch, frozen membership, fulfillment context, destination, and evidence history and does not automatically cancel an Order Item, accept a refund, release stock, or change financial history.
- Ordinary Order Item cancellation after SHIPPED remains forbidden. The only C2 exception permits a separately authorized terminal non-delivery resolution to move an IN_FULFILLMENT Item to CANCELLED after its frozen covering Shipment is UNDELIVERED and all applicable rules pass.
- Reshipment and replacement after dispatch are unsupported in MVP. UNDELIVERED remains non-CANCELLED, so its frozen Order Items cannot join a second non-CANCELLED Shipment.
- Ready-made post-dispatch terminal non-delivery cancellation never releases the original allocation or increases available quantity. Return-to-sender evidence is not automatic restock; receipt, inspection, restocking, and manual adjustment remain separate future work.
- Made-to-order terminal non-delivery cancellation preserves Manufacturer Profile assignment, acceptance, compensation basis, and all historical snapshots. A CANCELLED Item does not satisfy the current Payout FULFILLED release-candidate prerequisite.
- C2 introduces no Delivery Failure, Shipment Event, replacement, return, inventory, claim, or other core entity and no new Workspace scope.
- Shipment has no Workspace relationship or new Workspace scope and owns no independent destination, money, stock allocation, or manufacturing responsibility.
- Shipment operation follows fulfillment-context authorization, while provider statuses are evidence rather than automatic domain authority.
- Corrective documentation change sets A1, A2, B1, B2, C1, and C2 are complete. Executable implementation must now translate each domain invariant into an enforcement mechanism and adversarial executable test rather than treating another prose review as correctness proof.

## Product Rules

- Order and Order Item are different entities.
- One Order may contain multiple Order Items with different fulfillment paths.
- A made-to-order Order Item has exactly one assigned Manufacturer Profile.
- Ready-made fulfillment allocates existing stock and does not require a Manufacturer Profile merely because the Order Item is ready-made; Creastrix is the current MVP buyer-facing seller and merchant, while third-party seller and fulfillment semantics remain future work.
- Project, Revision, Listing, and Personalization are different concepts.
- Revision is created for product-defining changes, not text corrections.

## Product Direction

- The MVP must support ready-made physical products in addition to Project and Revision-based made-to-order products.
- Creastrix acts as the single buyer-facing seller-of-record and merchant-of-record for current MVP Orders.
- Third-party seller self-service may be introduced later.
- The architecture should remain extensible for a future seller marketplace without implementing that marketplace now.
- Creastrix-first selling is platform policy rather than an invariant of Ready-Made Product.

## Next Steps

1. Finalize and independently approve the Workspace and Workspace Membership architecture.
2. Implement the Workspace foundation from approved specifications.
3. Then proceed to the Ready-Made Product foundation.
4. Only afterward advance toward Listing and commerce slices in separately reviewed steps.
5. Before any functional Payout implementation milestone, separately define and approve the first concrete versioned Payout release policy.
