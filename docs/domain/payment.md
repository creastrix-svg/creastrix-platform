# Payment

## Purpose

A Payment represents one durable attempt to collect buyer funds for exactly one existing Order.

It preserves the accepted financial and provider-correlated history of that collection attempt without becoming the Order lifecycle, settlement, payout, Royalty, or seller identity.

## Responsibilities

A Payment is responsible for:

- preserving its immutable relationship with one Order;
- managing the PENDING, AUTHORIZED, CAPTURED, FAILED, and CANCELLED collection lifecycle;
- preserving its immutable currency and intended amount;
- respecting the collection eligibility of its Order;
- preserving accepted authorization and capture facts where applicable;
- preserving immutable accepted provider correlation transferred from pre-Order workflow when confirmation creates it as AUTHORIZED;
- correlating the collection attempt with external provider evidence;
- preserving append-only accepted refund facts, their immutable economic instruction snapshots, and the cumulative refund condition derived from them;
- providing the immutable basis for durable and economically idempotent provider-refund execution and reconciliation;
- preserving only non-sensitive historical payment-method metadata;
- providing the captured-funds boundary for Payment Allocations.

## Relationships

A Payment:

- belongs to exactly one immutable Order;
- derives its Buyer User through that Order;
- may have zero or more Payment Allocations;
- has no Workspace relationship.

## Business Rules

- A core Payment cannot exist without an Order in MVP, and one Payment never covers more than one Order.
- A core Payment in MVP always belongs to a positive-payable Order and represents an attempt to collect that Order's full immutable confirmed payable total. Its intended amount is greater than zero and always equals the Order's confirmed payable total.
- A Payment may be created, authorized, or accepted as CAPTURED only while its Order remains eligible for buyer payment collection under current Order rules.
- An Order may have zero or more Payments over time. A zero-payable Order does not create a zero-value Payment merely to represent payment readiness and may validly have no Payment.
- In the normal positive-payable card flow, external provider authorization may be obtained before the Order exists only through payment and checkout integration workflow backed by a durable pre-Order authorization submission commitment.
- The pre-Order authorization submission commitment is workflow or integration persistence rather than a core domain entity. It is not Payment, Order, Checkout, Payment Attempt, Payment Method, Payment Provider, Authorization, Payment Intent, Collection Intent, Compensation, or Idempotency Key and may exist before Order and Payment.
- Before the first external authorization command for one confirmation attempt, the platform must durably preserve a stable platform confirmation-attempt identity, stable provider submission or idempotency identity, provider and account or routing context sufficient for correlation, Buyer User identity, intended amount, currency, and sufficient frozen prospective confirmation-intent context. The provider command may be sent only after that durable commitment succeeds.
- Frozen prospective confirmation-intent context contains or identifies enough immutable intent to bind the authorization to the intended Buyer, selected commercial items or configurations and quantities, applicable Personalization intent, delivery-destination intent, prospective monetary total and currency, and other materially purchase-defining confirmation inputs.
- Exact commitment storage may use normalized workflow values, stable references, or another immutable representation or fingerprint. It does not duplicate a complete future Order, does not require cryptographic hashing as a domain rule, and does not introduce a Cart or Checkout entity.
- An accepted pre-Order authorization may be used for confirmation only when the resulting purchase still matches the frozen prospective confirmation subject and accepted provider amount and currency. A material purchase-defining change prevents consumption even when amount and currency happen to remain equal.
- Accepted authorization remains bound to the stable confirmation-attempt identity of its commitment and cannot be attached or replayed through another confirmation attempt even when the prospective subject, amount, and currency appear identical.
- The commitment does not freeze or reserve mutable source entities. Listing orderability, stock, rights, Manufacturer Profile eligibility and acceptance, Personalization validity, User actionability, pricing, and every other applicable confirmation prerequisite are revalidated normally at Order confirmation.
- One pre-Order provider submission identity represents at most one external economic authorization attempt. Duplicate client requests for the same confirmation-attempt identity and an economically unknown provider outcome use the same unchanged submission identity for idempotent retry, query, or reconciliation and never start an unrelated replacement authorization.
- Pre-Order authorization may be enabled only with a provider integration that supports sufficiently safe economic idempotency, lookup, or reconciliation for an unknown outcome. Blind independent retry is forbidden when the same economic authorization cannot be safely determined or retried.
- Provider responses and webhooks remain evidence rather than automatic domain truth. Before accepted authorization can support confirmation, platform workflow validates provider and account correlation, binding to the exact commitment, amount, currency, current validity, and absence of prior or inconsistent consumption or recognition.
- Provider authorization or reference identity may become known only after a provider response or reconciliation discovers it. Once accepted, its provider, account, submission, and economic-authorization correlation is immutable.
- When accepted pre-Order authorization and every current confirmation prerequisite succeed, the Order, all Order Items, applicable ready-made stock allocations, immutable Order monetary snapshots, and an AUTHORIZED Payment are created together in one local confirmation transaction. That transaction also durably establishes one-time consumption and correlation between the pre-Order commitment, accepted provider authorization, resulting Payment, and confirmed purchase.
- Durable pre-Order consumption correlation may remain workflow or integration persistence and does not create a core Payment relationship to a pre-Order object. One accepted authorization economic event or submission commitment can produce at most one core AUTHORIZED Payment and at most one confirmed Order.
- Repeated client confirmation, provider response, webhook, or reconciliation delivery for an already consumed authorization resolves to the existing committed Order and Payment and never creates another purchase or collection attempt.
- If local confirmation committed successfully but the process crashes before returning success, durable consumption correlation proves that the authorization already belongs to the existing AUTHORIZED Payment. Recovery returns or resolves to that purchase and must neither create another Order or Payment nor compensate the consumed authorization merely because the caller missed the response.
- If local transaction outcome is initially unknown after a crash, workflow first reconciles local persistence using the stable confirmation and submission identities. Compensation may begin only after determining that no committed Order and Payment consumed the authorization.
- If accepted authorization is not consumed because local confirmation definitively fails, no Order, core Payment, partial Item collection, or ready-made stock allocation from that confirmation survives. The authorization cannot be reused for a changed or later Order and must enter durable compensation and reconciliation; a materially changed purchase requires a new validated confirmation attempt and, when positive-payable, a new provider submission identity.
- Before the first external void or release command, compensation workflow durably preserves a stable compensation-operation identity and correlation targeting the original commitment and accepted provider authorization. Unknown compensation outcome is retried, queried, or reconciled through that same operation rather than an unrelated blind command, and no Compensation entity is introduced.
- When reliable provider evidence definitively confirms void or release, the external authorization hold is resolved for workflow purposes. Exact compensation status and retention representation remain integration details.
- A void or release may proceed only when the authorization has not been consumed into a committed Order and Payment. Consumption and compensation for the same accepted authorization must serialize so they cannot both succeed economically.
- If provider evidence definitively establishes that authorization failed and no external hold or economic authorization exists, no Order or core Payment is created. The commitment may reach a terminal workflow outcome, and a later buyer attempt requires normal revalidation and a new submission identity.
- While authorization outcome remains unknown, no confirmed Order is created from unaccepted evidence and no unrelated second authorization is started for the same confirmation-attempt identity. Safety takes precedence over immediate liveness.
- Pre-Order direct capture before a core Payment exists is unsupported in the current MVP. The pre-Order flow is authorization only.
- A zero-payable Order requires no external authorization, pre-Order authorization submission commitment, or zero-value Payment merely for payment readiness.
- Once an Order exists, each retry is represented by a new Payment. A retry Payment may begin in PENDING while its final provider result has not yet been accepted, but its intended amount still equals the same immutable Order confirmed payable total rather than only an unpaid difference.
- For an economic provider command against an existing Payment, that Payment is the durable local attempt and correlation anchor. Integration still establishes a stable provider command or idempotency identity before sending the command, but no separate pre-Order authorization commitment is required.
- If any Order Item is CANCELLED before the Order's first accepted Payment capture, the Order is permanently closed to further collection in MVP. No new Payment attempt may be started, and no existing PENDING or AUTHORIZED Payment may subsequently be accepted as CAPTURED.
- After pre-capture cancellation closes the Order to collection, any outstanding external authorization must be durably and idempotently cancelled, voided, or released through payment compensation and reconciliation workflow.
- When pre-capture Order Item cancellation and capture evidence race, capture may be accepted only if it is accepted before the Item cancellation. Once cancellation is accepted, later capture evidence for the original immutable payable total cannot be accepted as CAPTURED.
- A FAILED or CANCELLED Payment never returns to PENDING.
- At most one PENDING or AUTHORIZED Payment may be active for an Order at one time in MVP.
- After one Payment has been fully CAPTURED for the Order's confirmed payable total, no additional collection attempt may be started. A later refund does not automatically reopen collection.
- Split tender and intentional partial payment or capture are unsupported in MVP.
- A Payment has exactly one immutable currency, and it must equal the currency of its Order.
- A Payment preserves its intended amount and accepted authorized and captured amounts where applicable. Its intended amount always equals the Order's confirmed payable total.
- When a Payment has an accepted authorization fact, the accepted authorized amount is greater than zero and equals both the Payment intended amount and the Order confirmed payable total.
- A Payment may reach CAPTURED through a supported direct-capture flow without a separate accepted authorization fact only after its Order and durable PENDING Payment already exist. That Payment is the durable local correlation anchor before the external capture command.
- For every CAPTURED Payment, the accepted captured amount is greater than zero and equals both the Payment intended amount and the Order confirmed payable total.
- Provider evidence of a partial or otherwise mismatched capture requires exception handling and reconciliation and cannot be accepted as a normal CAPTURED Payment in the current MVP.
- Across all Payments of one Order, accepted gross captured amount must never exceed the Order's confirmed payable total. Overpayment is forbidden.
- A Payment has exactly one lifecycle state: PENDING, AUTHORIZED, CAPTURED, FAILED, or CANCELLED.
- PENDING means a durable collection attempt exists for an existing Order but the final provider collection result has not been accepted.
- AUTHORIZED means the platform has accepted valid evidence that provider authorization for the required amount exists.
- CAPTURED means the platform has accepted valid evidence of capture of the authoritative amount. It does not mean final bank settlement, beneficiary payout, absence of chargeback or dispute risk, or irreversible funds.
- FAILED means the collection attempt cannot continue successfully under the current workflow.
- CANCELLED means the collection attempt was intentionally terminated or voided without capture.
- The allowed lifecycle transitions are PENDING to AUTHORIZED, CAPTURED, FAILED, or CANCELLED, and AUTHORIZED to CAPTURED, FAILED, or CANCELLED.
- CAPTURED, FAILED, and CANCELLED are terminal for the collection lifecycle of that Payment.
- REFUNDED is not a Payment lifecycle state. Not-refunded, partially-refunded, and fully-refunded conditions are derived from append-only accepted refund history.
- External provider responses and webhooks are evidence rather than automatic domain truth. Before accepting authorization, capture, refund, cancellation, or void evidence, platform rules validate provider and account identity, authenticity, correlation, amount, currency, current Payment state, duplicate economic-event identity, and the allowed transition.
- Once accepted for a Payment, its provider, account, and transaction correlation identity remains immutable. A later attempt, including one routed through another provider, is a new Payment.
- Only an authorized platform payment workflow may mutate Payment lifecycle or accept financial evidence. A Buyer may initiate checkout, retry, or a refund request but cannot directly assign Payment domain state.
- A Manufacturer, beneficiary, Workspace member, or Organization member does not receive Payment mutation authority merely because of that role or relationship.
- The same external economic authorization, capture, cancellation, void, or refund must not be recognized more than once.
- Payment may preserve non-sensitive historical method metadata such as method type, masked display data, a provider token or reference, and card brand or last digits where legally and operationally permitted.
- Raw card number, CVV, and complete payment credentials are never Payment domain data.
- No Refund entity exists in this specification version. Refund instructions, accepted refund facts, and component snapshots are embedded values or workflow records associated with Payment rather than separate core entities.
- The current MVP deterministic policy for translating an accepted buyer refund into Payment Allocation REVERSALS is `PLATFORM_FIRST_WITH_ROYALTY_NO_SUBSIDY_SAFETY_FLOOR_V1`.
- No economically unscoped accepted refund exists in MVP. Before provider execution, every refund instruction contains one or more explicit immutable buyer-facing economic components.
- The only refund component types in MVP are ITEM_MERCHANDISE, SHIPPING, and TAX. An ITEM_MERCHANDISE component references exactly one Order Item, a SHIPPING component references the Order-level SHIPPING context, and a TAX component references the Order-level TAX context.
- Every refund component has a positive amount, uses the Payment currency, identifies its exact immutable economic subject, and respects the remaining capacity for that subject.
- Duplicate component keys are normalized before provider execution. One refund instruction has at most one component for each pair of ITEM_MERCHANDISE and exact Order Item, at most one SHIPPING component for the Order, and at most one TAX component for the Order.
- The sum of refund component amounts equals the provider refund amount exactly.
- DISCOUNT, ITEM_PROCEEDS, MANUFACTURING_COMPENSATION, ROYALTY, and PROCESSOR_FEE are not buyer-facing refund component types. ITEM_PROCEEDS and MANUFACTURING_COMPENSATION are internal Payment Allocation results of applying the approved policy to an ITEM_MERCHANDISE component.
- Every accepted refund fact preserves an immutable economic instruction snapshot containing at least its stable platform refund-event identity, provider refund reference when known, amount, currency, platform-accepted timestamp, normalized refund component list, applicable refund policy identifier and version, and appropriate reason and evidence context. Each ITEM_MERCHANDISE component preserves its exact Order Item identity.
- The accepted refund component snapshot represents buyer-facing economic attribution. The complete correlated Payment Allocation REVERSAL set is the authoritative internal captured-funds attribution produced from that snapshot.
- An accepted refund fact may exist only for a CAPTURED Payment. No accepted refund fact may be attached to a PENDING, AUTHORIZED, FAILED, or CANCELLED Payment.
- Every accepted refund amount is greater than zero and uses the Payment currency. Foreign-exchange refund inside Payment is unsupported in MVP.
- The immutable refund-event identity provides stable correlation for idempotency, reconciliation, and the complete REVERSAL Payment Allocation set associated with that accepted refund. Exact storage fields remain implementation detail.
- Before the first provider refund command is sent, the platform durably freezes one workflow or integration commitment containing a stable submission and idempotency identity, Payment and provider context, amount, currency, exact normalized refund component instruction, and applicable refund policy identifier and version. The provider command may occur only after that commitment succeeds.
- At most one active or economically unknown refund submission may exist for one Payment at a time in MVP.
- While an earlier refund submission outcome is economically unknown, no second refund submission for that Payment may be committed, its frozen component capacities remain reserved, and retry or provider query uses the same submission identity and unchanged instruction rather than recalculating from newer state.
- If the provider definitively rejects or fails the committed operation with reliable evidence that no refund occurred or will occur, the capacity reservation is released. A later refund instruction requires complete revalidation and a new commitment.
- If the provider may have accepted a refund but the application loses the response, the platform must query, retry idempotently, or reconcile the same provider operation and must not issue an unrelated second refund. Blind economic retry is forbidden when the provider cannot support safe idempotency or lookup and reconciliation.
- One refund submission identity never changes its amount, currency, components, or policy version.
- Platform recognition of an accepted refund fact, its immutable accepted component snapshot, and its complete correlated positive REVERSAL Payment Allocation set is one local atomic domain operation. No zero-value REVERSAL Allocation is created.
- For every accepted refund event, the sum of component amounts, the accepted refund amount, and the sum of correlated REVERSAL Payment Allocation amounts are equal.
- If a provider refund succeeds externally but local recognition fails, retry or reconciliation must recognize the same frozen refund event exactly once without creating another accepted refund fact or REVERSAL set.
- Payment remains CAPTURED after an accepted refund. Cumulative accepted refund amount must never exceed captured amount, and accepted refund history cannot be destructively deleted.
- Refund allocation selection is derived only from the immutable refund instruction, immutable Order and Order Item monetary snapshots, append-only accepted financial history, and the frozen policy version. It is independent from current Listing, Profile, Manufacturer Profile, Workspace, or other mutable state.
- An accepted refund does not directly change Order Item lifecycle, release ready-made stock, or imply Order Item cancellation.
- For the positive-payable card MVP, an Order is payment-ready for fulfillment only after full accepted capture of its confirmed payable total. A zero-payable Order is payment-ready without a Payment.
- Payment readiness is not an Order or Order Item lifecycle state, and Payment state does not directly transition or cancel an Order Item.
- After terminal payment resolution failure and a bounded retry window, commerce workflow may attempt cancellation of eligible Order Items. Order status remains derived from Order Item states.
- The Buyer may view Payment amount, currency, state, non-sensitive method metadata, and a buyer-facing refund summary for the Buyer's own Order through customer authorization. The summary may identify refunded Items and explicit shipping or tax components where appropriate.
- Buyer access does not expose internal Payment Allocations, manufacturer compensation, merchant-side item proceeds, Royalty reversal mechanics, Payout details, or internal provider reconciliation details.
- Workspace Membership and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not expose Payment. No PAYMENTS Workspace permission scope exists in MVP.

## Invariants

- A Payment always has one stable identity.
- A Payment always belongs to exactly one existing Order, and that relationship never changes.
- A Payment never covers more than one Order.
- A Payment intended amount is always greater than zero and always equals its Order's confirmed payable total.
- An accepted authorized amount, when present, is greater than zero and always equals the Payment intended amount.
- An accepted captured amount, when present, is greater than zero and always equals the Payment intended amount.
- A Payment always uses exactly one immutable currency equal to its Order currency.
- A Payment always has exactly one lifecycle state: PENDING, AUTHORIZED, CAPTURED, FAILED, or CANCELLED.
- CAPTURED, FAILED, and CANCELLED never return to a non-terminal collection state.
- Intentional partial payment, split tender, and overpayment never occur in MVP.
- Accepted gross captured amount across an Order's Payments never exceeds the confirmed payable total.
- A Payment is never accepted as CAPTURED after its Order has been closed to collection by pre-capture Order Item cancellation.
- Every external pre-Order authorization command is preceded by successful durable preservation of its stable confirmation-attempt and provider submission commitment.
- One accepted pre-Order authorization economic event or submission commitment is consumed by at most one core AUTHORIZED Payment and at most one confirmed Order.
- Every AUTHORIZED Payment created from accepted pre-Order authorization has one durable unique consumption correlation to that authorization and its confirmed purchase.
- A pre-Order provider submission identity never changes its Buyer, intended amount, currency, or frozen prospective confirmation subject.
- Compensation for accepted pre-Order authorization never proceeds as resolved against an authorization already consumed into a committed Payment and Order.
- The same external economic event is never recognized more than once.
- Accepted provider, account, and transaction correlation identity never changes for an existing Payment.
- Every accepted refund fact always has exactly one immutable refund-event identity.
- An accepted refund fact exists only for a CAPTURED Payment, always has an amount greater than zero, always uses the Payment currency, and always preserves one immutable normalized component snapshot and refund policy identifier and version.
- Every accepted refund component has a supported type, positive amount, Payment currency, and exact immutable economic subject.
- One accepted refund snapshot never contains more than one component with the same normalized component key.
- Component amounts of one accepted refund always sum exactly to its accepted refund amount.
- Cumulative accepted refund amount never exceeds captured amount.
- The sum of REVERSAL Payment Allocation amounts correlated with one accepted refund-event identity always equals both that accepted refund amount and the sum of its component amounts.
- One Payment never has more than one active or economically unknown refund submission commitment in MVP.
- One refund submission identity never changes its amount, currency, component instruction, or policy version.
- Accepted refund fact, component, provider-correlation, and resulting REVERSAL history is never destructively rewritten.
- Accepted authorization, capture, refund, and provider-correlation history is never destructively deleted or rewritten.
- A CAPTURED Payment always has a complete ORIGINAL Payment Allocation set whose amounts equal its captured amount.
- A Payment never has a direct Workspace relationship.

## Notes

Payment is not an Order, Order Item, Payment Allocation, Checkout, Payment Attempt, Payment Method, Payment Provider, Authorization, Payment Intent, Collection Intent, Compensation, Refund, settlement, payout, Royalty, Workspace, or seller identity. No separate Checkout, Payment Attempt, Payment Method, Payment Provider, Authorization, Payment Intent, Collection Intent, Compensation, Idempotency Key, Refund, Refund Component, or Refund Allocation Plan core entity is introduced in DRAFT 0.4.

Creastrix is the single buyer-facing contractual seller-of-record and merchant-of-record for every MVP Order. This is a current commerce policy rather than a permanent invariant of the whole platform. Its legal, tax, invoicing, consumer-protection, VAT, acquiring, KYC, AML, and PSP feasibility requires production legal and compliance validation.

The core domain does not assume that Creastrix directly performs regulated custody or onward transfer of third-party customer funds. Actual payment collection and future beneficiary payouts are expected to use appropriately licensed payment infrastructure; exact provider architecture is implementation and integration work.

External authorization and local Order confirmation cannot form one cross-system ACID transaction. Correctness depends on durable command identity before the provider call, safe provider economic idempotency or reconciliation, one local atomic Order confirmation, durable one-time consumption correlation, and durable compensation when local confirmation does not commit.

Implementation validation for this boundary requires mechanisms equivalent to durable unique confirmation and provider submission identities, unique one-time consumption, atomic consumption correlation inside Order confirmation, durable compensation command identity, reconciliation of unknown external and local outcomes, and concurrency and crash tests before and after local commit. Exact tables, constraints, locks, isolation levels, and provider API mechanics remain implementation concerns.

External refund execution and local accepted-refund recognition likewise cannot form one cross-system ACID transaction. The durable pre-provider refund commitment is workflow or integration persistence and does not by itself introduce a new core domain entity.

Order collection eligibility is an operational domain condition derived from Order and Order Item history rather than a Payment lifecycle state. Closing an Order to collection does not rewrite its Payment history, immutable payable snapshot, or Order lifecycle.

Technical concurrency controls, provider adapters, webhook storage, and exact retry and timeout durations remain implementation or future policy concerns. Consumer refund policy, disputes, chargebacks, settlement, accounting treatment, and exact retention duration also remain future legal and domain work.

Payment and its accepted financial history cannot be destructively deleted. Exact retention duration must follow future legal, accounting, privacy, and PSP requirements.

---

Status: DRAFT

Version: 0.4
