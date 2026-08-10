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
- Listing
- Personalization
- Manufacturer Profile
- Order
- Order Item
- Payment
- Payment Allocation
- Royalty
- Shipment

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
- An ACTIVE Organization Membership with the role OWNER is the current source of general organization-level authority when no more specific delegation rule exists.
- Organization does not by itself determine seller-of-record, merchant identity, economic beneficiary, payment recipient, or payout identity. Creastrix is the single buyer-facing seller-of-record and merchant-of-record for current MVP Orders.
- Workspace belongs to exactly one User or Organization.
- Workspace remains a common operational and access boundary and is not limited to design work.
- Workspace ownership and Workspace access are separate concepts.
- In a User-owned Workspace, the User owner remains an ACTIVE ADMIN in MVP and cannot lose administrative access through normal Workspace membership changes.
- Workspace Membership authorization combines membership status, Workspace role, the relevant permission scope, and rules of the requested domain operation.
- An ACTIVE ADMIN has full Workspace access in MVP, while EDITOR and VIEWER operate only within explicitly granted scopes.
- PROJECTS is the Workspace permission scope for Project and Revision work.
- READY_MADE_PRODUCTS is the Workspace permission scope for Ready-Made Product management, including simple MVP available quantity.
- LISTINGS is the Workspace permission scope for Listing commercial management.
- PROJECTS, READY_MADE_PRODUCTS, and LISTINGS are independent scopes and do not grant access to one another.
- Future domain areas may introduce additional permission scopes without automatically expanding existing EDITOR or VIEWER access.
- Organization Membership does not automatically grant Workspace access or Workspace permission scopes.
- Every Organization-owned Workspace retains at least one User who is both an ACTIVE Organization OWNER and an ACTIVE Workspace ADMIN.
- Scoped Workspace permissions never grant ownership or business rights.
- Project belongs to exactly one Workspace.
- Project has no separate Business Owner in MVP.
- Project Effective Business Rights Holder derives from the Workspace owner.
- Moving a Project requires PROJECTS write authorization in both source and target Workspaces; DRAFT or PAUSED Listings additionally require LISTINGS write authorization in both.
- Ready-Made Product is the stable identity of one independently stocked physical product configuration and belongs to exactly one Workspace.
- The Workspace owner provides the platform-recognized commercial context in which a Ready-Made Product is managed; this does not prove legal ownership, physical custody, seller-of-record, manufacturer, or supplier status.
- Ready-Made Product has the ACTIVE and ARCHIVED lifecycle and may transition in either direction.
- Ready-Made Product uses simple non-negative available quantity in MVP; an allocation may be confirmed only when sufficient quantity is available at confirmation, and the same available stock capacity cannot be confirmed for more than one Order Item. Lifecycle remains independent from stock availability.
- One independently stocked physical configuration is one Ready-Made Product in MVP; no Product Variant entity exists.
- Ready-Made Product exists independently from Listing and is never published directly.
- Ready-Made Product does not require a Manufacturer Profile.
- Project is the stable identity of a manufacturable product concept and may have multiple Revisions.
- Revision itself carries the DRAFT and FINALIZED lifecycle; no separate Project Draft entity exists.
- Multiple DRAFT Revisions may be developed in parallel.
- A FINALIZED Revision has immutable product-defining content.
- Designer Profile is the stable public professional design identity and platform-verified publication capability of exactly one User or Organization.
- A Designer Profile has exactly one immutable Profile Holder of type USER or ORGANIZATION, never both. A User may hold no more than one personal Designer Profile, and an Organization may hold no more than one Designer Profile in MVP.
- Designer Profile Created By is immutable record-creation provenance and does not determine the Profile Holder, authorship, intellectual-property ownership, publication rights, Royalty beneficiary, payout identity, or permanent management authority.
- Designer Profile eligibility status is UNVERIFIED, VERIFIED, or SUSPENDED. Profile-level verification is separate from design-specific publication-rights validation.
- Every Revision-based Listing selects exactly one immutable Publication Designer Profile at creation and preserves sufficient current design-specific publication-rights context or basis. A Ready-Made Product Listing has no Publication Designer Profile.
- Creating or publishing a Revision-based Listing requires effective LISTINGS authorization in its source-derived Workspace and independent authority to act through the selected Designer Profile. Activation, reactivation, and effective orderability additionally require current profile and design-specific rights eligibility.
- Designer Profile has no mandatory Workspace relationship and is independent from Project ownership, Project business rights, legal authorship, intellectual-property ownership, Manufacturer Profile capability, and Royalty beneficiary identity.
- Loss of Designer Profile or design-specific rights eligibility leaves Listing lifecycle unchanged but makes an ACTIVE Revision-based Listing non-orderable while the loss continues.
- A Revision-based Order Item preserves an immutable historical publication-context snapshot without creating a second authoritative live Designer Profile relationship.
- Listing represents the commercial and public offer of exactly one immutable commercial source: a FINALIZED Revision or a Ready-Made Product, never both.
- Listing Workspace context is derived from its commercial source, and source write permission is not required merely to manage Listing commercial data.
- LISTINGS authorization and business eligibility are separate requirements.
- A source may have multiple Listings over time but no more than one ACTIVE Listing at the same time in MVP.
- Listing has the DRAFT, ACTIVE, PAUSED, and ARCHIVED lifecycle; ACTIVE does not guarantee effective orderability.
- Source archive or loss of required business eligibility makes an existing Listing non-orderable without changing Listing lifecycle automatically.
- A Project cannot move between Workspaces while a Listing targeting any of its Revisions is ACTIVE.
- A Ready-Made Product Listing uses a fixed unit sale price, while a Revision-based Listing may use base or display pricing before confirmed Order Item merchandise amounts are determined.
- Every Listing uses one currency in MVP.
- A Revision-based Listing requires explicit applicable royalty terms before activation; a Ready-Made Product Listing does not create designer royalty automatically.
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
- External pre-Order provider authorization remains checkout and provider workflow. Core Payment never exists without Order; successful pre-authorization is recorded as an AUTHORIZED Payment in the same local confirmation transaction as the Order, while failed confirmation requires durable void or release compensation without creating Order or Payment.
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
- Payout is a separate PLANNED domain for future transfers, KYC, reserves, aggregation, and payout failure or retry behavior.
- Cancellation preserves all confirmed snapshots, and partial-quantity cancellation is unsupported in MVP.
- Later Listing, source, Personalization, Workspace access, Designer verification, Manufacturer Profile, or royalty-term changes never rewrite confirmed commerce.
- Order owns one immutable confirmed delivery destination, and every Shipment of that Order uses it without an independent divergent destination.
- Shipment belongs to one Order and groups full-quantity Order Items from that Order. Partial-quantity shipment and Shipment Item are unsupported in MVP.
- Shipment preserves one immutable fulfillment-context snapshot established at creation: made-to-order context captures one Manufacturer Profile identity through Order Items, ready-made context contains an opaque platform-controlled context value, and the paths never mix or switch; the snapshot is an embedded domain value rather than a separate entity.
- Shipment lifecycle is PREPARING, SHIPPED, DELIVERED, or CANCELLED, and accepted delivery evidence contributes to Order Item fulfillment.
- Shipment has no Workspace relationship or new Workspace scope and owns no independent destination, money, stock allocation, or manufacturing responsibility.
- Shipment operation follows fulfillment-context authorization, while provider statuses are evidence rather than automatic domain authority.

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

1. Review and finalize the Designer Profile draft.
2. Review and model Payout when beneficiary transfer requirements are modeled.
3. Continue with Reviews, Notifications, Conversations, and Audit Log as dependencies become clear.
