# Order Item

## Purpose

An Order Item represents one confirmed purchased line that preserves its immutable commercial configuration and progresses independently through fulfillment.

An Order Item is the immutable commercial and fulfillment-line boundary for the purchased configuration.

## Responsibilities

An Order Item is responsible for:

- representing a stable confirmed purchased-line identity;
- preserving its immutable Order and purchased Listing relationships;
- preserving an immutable purchase-time commercial and source snapshot;
- recording positive integer quantity, currency, unit merchandise price, and line merchandise amount;
- preserving its immutable confirmed discount share for captured-funds attribution;
- preserving its immutable confirmation-time publication context when Revision-based;
- preserving its immutable confirmation-time royalty configuration, calculation, beneficiary, and rights context when Revision-based;
- preserving immutable confirmation-time accepted royalty-rights validation context for the exact purchased royalty configuration when Revision-based;
- preserving optional Personalization traceability and the authoritative purchased Personalization snapshot;
- establishing confirmed Ready-Made Product stock allocation when applicable;
- preserving exactly one assigned Manufacturer Profile for made-to-order fulfillment;
- preserving an immutable, authoritative confirmation-time Manufacturer acceptance fact for made-to-order fulfillment;
- preserving the confirmed manufacturer compensation and beneficiary basis for made-to-order Payment Allocation;
- managing its source-neutral fulfillment and cancellation lifecycle;
- governing path-specific authorization for formally starting fulfillment;
- providing a stable boundary for Shipment, Payment Allocation, and Royalty integration.

## Relationships

An Order Item:

- belongs to exactly one Order;
- references exactly one immutable purchased Listing;
- preserves the Publication Designer Profile identity and historical publication context inside its immutable snapshot when Revision-based, without creating a second authoritative live Designer Profile relationship;
- may reference zero or one Personalization for traceability;
- has exactly one assigned Manufacturer Profile when its purchased source is a FINALIZED Revision;
- has no assigned Manufacturer Profile merely for ordinary Ready-Made Product fulfillment;
- may be a current or frozen member of zero or more Shipments over time, subject to at most one non-CANCELLED Shipment at a time;
- may be the charge subject of zero or more Payment Allocations through captured Payments of its Order;
- has zero or one Royalty when it is Revision-based, has a positive calculated royalty amount, and the applicable Payment capture is accepted;
- has zero or one Designer Review when it qualifies under Designer Review rules;
- has no direct Workspace relationship.

## Business Rules

- An Order Item is created atomically with its Order only when Order confirmation succeeds.
- An Order Item cannot be added to, removed from, or moved between Orders after confirmation.
- Every Order Item references exactly one purchased Listing, and that Listing relationship cannot change after confirmation.
- The purchased Listing remains a traceability reference, while the immutable Order Item snapshot is the authoritative historical record of the purchase.
- An Order Item preserves exactly one immutable source identity and source type snapshot derived from its purchased Listing at confirmation. The source type is either one FINALIZED Revision or one Ready-Made Product.
- The source type determines the MVP fulfillment path: a FINALIZED Revision uses made-to-order fulfillment, while a Ready-Made Product uses existing-stock ready-made fulfillment.
- No separate mutable fulfillment path is stored merely to duplicate the immutable source type.
- The purchase-time snapshot preserves the Listing identity, source identity and type, source Workspace identity, Workspace owner identity and type, applicable Project business-rights or Ready-Made commercial context, and purchased commercial or public presentation required for history.
- The purchase-time commercial context does not identify the Workspace owner automatically as seller, merchant, tax merchant, or payout recipient.
- Every Revision-based Order Item preserves an immutable publication-context snapshot derived from its purchased Listing at confirmation.
- The Revision-based publication snapshot preserves the immutable Publication Designer Profile identity, Profile Holder type and identity, the valid non-empty public display/studio name copied from that profile's current publication identity at confirmation, the confirmation-time facts that required profile publication eligibility and design-specific publication-rights validation passed, and sufficient rights-context or source basis for historical traceability.
- Order confirmation for a Revision-based Item requires the purchased Listing's immutable Publication Designer Profile to remain VERIFIED, the applicable current design-specific publication rights to remain valid, and currently applicable accepted royalty-rights validation to match the Listing's exact current royalty validation subject.
- The publication-context snapshot does not copy the full Designer Profile, bio, portfolio, media, or mutable current status. It does not establish authorship, intellectual-property ownership, Project business rights, Royalty beneficiary identity, payout identity, seller identity, or manufacturing authority.
- The purchased Listing remains the authoritative entity relationship through which the Publication Designer Profile was selected. Preserving its identity in the Order Item snapshot provides historical autonomy without creating another mutable ownership or publication relationship.
- A Ready-Made Product Order Item has no Designer Profile publication context merely because its source is ready-made.
- The current public display/studio name of a Designer Profile may change prospectively, but a later rename never changes the confirmation-time name in an immutable Order Item publication-context snapshot.
- Later Designer Profile status, other presentation, Profile Holder authority, or design-specific publication-rights changes never rewrite the immutable publication-context snapshot.
- Only a Revision-based Order Item that has reached FULFILLED may qualify for a Designer Review. CONFIRMED, IN_FULFILLMENT, CANCELLED, and Ready-Made Product Order Items do not qualify in the current MVP.
- When a Designer Review exists, its immutable Reviewer User must equal the Buyer User of this Order Item's Order, and its immutable target Designer Profile must equal the Publication Designer Profile identity preserved by this Order Item's publication-context snapshot.
- One Order Item may have at most one Designer Review even when its quantity is greater than one. A WITHDRAWN Review continues to occupy that one-review slot.
- The direct Designer Review relationship provides purchase-backed eligibility and traceability but never grants public access to the Order Item, its Order, or private Personalization data.
- Once a Revision-based Order Item legitimately reaches FULFILLED, a later partial or full refund does not remove its Designer Review eligibility or rewrite an existing Review's history.
- Designer Review content, lifecycle, moderation, visibility, and mutation authority remain responsibilities of Designer Review rather than Order Item.
- Order Item quantity must be a positive integer.
- Quantity greater than one represents multiple units of the same confirmed purchased configuration. Different Personalizations or other purchased configurations require separate Order Items.
- Every Order Item uses the currency of its Order.
- At confirmation, the Order Item currency must equal the currency of its purchased Listing at that time and therefore must match the Order currency. Later Listing changes never rewrite the confirmed Order Item currency snapshot.
- Unit merchandise price and authoritative line merchandise amount are fixed at confirmation and never change afterward.
- Personalization and manufacturing pricing effects must already be reflected in the confirmed merchandise amounts when applicable.
- Line merchandise amount is snapshotted independently and is not permanently constrained by an invariant requiring it to equal unit merchandise price multiplied by quantity.
- Tax, shipping, coupons, order-level discounts, and payment fees are not modeled as Order Item merchandise amounts in this specification.
- The confirmed aggregate Order discount is attributed deterministically across Order Items for Payment Allocation using authoritative line merchandise amounts as the proportional basis and largest-remainder distribution in currency minor units. Equal fractional remainders are resolved by canonical ascending immutable Order Item identity.
- Each Order Item preserves its immutable confirmed discount share for allocation calculation and history. The share reduces net item merchandise contribution but never rewrites unit merchandise price or line merchandise amount.
- Every confirmed item discount share is non-negative and does not exceed the authoritative line merchandise amount. The complete sum of confirmed item discount shares equals the Order's aggregate confirmed discount total.
- Net item merchandise contribution equals authoritative line merchandise amount minus the confirmed item discount share, is always non-negative, and is never recomputed from unit merchandise price multiplied by quantity.
- Every Revision-based Order Item preserves an explicit immutable royalty decision and calculation context from its purchased Listing at confirmation.
- The MVP royalty method is PERCENTAGE, represented by an integer rate from zero through 10,000 basis points inclusive. The calculation basis is NET_ITEM_MERCHANDISE_CONTRIBUTION_V1, and the rounding rule is HALF_UP_MINOR_UNIT_V1.
- Royalty basis amount equals authoritative line merchandise amount minus confirmed item discount share and therefore equals net item merchandise contribution. Buyer-facing merchandise discount reduces the royalty basis in MVP.
- Calculated original royalty amount is determined once at the authoritative line level by applying the rate basis points to royalty basis minor units, dividing by 10,000, and rounding half up to the currency minor unit under HALF_UP_MINOR_UNIT_V1.
- Royalty is never calculated per unit or recomputed from unit merchandise price multiplied by quantity. Quantity is already represented in the authoritative line merchandise amount.
- The Revision-based royalty snapshot preserves method, rate basis points, calculation basis identifier and version, authoritative royalty basis amount, rounding rule version, calculated original royalty amount, currency, beneficiary presence or absence, beneficiary context when present, royalty-right source or basis, and existing Listing and Revision traceability.
- Before confirming a Revision-based Order Item, the platform revalidates that the purchased Listing has currently applicable accepted royalty-rights validation whose exact validated subject matches the current royalty configuration used for purchase.
- Order confirmation fails and no Order Item is created when that accepted validation is absent, stale, mismatched, or no longer applicable. This check is additional to Designer Profile eligibility, design-specific publication rights, pricing, Manufacturer acceptance, and every other applicable confirmation requirement.
- Every confirmed Revision-based Order Item preserves an immutable confirmation-time accepted royalty-rights validation snapshot containing the accepted validation or decision identity, acceptance timestamp, validation policy or rules version, the exact validated subject or sufficient immutable representation of it, correspondence to the positive-rate beneficiary or explicit zero-rate decision, sufficient royalty-rights source or basis context for historical traceability, and the confirmation-time fact that the accepted validation matched the purchased Listing's current royalty configuration.
- The accepted validation snapshot is evidence and context that the exact purchased royalty terms passed platform royalty-rights validation at confirmation. The existing Order Item royalty snapshot remains the sole authoritative source for rate, basis, rounding, calculated original Royalty amount, beneficiary, and rights basis.
- Royalty amounts are never recalculated from accepted validation context, and the validation snapshot does not create a second source of monetary calculation truth.
- A Ready-Made Product Order Item has no royalty-rights validation snapshot merely because of its source type.
- Full external legal or evidence documents are not duplicated in Order Item, and the embedded accepted validation snapshot does not introduce a validation or rights entity.
- A positive royalty rate requires exactly one confirmed beneficiary of type USER or ORGANIZATION, the applicable live User or Organization reference, an immutable historical beneficiary identity snapshot, and the source or basis of the royalty right.
- A zero royalty rate may omit a monetary beneficiary only when applicable Listing and business rules explicitly permit that zero-royalty configuration. The explicit zero-royalty decision and calculation context remain preserved.
- A positive rate whose calculated original royalty amount rounds to zero still preserves its required beneficiary and complete calculation context, but it does not create a zero-value Royalty after capture.
- Royalty currency always equals Order Item and Order currency. Percentage royalty terms have no independent currency, and foreign exchange inside Royalty is unsupported in MVP.
- Confirmation requires the Listing and source to permit purchase, fixed quantity and merchandise amounts, the applicable commercial and royalty context, and all source-specific prerequisites.
- Personalization is optional and may be used only for an applicable FINALIZED Revision-sourced Order Item in MVP.
- When Personalization is used, it must belong to the Buyer User of the Order, use the applicable FINALIZED Revision base, and pass current required validation before confirmation.
- A Personalization reference is for traceability. The authoritative purchased Personalization snapshot preserves the Personalization identity where applicable, FINALIZED Revision identity, selected buyer values, reproducible generated output required for manufacturing, and relevant validation context.
- Later Personalization edits or deletion never change the purchased Personalization snapshot.
- A made-to-order Order Item has exactly one assigned Manufacturer Profile at confirmation.
- The assigned Manufacturer Profile must be VERIFIED at confirmation, item-specific manufacturing eligibility must succeed, and required Manufacturer acceptance must already have occurred.
- VERIFIED status alone does not establish item-specific capability, available capacity, pricing, acceptance, lead time, or product compliance.
- Manufacturer acceptance is a pre-confirmation prerequisite rather than Manufacturer Profile state.
- Every confirmed made-to-order Order Item preserves an immutable, authoritative confirmation-time business fact that the required Manufacturer acceptance was obtained.
- Every confirmed made-to-order Order Item preserves sufficient immutable confirmed Manufacturer compensation basis and terms for future captured-funds attribution, including the compensation amount, beneficiary type, applicable live User or Organization reference, immutable beneficiary identity snapshot, and source or basis of the confirmed commercial terms.
- The Manufacturer compensation beneficiary is the confirmation-time User or Organization Profile Holder context of the assigned Manufacturer Profile. The Manufacturer Profile itself is never the beneficiary or payment recipient.
- Manufacturer Profile assignment alone does not create a Manufacturer compensation right. The compensation amount and beneficiary context must have been explicitly established in accepted commercial and manufacturing terms before Order confirmation.
- The Order Item compensation basis does not by itself prove that Manufacturer compensation has been earned, become due or payable, become eligible for withdrawal or payout, or been transferred. A MANUFACTURING_COMPENSATION Payment Allocation at capture records captured-funds attribution toward that basis and does not prove those later conditions either.
- Buyer-facing Order discount reduces the royalty basis under the current MVP rule, does not reduce an already confirmed Manufacturer compensation amount, and is otherwise borne within Creastrix merchant economics.
- Order confirmation must reject a made-to-order economic configuration when the sum of explicit confirmed Manufacturer compensation and confirmed calculated original royalty amount exceeds net item merchandise contribution. Equivalently, the calculated Royalty amount cannot exceed the ITEM_PROCEEDS amount that would remain after Manufacturer compensation. No subsidy or external funding model exists in MVP.
- The Manufacturer acceptance fact belongs to the Order Item confirmation history and snapshot and remains authoritative even if no Audit Log record exists.
- A future Audit Log may record acting User, exact timestamp, evidence source, workflow provenance, or detailed acceptance evidence, but it does not replace the Order Item as the authoritative source of truth that Manufacturer acceptance occurred.
- Later changes to Manufacturer Profile eligibility status, Organization Membership, acting User status, future Audit Log behavior, or Profile Holder permissions never rewrite the historical confirmed Manufacturer acceptance fact.
- Manufacturer Profile assignment is immutable after confirmation. Later UNVERIFIED or SUSPENDED status does not rewrite the assignment or automatically reassign or cancel the Order Item.
- Later changes to the Manufacturer Profile, Profile Holder, User, Organization, or commercial rules do not rewrite the confirmed Manufacturer compensation or beneficiary basis.
- If an assigned Manufacturer later fails, the Order Item may be cancelled when applicable rules permit. Replacement requires a future separate purchasing workflow rather than mutation of the confirmed assignment or Order Item collection.
- A ready-made Order Item has no assigned Manufacturer Profile merely because of ordinary existing-stock fulfillment.
- Ready-made confirmation establishes a confirmed allocation of the full Order Item quantity against its Ready-Made Product.
- Ready-made allocation succeeds only when the full quantity is currently available. Allocation is atomic at the domain level, available quantity never becomes negative, and the same stock capacity cannot be confirmed more than once.
- A confirmed ready-made allocation remains associated with the Order Item until fulfillment or consumption, or until an applicable release.
- Successful cancellation may release a ready-made allocation when it is no longer required.
- Ready-Made Product stock allocation remains an Order Item responsibility. Shipment does not reserve or allocate stock, change ordered quantity, or create Inventory state.
- An Order Item may be added to a Shipment only after the Item is in the IN_FULFILLMENT state.
- Shipment creation does not transition an Order Item from CONFIRMED to IN_FULFILLMENT.
- A Shipment always covers the full quantity of an included Order Item. Partial-quantity shipment is not supported in MVP.
- An Order Item may be covered by at most one non-CANCELLED Shipment in MVP.
- An Order Item has the lifecycle state CONFIRMED, IN_FULFILLMENT, FULFILLED, or CANCELLED.
- CONFIRMED means the immutable commercial snapshot exists and fulfillment has not yet begun.
- IN_FULFILLMENT means the applicable source-specific fulfillment obligations are being performed.
- FULFILLED means all applicable fulfillment obligations for the Order Item are complete.
- Transition from CONFIRMED to IN_FULFILLMENT means that applicable fulfillment execution has formally started. It requires the Order to satisfy its current payment-readiness rule in addition to all path-specific authorization and fulfillment prerequisites, but it is not caused automatically by Payment state, Shipment creation, Listing lifecycle, or source lifecycle.
- For a made-to-order Order Item, transition from CONFIRMED to IN_FULFILLMENT requires authorization within the Item's immutable assigned Manufacturer Profile context.
- Made-to-order fulfillment may be started by an authorized User acting through that Manufacturer Profile holder context or by explicitly authorized platform automation or an internal platform process acting within the same context.
- For a User-held Manufacturer Profile, the holder User provides the holder context. For an Organization-held Manufacturer Profile, current User authorization resolves through an ACTIVE Organization Membership with the role OWNER unless a future explicit delegation rule authorizes another actor.
- For a ready-made Order Item, transition from CONFIRMED to IN_FULFILLMENT uses explicitly authorized internal platform fulfillment through platform automation, an internal platform process, or an internal authorized User workflow.
- Ready-made fulfillment-start authority is not inferred from the source Workspace owner, Workspace Membership, the READY_MADE_PRODUCTS, LISTINGS, or PROJECTS scopes, or Buyer status. Third-party ready-made fulfillment remains future work.
- For the current physical-delivery MVP, a non-CANCELLED Shipment covering the full Order Item quantity must reach DELIVERED before the Item may transition to FULFILLED, and all other applicable fulfillment obligations must also be complete.
- Shipment DELIVERED is delivery evidence and is not a universal permanent synonym for Order Item FULFILLED.
- CANCELLED means the item will not be fulfilled and its historical commercial snapshot remains preserved.
- The allowed normal fulfillment transitions are CONFIRMED to IN_FULFILLMENT and IN_FULFILLMENT to FULFILLED.
- A CONFIRMED Order Item may transition to CANCELLED. An IN_FULFILLMENT Order Item may transition to CANCELLED before physical dispatch only when applicable cancellation and production rules permit it.
- Once the non-CANCELLED Shipment covering an Order Item is SHIPPED, ordinary MVP Order Item cancellation is not permitted. Returns, delivery failure, and other post-dispatch resolution remain future work.
- FULFILLED and CANCELLED are terminal states.
- Cancellation applies to the complete Order Item in MVP. Partial-quantity cancellation is not supported.
- A FULFILLED Order Item cannot be cancelled.
- Cancellation preserves the Order Item identity, relationships, merchandise amounts, commercial and source snapshot, Manufacturer Profile assignment, Personalization snapshot, and royalty context.
- Payment failure or timeout does not directly mutate Order Item state. After a bounded payment-resolution window, commerce workflow may attempt cancellation when the existing Item cancellation rules permit it.
- Cancellation of any Order Item in a positive-payable Order before that Order's first accepted Payment capture permanently closes the Order to further buyer payment collection under current MVP Order and Payment rules. The cancellation itself does not directly mutate Payment state, the immutable Order Item collection, or the confirmed Order payable snapshot.
- Payment refund is separate from Order Item cancellation. Cancellation before capture may require no refund, cancellation after capture may create a refund obligation, and an accepted refund does not itself change Order Item lifecycle.
- Partial refund does not create partial Item cancellation, and neither cancellation nor refund rewrites original merchandise amounts, confirmed discount share, Manufacturer compensation basis, or the Order payable snapshot.
- ORIGINAL Payment Allocations may use the Order Item as their charge subject only after capture of a Payment of the Order is accepted. Payment Allocation does not become an Order Item lifecycle state.
- An Order Item preserves the applicable Listing royalty configuration, calculated amount, beneficiary and rights context at confirmation. It is the authoritative Royalty calculation snapshot but is not the accrued Royalty ledger.
- Accepted full Payment capture is the mandatory idempotent trigger for recognizing exactly one Royalty for a qualifying Revision-based Order Item whose calculated original royalty amount is positive. Royalty recognition is separately durable and reconcilable and does not redefine the atomic Payment CAPTURED and ORIGINAL Payment Allocation boundary.
- A temporary Royalty-processing failure after capture does not change Payment state. Reconciliation must eventually recognize the missing Royalty exactly once.
- Royalty original amount is copied from the immutable Order Item snapshot and is never recalculated from current Listing or other mutable domain state.
- Later Listing royalty-configuration changes, replacement or renewed validation, validation revocation or expiry, beneficiary account-status changes, Designer Profile changes, or Workspace changes never rewrite the confirmation-time accepted validation snapshot, the authoritative royalty snapshot, an existing Royalty, or Royalty reversal history.
- Designer Profile publication eligibility is not revalidated at Royalty recognition. Later Designer Profile verification or status changes do not rewrite the confirmed royalty snapshot or prevent recognition after qualifying capture.
- A ready-made Order Item preserves that no designer royalty applies and never creates a Royalty in the current MVP.
- Order Item cancellation alone does not reverse Royalty. Accepted refund economics may create append-only Royalty reversals according to refunded royalty basis attributable to the Item.
- Later Listing, Project, Revision context, Ready-Made Product, Personalization, Workspace access, Designer verification, Manufacturer Profile, beneficiary, or royalty-configuration changes never rewrite the immutable confirmed commercial snapshot.

## Invariants

- An Order Item always has one stable identity.
- An Order Item always belongs to exactly one Order.
- Its Order membership never changes after confirmation.
- An Order Item always references exactly one immutable purchased Listing.
- An Order Item always preserves exactly one immutable source identity and source type snapshot.
- The source type is always either exactly one FINALIZED Revision or exactly one Ready-Made Product.
- Quantity is always a positive integer.
- Order Item currency always matches its Order currency.
- The confirmed Order Item currency is always the purchased Listing currency captured at confirmation.
- Unit merchandise price and line merchandise amount never change after confirmation.
- The confirmed discount share is always non-negative, never exceeds the authoritative line merchandise amount, never changes after confirmation, and never rewrites the line merchandise amount.
- The sum of all confirmed item discount shares in an Order always equals that Order's aggregate confirmed discount total.
- Net item merchandise contribution is always non-negative.
- Every Revision-based Order Item always preserves exactly one immutable royalty decision and calculation context using PERCENTAGE, NET_ITEM_MERCHANDISE_CONTRIBUTION_V1, and HALF_UP_MINOR_UNIT_V1.
- Every Revision-based Order Item always preserves exactly one immutable accepted royalty-rights validation snapshot that matched the purchased Listing's exact royalty validation subject at confirmation.
- A Ready-Made Product Order Item never has a royalty-rights validation snapshot merely because of its source type.
- An accepted royalty-rights validation snapshot never changes after Order confirmation and never replaces the authoritative monetary royalty snapshot.
- Every Revision-based Order Item always preserves exactly one immutable publication-context snapshot identifying the Publication Designer Profile and its confirmation-time Holder and validation context.
- Every Revision-based Order Item publication-context snapshot always preserves the valid non-empty public display/studio name used by its Publication Designer Profile at confirmation.
- A Ready-Made Product Order Item never has a Designer Profile publication-context snapshot merely because of its source type.
- A Revision-based royalty rate is always an integer from zero through 10,000 basis points inclusive.
- Royalty basis amount always equals net item merchandise contribution, and calculated original royalty amount always equals the HALF_UP_MINOR_UNIT_V1 line-level result of applying the immutable rate to that basis.
- A positive royalty rate always preserves exactly one immutable USER or ORGANIZATION beneficiary context. An explicitly permitted zero rate may preserve no monetary beneficiary.
- Confirmed Manufacturer compensation plus calculated original royalty amount never exceeds net item merchandise contribution.
- A Revision-based Order Item never has more than one Royalty in MVP, and a Ready-Made Product Order Item never has a Royalty in the current MVP.
- An Order Item never has more than one Designer Review in MVP.
- Whenever an Order Item has a Designer Review, the Item is Revision-based, has reached FULFILLED, and its immutable publication-context Designer Profile identity equals the Review target.
- A Ready-Made Product Order Item never has a Designer Review in the current MVP.
- The purchase-time commercial, source, Workspace, and royalty context never changes after confirmation.
- When Personalization is used, the authoritative purchased Personalization snapshot never changes after confirmation.
- A made-to-order Order Item always has exactly one assigned Manufacturer Profile.
- A confirmed made-to-order Order Item always preserves an immutable, authoritative confirmation-time fact that required Manufacturer acceptance was obtained.
- A confirmed made-to-order Order Item always preserves its explicitly established Manufacturer compensation and User or Organization beneficiary basis.
- An ordinary ready-made Order Item never has an assigned Manufacturer Profile merely for stock fulfillment.
- Manufacturer Profile assignment never changes after confirmation.
- The historical confirmed Manufacturer acceptance fact never changes after confirmation.
- An Order Item included in a Shipment is always covered in its full quantity.
- An Order Item is never covered by more than one non-CANCELLED Shipment in MVP.
- An Order Item always has exactly one lifecycle state: CONFIRMED, IN_FULFILLMENT, FULFILLED, or CANCELLED.
- Every transition from CONFIRMED to IN_FULFILLMENT satisfies the applicable path-specific authorization rules.
- Every transition from CONFIRMED to IN_FULFILLMENT satisfies the Order's applicable payment-readiness rule.
- FULFILLED and CANCELLED are terminal states.
- Cancellation of an Order Item in a positive-payable Order before that Order's first accepted Payment capture always closes the Order to further buyer payment collection in MVP.
- Later source, profile, access, or commercial changes never rewrite the immutable confirmed snapshot.

## Notes

The immutable source snapshot does not create a second authoritative direct source relationship. The purchased Listing remains the entity relationship, and the snapshot provides historical autonomy from current Listing and source state.

Order Item does not own Inventory, and no Inventory or Reservation entity is introduced in MVP. Temporary reservation, returns, restocking, and technical locking remain future integration concerns. Payment-resolution failure may lead to Order Item cancellation, but Payment never directly releases ready-made stock allocation.

Shipment provides delivery evidence toward the FULFILLED transition, while Shipment lifecycle states are not duplicated in the Order Item lifecycle. Manufacturing, Payment, Payment Allocation, and refund substates also remain outside the Order Item lifecycle.

Royalty accrual and append-only reversal history belong to Royalty. Royalty recognition after accepted capture does not mean the amount has been earned, become payable or payout-eligible, or been transferred. Earning, release, and Payout rules remain future work. Payment Allocation explains captured funds without becoming the Royalty ledger or being rewritten by Royalty.

Accepted royalty-rights validation context is an embedded immutable part of the Revision-based Order Item confirmation snapshot rather than a relationship to another entity. It preserves the accepted decision needed for historical traceability without copying full legal evidence or becoming a calculation source.

Designer Profile publication context is separate from Royalty beneficiary context, Project business rights, authorship, intellectual-property ownership, seller identity, and Manufacturer Profile capability. Designer Review identifies its target publication profile and Reviewer eligibility from the immutable Order and Order Item context while keeping Review content, lifecycle, moderation, visibility, and authority outside Order Item.

The Order preserves the current Creastrix seller-of-record and merchant-of-record context and its final payable snapshot. Detailed tax, payment-fee, refund-policy, and replacement-commerce rules remain future domain concerns.

---

Status: DRAFT

Version: 0.8
