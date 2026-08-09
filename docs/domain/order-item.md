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
- preserving optional Personalization traceability and the authoritative purchased Personalization snapshot;
- establishing confirmed Ready-Made Product stock allocation when applicable;
- preserving exactly one assigned Manufacturer Profile for made-to-order fulfillment;
- preserving an immutable, authoritative confirmation-time Manufacturer acceptance fact for made-to-order fulfillment;
- managing its source-neutral fulfillment and cancellation lifecycle;
- governing path-specific authorization for formally starting fulfillment;
- providing a stable boundary for Shipment and future Payment Allocation and Royalty integration.

## Relationships

An Order Item:

- belongs to exactly one Order;
- references exactly one immutable purchased Listing;
- may reference zero or one Personalization for traceability;
- has exactly one assigned Manufacturer Profile when its purchased source is a FINALIZED Revision;
- has no assigned Manufacturer Profile merely for ordinary Ready-Made Product fulfillment;
- may be a current or frozen member of zero or more Shipments over time, subject to at most one non-CANCELLED Shipment at a time;
- may later participate in Payment Allocation and Royalty records;
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
- Order Item quantity must be a positive integer.
- Quantity greater than one represents multiple units of the same confirmed purchased configuration. Different Personalizations or other purchased configurations require separate Order Items.
- Every Order Item uses the currency of its Order.
- At confirmation, the Order Item currency must equal the currency of its purchased Listing at that time and therefore must match the Order currency. Later Listing changes never rewrite the confirmed Order Item currency snapshot.
- Unit merchandise price and authoritative line merchandise amount are fixed at confirmation and never change afterward.
- Personalization and manufacturing pricing effects must already be reflected in the confirmed merchandise amounts when applicable.
- Line merchandise amount is snapshotted independently and is not permanently constrained by an invariant requiring it to equal unit merchandise price multiplied by quantity.
- Tax, shipping, coupons, order-level discounts, and payment fees are not modeled as Order Item merchandise amounts in this specification.
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
- The Manufacturer acceptance fact belongs to the Order Item confirmation history and snapshot and remains authoritative even if no Audit Log record exists.
- A future Audit Log may record acting User, exact timestamp, evidence source, workflow provenance, or detailed acceptance evidence, but it does not replace the Order Item as the authoritative source of truth that Manufacturer acceptance occurred.
- Later changes to Manufacturer Profile eligibility status, Organization Membership, acting User status, future Audit Log behavior, or Profile Holder permissions never rewrite the historical confirmed Manufacturer acceptance fact.
- Manufacturer Profile assignment is immutable after confirmation. Later UNVERIFIED or SUSPENDED status does not rewrite the assignment or automatically reassign or cancel the Order Item.
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
- Transition from CONFIRMED to IN_FULFILLMENT means that applicable fulfillment execution has formally started. It is not caused merely by Payment state, Shipment creation, Listing lifecycle, or source lifecycle.
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
- Payment refund is separate from Order Item cancellation.
- An Order Item preserves the applicable Listing royalty terms and context at confirmation. It is not the authoritative accrued Royalty ledger.
- A ready-made Order Item may explicitly preserve that no designer royalty applied.
- Later Listing, Project, Revision context, Ready-Made Product, Personalization, Workspace access, Designer verification, Manufacturer Profile, or royalty-term changes never rewrite the immutable confirmed commercial snapshot.

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
- The purchase-time commercial, source, Workspace, and royalty context never changes after confirmation.
- When Personalization is used, the authoritative purchased Personalization snapshot never changes after confirmation.
- A made-to-order Order Item always has exactly one assigned Manufacturer Profile.
- A confirmed made-to-order Order Item always preserves an immutable, authoritative confirmation-time fact that required Manufacturer acceptance was obtained.
- An ordinary ready-made Order Item never has an assigned Manufacturer Profile merely for stock fulfillment.
- Manufacturer Profile assignment never changes after confirmation.
- The historical confirmed Manufacturer acceptance fact never changes after confirmation.
- An Order Item included in a Shipment is always covered in its full quantity.
- An Order Item is never covered by more than one non-CANCELLED Shipment in MVP.
- An Order Item always has exactly one lifecycle state: CONFIRMED, IN_FULFILLMENT, FULFILLED, or CANCELLED.
- Every transition from CONFIRMED to IN_FULFILLMENT satisfies the applicable path-specific authorization rules.
- FULFILLED and CANCELLED are terminal states.
- Later source, profile, access, or commercial changes never rewrite the immutable confirmed snapshot.

## Notes

The immutable source snapshot does not create a second authoritative direct source relationship. The purchased Listing remains the entity relationship, and the snapshot provides historical autonomy from current Listing and source state.

Order Item does not own Inventory, and no Inventory or Reservation entity is introduced in MVP. Temporary reservation, payment-failure release, returns, restocking, and technical locking remain future integration concerns.

Shipment provides delivery evidence toward the FULFILLED transition, while Shipment lifecycle states are not duplicated in the Order Item lifecycle. Manufacturing, payment, and refund substates also remain outside the Order Item lifecycle.

Actual Royalty accrual, amount, reversal, and payout belong to the future Royalty and Payment domains.

Seller-of-record, final payable totals, taxes, shipping amounts, discounts, payment fees, and replacement commerce remain future domain concerns.

---

Status: DRAFT

Version: 0.3
