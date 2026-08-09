# Order

## Purpose

An Order represents one confirmed buyer purchase on Creastrix that groups one or more Order Items for exactly one User and in exactly one currency.

An Order is the buyer-facing aggregate of one confirmed purchase.

## Responsibilities

An Order is responsible for:

- representing a stable confirmed purchase identity;
- preserving its immutable Buyer User relationship;
- grouping a fixed collection of confirmed Order Items;
- preserving one currency for the purchase;
- exposing the confirmed merchandise subtotal derived from its Order Items;
- representing aggregate lifecycle state derived from its Order Items;
- providing a stable boundary for future Payment and other commerce integration.

## Relationships

An Order:

- belongs to exactly one Buyer User;
- contains one or more Order Items;
- may later be referenced by Payments and other commerce records;
- has no Workspace relationship.

## Business Rules

- An Order and all of its Order Items are created atomically only when confirmation succeeds.
- Listing selection, Personalization editing, pricing, validation, stock checks, Manufacturer Profile discovery, Manufacturer acceptance, payment preparation, and checkout orchestration before confirmation do not create a DRAFT Order.
- If any required Order Item prerequisite fails, no confirmed Order or partial collection of confirmed Order Items is created.
- Abandonment before confirmation is not Order cancellation.
- Every Order belongs to exactly one Buyer User in MVP. Organization buying and guest checkout are not supported in MVP.
- The Buyer User cannot be changed after confirmation.
- The Order Item collection is fixed at confirmation. No Order Item may be added, removed, or deleted afterward.
- Every Order uses exactly one currency, and every Order Item in the Order must use that same currency.
- At confirmation, every purchased Listing represented in an Order must use that Order's currency; therefore, Listings using different currencies cannot be confirmed in the same Order in MVP.
- An Order may structurally contain ready-made and made-to-order Order Items from multiple Listings, source Workspaces, and Manufacturer Profiles when all items use the same currency and current checkout policy permits them to be confirmed together.
- An Order does not imply one seller, one source Workspace, one Manufacturer Profile, one shipment, or one fulfillment path.
- An Order has no Workspace relationship, and no ORDERS Workspace permission scope exists in MVP.
- The Buyer User accesses the User's own Orders through User and customer authorization without requiring Workspace Membership or a Workspace permission scope.
- Workspace ownership and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not automatically provide access to an entire buyer Order.
- Confirmation means Creastrix has atomically created an immutable purchase record after all domain prerequisites required by the applicable commerce paths have succeeded.
- Order confirmation does not mean that payment is settled, shipment has started, fulfillment is complete, or a seller-of-record has been inferred.
- Payment state is not part of the Order lifecycle. A checkout flow may operationally require payment authorization, but that is not a universal Order confirmation invariant in this specification.
- The confirmed merchandise subtotal is the sum of the immutable line merchandise amounts of all Order Items in the Order.
- The confirmed merchandise subtotal is not a final payable total and does not include future tax, shipping, coupon, payment-fee, or order-level discount semantics.
- Cancellation does not retroactively change confirmed Order Item merchandise amounts or the originally confirmed merchandise subtotal.
- An Order has the aggregate lifecycle state CONFIRMED, COMPLETED, or CANCELLED.
- An Order is CONFIRMED while at least one Order Item remains non-terminal.
- An Order is CANCELLED when all of its Order Items are CANCELLED.
- An Order is COMPLETED when all of its Order Items are terminal, at least one is FULFILLED, and every remaining item, if any, is CANCELLED.
- A request to cancel an Order attempts to cancel all applicable non-terminal Order Items and does not directly overwrite Order status.
- Order lifecycle state is canonically derived from Order Item states. If materialized for querying, it must remain consistent with that derivation.
- Cancellation and fulfillment state changes never remove confirmed Order Items or rewrite their immutable commercial history.

## Invariants

- An Order always has one stable identity.
- An Order always belongs to exactly one Buyer User.
- The Buyer User never changes after confirmation.
- An Order always contains at least one Order Item.
- Every Order Item in an Order belongs to that Order only.
- Order Item membership never changes after confirmation.
- An Order always uses exactly one currency.
- Every Order Item in an Order always uses the Order's currency.
- The confirmed merchandise subtotal always equals the sum of the immutable Order Item line merchandise amounts.
- An Order always has exactly one aggregate lifecycle state: CONFIRMED, COMPLETED, or CANCELLED.
- The aggregate lifecycle state always corresponds to the canonical states of the Order Items.
- A confirmed Order Item record never disappears from its Order history.

## Notes

Order is not a Cart, Checkout session, Quote, Manufacturing Request, Payment, or Shipment. No pre-confirmation Order lifecycle is modeled in MVP.

Future checkout, seller, payment, or legal policy may split selected items into multiple Orders before confirmation without introducing a single-Workspace invariant into Order.

Final payable total, taxes, shipping amounts, discounts, payment fees, payment attempts, settlement, refunds, shipment grouping, and seller-of-record remain future domain concerns.

Order contains no Created By relationship in MVP. Its Buyer User relationship identifies the customer context of the confirmed purchase without asserting legal ownership.

---

Status: DRAFT

Version: 0.1
