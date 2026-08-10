# Order

## Purpose

An Order represents one confirmed buyer purchase from Creastrix that groups one or more Order Items for exactly one User and in exactly one currency.

An Order is the buyer-facing aggregate of one confirmed purchase and preserves its immutable seller, merchant, and payable-total context.

## Responsibilities

An Order is responsible for:

- representing a stable confirmed purchase identity;
- preserving its immutable Buyer User relationship;
- grouping a fixed collection of confirmed Order Items;
- preserving one currency for the purchase;
- preserving the immutable confirmation-time Creastrix seller-of-record and merchant-of-record context;
- preserving one immutable confirmed checkout delivery-destination snapshot;
- exposing the confirmed merchandise subtotal derived from its Order Items;
- preserving immutable shipping-charge, tax, discount, and confirmed payable-total amounts;
- ensuring that consumption of accepted pre-Order authorization matches the frozen prospective confirmation intent;
- providing the local atomic boundary for one-time consumption of accepted pre-Order authorization into an AUTHORIZED Payment;
- relating the purchase to its durable Payment attempts;
- defining whether the immutable purchase remains eligible for buyer payment collection;
- providing the payment-readiness boundary for fulfillment;
- representing aggregate lifecycle state derived from its Order Items;
- providing a stable boundary for other commerce integration.

## Relationships

An Order:

- belongs to exactly one Buyer User;
- contains one or more Order Items;
- has zero or more Payments;
- has zero or more Shipments;
- has no Workspace relationship.

## Business Rules

- An Order and all of its Order Items are created atomically only when confirmation succeeds.
- Listing selection, Personalization editing, pricing, validation, stock checks, Manufacturer Profile discovery, Manufacturer acceptance, payment preparation, and checkout orchestration before confirmation do not create a DRAFT Order.
- A durable pre-Order authorization submission commitment may exist in payment and checkout integration workflow before Order confirmation, but it is not an Order, does not create an Order relationship, and does not introduce a pre-confirmation Order or Checkout lifecycle.
- If any required Order Item prerequisite fails, no confirmed Order or partial collection of confirmed Order Items is created.
- Abandonment before confirmation is not Order cancellation.
- Every Order belongs to exactly one Buyer User in MVP. Organization buying and guest checkout are not supported in MVP.
- The Buyer User cannot be changed after confirmation.
- The Order Item collection is fixed at confirmation. No Order Item may be added, removed, or deleted afterward.
- Order confirmation captures exactly one immutable checkout delivery-destination snapshot containing the structured delivery information required for the physical goods in the Order.
- The confirmed delivery destination cannot change after Order confirmation in MVP.
- User Profile address or contact information is not the historical source of truth for the confirmed Order delivery destination.
- Every Shipment of an Order uses that Order's immutable confirmed delivery destination and does not own an independently divergent destination in MVP.
- One Order cannot use multiple delivery destinations in MVP.
- Every Order uses exactly one currency, and every Order Item in the Order must use that same currency.
- At confirmation, every purchased Listing represented in an Order must use that Order's currency; therefore, Listings using different currencies cannot be confirmed in the same Order in MVP.
- Creastrix is the single buyer-facing contractual seller-of-record and merchant-of-record for every MVP Order.
- Order confirmation preserves one immutable seller and merchant context identifying Creastrix. Manufacturer Profile holders, designers, Workspace owners, Created By Users, and other business participants do not automatically become buyer-facing sellers through their participation in an Order.
- An Order may structurally contain ready-made and made-to-order Order Items from multiple Listings, source Workspaces, and Manufacturer Profiles when all items use the same currency and current checkout policy permits them to be confirmed together. This mixed structure remains possible in MVP because the Order has one Creastrix buyer-facing seller and merchant context.
- An Order does not imply one source Workspace, one Manufacturer Profile, one shipment, or one fulfillment path.
- An Order has no Workspace relationship, and no ORDERS Workspace permission scope exists in MVP.
- The Buyer User accesses the User's own Orders through User and customer authorization without requiring Workspace Membership or a Workspace permission scope.
- Workspace ownership and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not automatically provide access to an entire buyer Order.
- Confirmation means Creastrix has atomically created an immutable purchase record after all domain prerequisites required by the applicable commerce paths have succeeded.
- In the normal positive-payable card flow, the first external provider authorization command may occur before Order confirmation only after durable workflow or integration commitment of stable confirmation-attempt and provider submission identities, Buyer, intended amount and currency, and sufficient frozen prospective confirmation-intent context.
- An accepted pre-Order authorization may support confirmation only when the resulting Order still matches its exact frozen prospective confirmation subject and accepted amount and currency. Matching includes the intended Buyer, selected commercial items or configurations and quantities, applicable Personalization intent, delivery-destination intent, prospective monetary total and currency, and every other materially purchase-defining confirmation input.
- Equal amount and currency do not permit reuse when another material confirmation input differs. A changed purchase requires a newly validated confirmation attempt and, when positive-payable, a new authorization commitment and provider operation.
- The pre-Order commitment does not freeze mutable domain entities or prove final confirmation eligibility. Order confirmation still revalidates Buyer actionability, Listing and source orderability, stock, current rights, pricing, Personalization, Manufacturer Profile eligibility and acceptance, monetary rules, and every other applicable commerce prerequisite.
- External authorization alone never creates an Order, Order Item, Payment, or ready-made stock allocation and never reserves or proves stock availability. Ready-made stock is allocated only inside successful local Order confirmation.
- When accepted pre-Order authorization and every current prerequisite succeed, the same local atomic transaction creates the Order, all Order Items, applicable ready-made stock allocations, immutable confirmation snapshots, and one AUTHORIZED Payment and durably establishes one-time consumption and correlation of that authorization with the resulting Payment and confirmed purchase.
- One accepted pre-Order authorization economic event or submission commitment can be consumed into at most one confirmed Order and one core AUTHORIZED Payment. Concurrent or repeated client confirmation, provider response, webhook, or reconciliation delivery for the same identities must resolve to the existing committed purchase rather than create another Order or Payment.
- If the local confirmation transaction committed but the process crashed before returning success, recovery uses durable consumption correlation to return or resolve to the existing Order and Payment. It never creates a second purchase and never voids or releases the authorization merely because the caller did not receive the response.
- If local transaction outcome is initially unknown, workflow must first reconcile local persistence by stable confirmation and provider submission identities. Compensation can proceed only after determining that no committed Order and Payment consumed the authorization.
- If confirmation definitively fails after accepted authorization because of stock, Listing orderability, User actionability, rights, Personalization, Manufacturer eligibility or acceptance, monetary validation, or any other prerequisite, no Order, Payment, partial Item collection, or stock allocation from that transaction survives. The authorization enters durable idempotent compensation and cannot later be attached to another purchase.
- Unknown provider authorization evidence does not permit Order confirmation. Workflow reconciles the same provider submission identity and does not initiate an unrelated second authorization for the same confirmation attempt.
- Pre-Order direct capture is unsupported in the current MVP. A supported direct-capture operation begins only after an Order and durable PENDING Payment already exist.
- Order confirmation does not mean that payment is settled or captured, shipment has started, or fulfillment is complete.
- Payment lifecycle remains separate from Order lifecycle. An Order may temporarily exist without a successful Payment, and a zero-payable Order may validly have no Payment.
- A zero-payable Order requires no external authorization, payment-specific pre-Order authorization commitment, or zero-value Payment.
- A positive-payable Order remains eligible for buyer payment collection only while its immutable Order Item collection and current Order rules permit collection of its full confirmed payable total.
- If any Order Item is CANCELLED before the Order's first accepted Payment capture, the Order becomes permanently closed to further buyer payment collection in MVP.
- After pre-capture Order Item cancellation closes an Order to collection, no new Payment attempt may be started and no existing PENDING or AUTHORIZED Payment may subsequently be accepted as CAPTURED. Any outstanding external authorization must be cancelled, voided, or released through durable payment compensation workflow.
- Remaining non-cancelled selections from an Order closed to collection require a new Order if the Buyer still wants to purchase them. The original Order Item collection and confirmed payable snapshot remain unchanged.
- Collection eligibility is an operational commerce condition and is not an Order lifecycle state. Order lifecycle remains derived only from Order Item states.
- When pre-capture Order Item cancellation and Payment capture evidence race, the platform may accept only a domain-consistent ordering. Once cancellation is accepted first, later capture of the original payable total is forbidden; when full capture is accepted first, normal post-capture cancellation and refund rules apply.
- For the positive-payable card MVP, payment readiness requires full accepted capture of the confirmed payable total before any Order Item may start fulfillment. A zero-payable Order is payment-ready without a Payment.
- Payment readiness is an operational prerequisite rather than an Order lifecycle state. Payment failure or timeout does not directly change Order status; eligible Order Items may later be cancelled through commerce workflow, and Order status remains derived from those Item states.
- The confirmed merchandise subtotal is the sum of the immutable line merchandise amounts of all Order Items in the Order.
- Order confirmation preserves an immutable monetary snapshot containing confirmed merchandise subtotal, buyer-facing shipping charge total, tax total, aggregate discount total, confirmed payable total, and currency.
- Under the current versioned MVP confirmation rule, confirmed payable total equals confirmed merchandise subtotal plus shipping charge total plus tax total minus aggregate discount total.
- Aggregate discount total applies against confirmed merchandise in the current MVP rather than creating a separate shipping or tax discount model.
- Shipping charge total, tax total, aggregate discount total, and confirmed payable total are non-negative, and aggregate discount total must not exceed confirmed merchandise subtotal. Therefore discount cannot make the confirmed payable total negative under the current formula.
- The confirmed monetary snapshot freezes at Order confirmation. Later Listing, Shipment, tax-rule, pricing, or other commercial changes never rewrite it.
- Cancellation and refund do not retroactively change confirmed Order Item merchandise amounts or the original confirmed monetary snapshot.
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
- An Order always preserves exactly one immutable confirmed checkout delivery-destination snapshot.
- Every Shipment of an Order always uses that Order's confirmed delivery destination in MVP.
- An Order always uses exactly one currency.
- Every Order Item in an Order always uses the Order's currency.
- An Order always preserves one immutable seller-of-record and merchant-of-record context identifying Creastrix in MVP.
- The confirmed merchandise subtotal always equals the sum of the immutable Order Item line merchandise amounts.
- An Order always preserves one immutable confirmation-time monetary snapshot containing merchandise subtotal, shipping charge total, tax total, aggregate discount total, payable total, and currency.
- The aggregate discount total never exceeds the confirmed merchandise subtotal in the current MVP.
- The confirmed payable total always satisfies the current versioned MVP confirmation rule and is never negative.
- Every positive-payable Order created through accepted pre-Order authorization matches that authorization's frozen prospective confirmation subject, Buyer, amount, and currency.
- One accepted pre-Order authorization economic event or submission commitment never creates more than one confirmed Order or more than one AUTHORIZED Payment.
- Creation of an Order through accepted pre-Order authorization always atomically records the resulting AUTHORIZED Payment and unique durable consumption correlation in the same local confirmation transaction.
- Replaying the same consumed pre-Order confirmation or provider submission identity never creates another confirmed purchase.
- An Order with any Order Item cancelled before its first accepted Payment capture is never eligible for further buyer payment collection in MVP.
- An Order closed to collection never accepts a later Payment capture against its original immutable payable total.
- Payment lifecycle and payment readiness never become Order lifecycle states.
- An Order always has exactly one aggregate lifecycle state: CONFIRMED, COMPLETED, or CANCELLED.
- The aggregate lifecycle state always corresponds to the canonical states of the Order Items.
- A confirmed Order Item record never disappears from its Order history.

## Notes

Order is not a Cart, Checkout session, Quote, Manufacturing Request, Payment, Authorization, or Shipment. No pre-confirmation Order lifecycle or new Checkout, Authorization, Payment Intent, Collection Intent, or Compensation core entity is modeled in MVP. The durable pre-Order authorization commitment and its consumption correlation remain workflow or integration persistence.

The Creastrix seller-of-record and merchant-of-record model is current MVP commerce policy rather than a permanent invariant of Workspace, Organization, Listing, Manufacturer Profile, or the future platform. If future seller or merchant context becomes third-party-specific, checkout may split selected items into separate Orders before confirmation without introducing a single-Workspace invariant into Order.

Legal, tax, invoicing, consumer-protection, VAT, acquiring, KYC, AML, and PSP feasibility of the current Creastrix seller and merchant model requires production legal and compliance validation.

Shipment grouping and delivery evidence belong to the Shipment specification.

Detailed tax jurisdiction and item-level breakdown, payment fees, settlement, consumer refund policy, disputes, chargebacks, and future seller models remain future domain concerns. Shipping-specific, tax-specific, mixed-purpose, and externally funded discount models also require future explicit domain work. Payment attempts and accepted refund facts belong to Payment, while Payment Allocations explain captured funds.

Closing an unpaid Order to collection after any pre-capture Item cancellation preserves the current full-collection Payment model without mutating the original payable snapshot or supporting partial collection. Exact concurrency controls and external authorization compensation mechanisms remain implementation concerns.

The external provider command and local Order confirmation are not one cross-system ACID transaction. Correctness requires durable provider command identity before the external call, provider idempotency or reconciliation, atomic local creation and one-time consumption correlation, and durable compensation only after absence of committed consumption is established.

Concurrent duplicate confirmation, authorization evidence, local recovery, and compensation for the same stable identities must serialize economically so at most one Order and Payment consume the authorization and compensation cannot succeed against an already committed consumption. Exact unique constraints, locks, transaction isolation, and workflow persistence structures remain implementation validation.

No Address entity is introduced in MVP. Address correction, rerouting, and multiple delivery destinations require future explicit domain work.

Order contains no Created By relationship in MVP. Its Buyer User relationship identifies the customer context of the confirmed purchase without asserting legal ownership.

---

Status: DRAFT

Version: 0.5
