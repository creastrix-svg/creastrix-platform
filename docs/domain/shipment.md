# Shipment

## Purpose

A Shipment represents one physical-delivery execution record for the full quantities of one or more Order Items grouped under one immutable fulfillment-context snapshot and sent to the delivery destination of one Order.

A Shipment may exist before physical dispatch while PREPARING.

## Responsibilities

A Shipment is responsible for:

- representing a stable delivery-execution identity;
- preserving its immutable Order relationship;
- establishing and preserving one immutable fulfillment-context snapshot;
- grouping one or more full-quantity Order Items from that Order;
- using the immutable confirmed delivery destination of its Order;
- managing the PREPARING, SHIPPED, DELIVERED, UNDELIVERED, and CANCELLED lifecycle;
- holding operational provider and tracking metadata;
- preserving accepted dispatch, delivery, and terminal non-delivery evidence;
- retaining the historical Shipment record.

## Relationships

A Shipment:

- belongs to exactly one immutable Order;
- includes one or more Order Items belonging to that Order;
- has no direct Workspace relationship;
- has no direct Manufacturer Profile relationship.

## Business Rules

- Every Shipment belongs to exactly one Order, and that relationship never changes.
- One Order may have zero or more Shipments.
- A Shipment cannot include Order Items from different Orders in MVP.
- Every Shipment establishes and preserves exactly one immutable fulfillment-context snapshot at creation.
- The fulfillment-context snapshot never changes during the Shipment lifetime, including while the Shipment is PREPARING, and remains historically and operationally meaningful when PREPARING membership changes. If another context is required, the PREPARING Shipment must be cancelled when appropriate and another Shipment must be created.
- The fulfillment path captured at creation is either made-to-order or ready-made and never switches to the other path.
- Every Shipment uses the immutable confirmed delivery destination of its Order.
- Shipment does not own an independently mutable delivery destination in MVP. Operational provider or label copies do not become a second domain source of truth.
- User Profile information is not the historical source of truth for a confirmed Order delivery destination.
- Every Shipment includes one or more Order Items and covers the full quantity of every included Order Item.
- Partial-quantity shipment is not supported in MVP. No Shipment Item or Shipment Line entity is introduced.
- At Shipment creation and whenever an Order Item is later added while PREPARING, that Item must already be IN_FULFILLMENT, belong to the Shipment's Order, use that Order's delivery destination, be compatible with the Shipment's immutable fulfillment-context snapshot and dispatch timing, be covered in its full quantity, not be covered by another non-CANCELLED Shipment, and satisfy all other Shipment grouping rules.
- Shipment creation does not transition an Order Item from CONFIRMED to IN_FULFILLMENT.
- While a Shipment is PREPARING, its current membership may change subject to all addition, removal, and grouping rules.
- If an Order Item is removed while the Shipment remains PREPARING, the Item ceases to be a Shipment member and no historical domain relationship to that Shipment is retained. Future Audit Log behavior may record the planning change.
- A Shipment must always have at least one current or frozen Order Item member. Removing an Item while PREPARING is allowed only when at least one Item remains.
- The final member cannot be removed from a PREPARING Shipment. If no member should remain, the Shipment must transition to CANCELLED while preserving its current membership as frozen history.
- When a Shipment transitions from PREPARING to SHIPPED or CANCELLED, its current membership freezes and becomes immutable. DELIVERED and UNDELIVERED retain the membership already frozen at SHIPPED.
- An Order Item may be current- or frozen-covered by at most one non-CANCELLED Shipment in MVP.
- An Item removed from a PREPARING Shipment is no longer covered by it and may join another Shipment when all rules pass.
- A CANCELLED Shipment retains its frozen membership as history but does not prevent a replacement non-CANCELLED Shipment from covering those Items.
- An UNDELIVERED Shipment is non-CANCELLED, retains its frozen membership as history, and continues to prevent another non-CANCELLED Shipment from covering those Items. Reshipment or replacement after dispatch is unsupported in MVP.
- Order Items may share one Shipment only when they have the same Order, use that Order's delivery destination, are compatible with the Shipment's immutable fulfillment-context snapshot and dispatch timing, and are covered in their full quantities.
- Ready-made and made-to-order Order Items cannot be mixed in one Shipment in MVP.
- A made-to-order Shipment's immutable fulfillment-context snapshot captures the made-to-order path and the Manufacturer Profile identity defining its execution context. That identity is captured at creation from the common immutable Manufacturer Profile assignments of the creation-time Order Items.
- The Order Item Manufacturer Profile assignments remain authoritative. The captured Manufacturer Profile identity is immutable historical execution-context data inside the Shipment snapshot, not a second direct Manufacturer Profile relationship or mutable Shipment property.
- Every made-to-order Order Item added later must have the same assigned Manufacturer Profile as the identity captured in the Shipment fulfillment-context snapshot, even if every creation-time Item has since been removed while the Shipment remains PREPARING.
- A ready-made Shipment's immutable fulfillment-context snapshot captures the ready-made path and a stable opaque platform-controlled context value sufficient to preserve execution and grouping identity. Its exact representation remains a future implementation detail and does not require a Warehouse, Fulfillment Actor, or Location entity.
- Every ready-made Order Item added later must remain compatible with the opaque platform-controlled context value captured in that snapshot. Membership replacement never changes the Shipment to another ready-made context value.
- Ready-made fulfillment does not infer that a source Workspace owner is a shipper, custodian, fulfillment actor, or seller-of-record.
- A Shipment has exactly one lifecycle state: PREPARING, SHIPPED, DELIVERED, UNDELIVERED, or CANCELLED.
- PREPARING means the Shipment record exists, physical dispatch has not occurred, current membership may still be adjusted subject to Shipment addition and removal rules, and provider or tracking preparation may occur.
- SHIPPED means the platform has accepted sufficient evidence that physical dispatch or handoff occurred.
- DELIVERED means the platform has accepted sufficient evidence that the Shipment reached the confirmed delivery destination of its Order.
- UNDELIVERED means physical dispatch occurred, but the platform has accepted sufficiently definitive evidence that this Shipment execution did not and will not reach the immutable confirmed delivery destination of its Order.
- CANCELLED means Shipment preparation will not proceed to dispatch and the historical Shipment record is retained.
- The allowed lifecycle transitions are PREPARING to SHIPPED, PREPARING to CANCELLED, SHIPPED to DELIVERED, and SHIPPED to UNDELIVERED.
- DELIVERED, UNDELIVERED, and CANCELLED are terminal states. No terminal state may be reopened or changed to another terminal state, and SHIPPED cannot transition to CANCELLED in MVP.
- PREPARING cannot transition to UNDELIVERED because UNDELIVERED preserves the fact that physical dispatch occurred. UNDELIVERED is not pre-dispatch cancellation.
- UNDELIVERED requires accepted definitive terminal non-delivery evidence under applicable platform authorization and evidence rules. Potentially sufficient evidence may include an authoritative carrier or provider conclusion that the Shipment is lost or irrecoverable, that final delivery failed with no further attempt for this execution, or that it was definitively returned without reaching the buyer destination; these examples are inputs for platform acceptance and are not automatic domain truth.
- Normal delivery delay, a missed estimate, tracking silence, a first failed delivery attempt, a temporary carrier exception, an out-for-delivery failure, an unsupported Buyer report, ambiguous provider evidence, or support suspicion is not sufficient by itself. While the outcome remains operationally or economically unknown, the Shipment remains SHIPPED.
- Transition to UNDELIVERED preserves the accepted dispatch fact, frozen membership, immutable fulfillment-context snapshot, Order relationship, Order delivery destination, and provider or tracking evidence history.
- Transition to UNDELIVERED does not automatically cancel an Order Item, change Order lifecycle, create or accept a Payment refund, release ready-made stock, create a replacement Order Item or Shipment, or change Manufacturer compensation, Payment Allocation, Royalty, or Payout history.
- An UNDELIVERED Shipment may provide the required terminal evidence for a separate authorized commerce resolution to cancel an eligible IN_FULFILLMENT Order Item. Shipment does not perform that cancellation, and each affected Item in a multi-item Shipment requires its own applicable resolution.
- A platform-authorized Shipment workflow must accept terminal non-delivery evidence under the applicable fulfillment context. Buyer status, Workspace ownership or Membership, Organization Membership, Manufacturer Profile Holder status, and the PROJECTS, READY_MADE_PRODUCTS, or LISTINGS scopes do not grant authority to assign UNDELIVERED.
- No SHIPMENTS Workspace scope is introduced in MVP.
- External provider integrations may supply tracking data and dispatch or delivery evidence and may trigger or participate in a platform-authorized workflow, but they do not independently receive general Shipment creation, membership, lifecycle, or mutation authority.
- External provider status is evidence or input and does not independently determine Shipment lifecycle without acceptance under applicable authorization and evidence rules.
- Shipment membership and lifecycle mutation remain governed by Creastrix authorization rules. A provider-triggered workflow may perform an operation only when the platform-authorized workflow has the applicable authority and accepts the required evidence.
- For the current physical-delivery MVP, a DELIVERED Shipment covering the full quantity of an Order Item permits that Item to transition to FULFILLED only when all other applicable fulfillment obligations are complete.
- Shipment DELIVERED is delivery evidence and is not a universal permanent synonym for Order Item FULFILLED.
- An UNDELIVERED Shipment cannot provide the DELIVERED evidence required for Order Item fulfillment.
- Cancelling a PREPARING Shipment does not automatically cancel an included Order Item or rewrite its commercial snapshot.
- If an Order Item becomes CANCELLED while its Shipment is PREPARING, it must not remain scheduled for dispatch. It may be removed when other dispatchable current members remain; otherwise the Shipment must transition to CANCELLED.
- Once the non-CANCELLED Shipment covering an Order Item is SHIPPED, ordinary MVP Order Item cancellation is not permitted through pre-dispatch cancellation behavior. The only post-dispatch exception in C2 is the separate authorized terminal non-delivery resolution for an IN_FULFILLMENT Item whose covering Shipment has already become UNDELIVERED.
- Shipment may hold operational provider identification, service, label, tracking, URL, and provider-status metadata without requiring a Carrier entity or exact fields in this specification.
- Provider or tracking metadata may evolve, but later updates never rewrite established dispatch, delivery, or terminal non-delivery facts.
- The Buyer User may access Shipments belonging to the Buyer's own Order through customer authorization.
- Buyer status by itself does not grant Shipment operational preparation or mutation authority.
- Made-to-order Shipment operational preparation and mutation require authorization within the Manufacturer Profile context identified by the Shipment's immutable fulfillment-context snapshot.
- Such made-to-order operations may be performed by an authorized User acting through that Manufacturer Profile holder context or by explicitly authorized platform automation or internal platform processes acting within the same Manufacturer Profile context.
- Ready-made Shipment operational preparation and mutation use internal platform authorization in MVP.
- Authorized platform automation and internal platform processes do not require a User actor but remain subject to the context-specific rules of the Shipment operation.
- Workspace ownership, Workspace Membership, Organization Membership, and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not automatically provide Shipment or buyer-destination access or Shipment mutation authority.
- Shipment has no buyer-facing monetary semantics and does not own shipping price, tax, payment amount, or operational carrier cost in this specification.
- Shipment does not reserve or allocate stock, change ordered quantity, own manufacturing, or manage Payment state.
- Shipment UNDELIVERED and return-to-sender evidence do not establish that a ready-made unit has been physically received, inspected, accepted for restock, or returned to available quantity.
- Shipment does not require a Created By User. It may be created through an authorized User workflow, authorized platform automation, an authorized internal platform process, or a platform-authorized workflow triggered by provider integration. Provider integration does not independently own Shipment creation or mutation authority, and initiator provenance may later belong to Audit Log.
- Competing SHIPPED-to-DELIVERED and SHIPPED-to-UNDELIVERED decisions, and any dependent Order Item fulfillment or terminal non-delivery resolution, require current-state revalidation and domain-consistent serialization. Only one terminal Shipment transition may commit; conflicting later evidence is a reconciliation or support concern and never rewrites terminal history.

## Invariants

- A Shipment always has one stable identity.
- A Shipment always belongs to exactly one Order.
- The Order relationship never changes.
- A Shipment always has exactly one immutable fulfillment-context snapshot.
- The fulfillment-context snapshot never changes during the Shipment lifetime.
- A Shipment's fulfillment path never switches between made-to-order and ready-made.
- A Shipment always has at least one current or frozen Order Item member.
- Every current or frozen Order Item member belongs to the Shipment's Order.
- Every current or frozen Order Item member is always covered in its full Order Item quantity.
- An Order Item is never current- or frozen-covered by more than one non-CANCELLED Shipment in MVP.
- Ready-made and made-to-order Order Items never share one Shipment in MVP.
- Every current or frozen made-to-order member always matches the Manufacturer Profile identity captured in the immutable fulfillment-context snapshot at Shipment creation.
- Every current or frozen ready-made member always matches the opaque platform-controlled context value captured in the immutable fulfillment-context snapshot at Shipment creation.
- Every Shipment always uses the immutable confirmed delivery destination of its Order.
- A Shipment always has exactly one lifecycle state: PREPARING, SHIPPED, DELIVERED, UNDELIVERED, or CANCELLED.
- DELIVERED, UNDELIVERED, and CANCELLED are terminal states.
- Shipment membership never changes after the Shipment becomes SHIPPED or CANCELLED, and DELIVERED and UNDELIVERED retain the membership frozen at SHIPPED.
- One Shipment never becomes both DELIVERED and UNDELIVERED.
- UNDELIVERED always preserves prior physical dispatch and never means pre-dispatch cancellation.
- Established dispatch, delivery, and terminal non-delivery facts are never rewritten by later provider metadata changes.
- A Shipment never has a direct Workspace relationship.

## Notes

Shipment is not an Order, Order Item, Manufacturer Profile, Warehouse, stock allocation, manufacturing process, Payment, seller identity, or carrier account.

No Fulfillment Context, Shipment Item, Shipment Line, Address, Carrier, Warehouse, Location, Fulfillment Actor, Return, Delivery Attempt, Parcel, Payment, or Inventory entity is introduced by this specification.

The immutable Order delivery destination is the domain source of truth. Operational destination copies used by providers or labels do not create an independent Shipment destination in MVP.

The immutable fulfillment-context snapshot is an embedded domain value of Shipment in MVP, not a separate domain entity. The made-to-order snapshot contains the captured Manufacturer Profile identity only as immutable execution-context data derived from authoritative Order Item assignments. The ready-made snapshot contains an opaque platform-controlled context value.

Terminal non-delivery after dispatch is represented by UNDELIVERED in C2. Reshipment, replacement, returns and restocking, parcel-level behavior, delivery attempts, claims and insurance, partial loss, address rerouting, pickup, multiple destinations, partial shipment, and third-party ready-made fulfillment remain future domain work.

Shipment DELIVERED may later provide evidence toward Designer Review, Manufacturer Review, or other post-delivery eligibility without owning those rules.

Exact dispatch, delivery, and terminal non-delivery evidence fields, carrier status mappings, provider trust, support authorization, internal ready-made fulfillment locations, retention of private delivery data, and Audit Log provenance remain future refinements. Accepted UNDELIVERED resolution must preserve sufficient evidence or provenance for historical explanation without introducing a Delivery Failure, Shipment Event, Carrier Claim, Lost Parcel, or other core entity.

Future executable implementation must prove mutually exclusive DELIVERED and UNDELIVERED transitions, idempotent duplicate terminal evidence handling, SHIPPED retention for ambiguous evidence, absence of automatic Order Item cancellation, refund, or stock release, the UNDELIVERED prerequisite for post-dispatch Item cancellation, the terminal nature of FULFILLED Items, continued at-most-one non-CANCELLED Shipment coverage, and correct ready-made pre-dispatch release versus post-dispatch no-release behavior. Exact locking, transaction isolation, version checks, constraints, and persistence structures remain implementation validation.

---

Status: DRAFT

Version: 0.3
