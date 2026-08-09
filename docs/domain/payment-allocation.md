# Payment Allocation

## Purpose

A Payment Allocation represents an immutable accounting attribution or reversal of accepted captured buyer funds for exactly one Payment.

It explains captured money without asserting that payout, bank settlement, Royalty accrual, seller identity, or accounting profit recognition has occurred.

## Responsibilities

A Payment Allocation is responsible for:

- representing either an ORIGINAL captured-funds attribution or a REVERSAL of an original attribution;
- preserving one immutable Payment relationship and currency;
- preserving exactly one immutable charge subject and economic purpose;
- preserving an immutable beneficiary context when its purpose establishes an economic attribution to a party;
- conserving every captured currency minor unit across the complete ORIGINAL Allocation set;
- preserving append-only reversal history for accepted refunds;
- keeping captured-funds attribution separate from payout, Royalty, settlement, and profit accounting.

## Relationships

A Payment Allocation:

- belongs to exactly one Payment and derives its Order through that Payment;
- uses the same currency as its Payment;
- has exactly one immutable charge subject;
- has exactly one immutable economic purpose;
- may identify one immutable beneficiary context when required by its purpose;
- references exactly one ORIGINAL Payment Allocation and exactly one accepted refund-event identity belonging to the same Payment when its kind is REVERSAL.

## Business Rules

- A Payment Allocation has exactly one immutable kind: ORIGINAL or REVERSAL.
- An ORIGINAL Allocation has a positive amount, is created only when capture is accepted, and participates in captured-amount conservation.
- A REVERSAL Allocation has a positive amount representing reversal effect, references exactly one ORIGINAL Allocation of the same Payment and exactly one immutable accepted refund-event identity of that Payment, and is created because of that accepted refund fact without changing the original Allocation.
- Negative ORIGINAL Allocations are not permitted.
- An ORIGINAL Allocation has exactly one charge subject: either one Order Item or one supported Order-level charge context.
- The only supported Order-level charge contexts in MVP are SHIPPING and TAX. No Order Charge entity exists.
- A single Allocation never spans multiple Order Items. One Order Item may have several ORIGINAL Allocations.
- A REVERSAL preserves the charge subject, economic purpose, beneficiary context, and currency of the ORIGINAL Allocation it reverses.
- The only ORIGINAL Allocation purposes in MVP are ITEM_PROCEEDS, MANUFACTURING_COMPENSATION, SHIPPING_CHARGE, and TAX.
- PLATFORM_REVENUE, ROYALTY, DISCOUNT, and PROCESSOR_FEE are not Payment Allocation purposes in DRAFT 0.1.
- ITEM_PROCEEDS uses one Order Item as its charge subject and represents the net captured merchandise contribution attributed to merchant-side item proceeds after confirmed discount attribution and any explicitly separated manufacturer compensation.
- ITEM_PROCEEDS identifies the Creastrix PLATFORM beneficiary context in MVP. It is not automatically accounting profit and may remain subject to future liabilities such as Royalty without rewriting the original Allocation.
- For a ready-made Order Item, its full net merchandise contribution is allocated as ITEM_PROCEEDS to the Creastrix PLATFORM context.
- For a made-to-order Order Item, its net merchandise contribution is divided between explicit MANUFACTURING_COMPENSATION and the remaining ITEM_PROCEEDS to the Creastrix PLATFORM context.
- MANUFACTURING_COMPENSATION uses one made-to-order Order Item as its charge subject and identifies exactly one USER or ORGANIZATION beneficiary context.
- The MANUFACTURING_COMPENSATION beneficiary is the immutable confirmation-time Profile Holder context of the assigned Manufacturer Profile, but Manufacturer Profile assignment alone never creates that economic right.
- Manufacturer compensation amount, beneficiary context, and the source or basis of the confirmed compensation terms must have been established explicitly in accepted commercial and manufacturing terms before Order confirmation.
- Manufacturer Profile itself is never the Allocation beneficiary or payee.
- SHIPPING_CHARGE uses the Order-level SHIPPING subject, identifies the Creastrix PLATFORM beneficiary context, and equals the confirmed buyer-facing Order shipping charge.
- Actual Shipment count and actual carrier cost never rewrite a SHIPPING_CHARGE Allocation. Shipment remains without buyer-facing monetary responsibility, and carrier cost remains a future merchant or logistics expense.
- TAX uses the Order-level TAX subject and has no external economic beneficiary in Payment Allocation. It represents the captured tax portion within the Creastrix merchant tax-liability context and is not platform revenue.
- Aggregate Order tax is sufficient for this architecture version. Exact VAT jurisdiction, item-level tax breakdown, invoicing, and refund-tax rules remain production legal and compliance work.
- Aggregate Order discount is not a positive charge subject and never creates a positive ORIGINAL Allocation.
- Before ORIGINAL Allocations are created, the confirmed aggregate Order discount is attributed deterministically across Order Items using their authoritative line merchandise amounts as the proportional basis.
- Proportional discount attribution uses largest-remainder distribution in currency minor units. Equal fractional remainders are resolved by canonical ascending immutable Order Item identity, so the same confirmed Order data always produces the same attribution and every minor unit is attributed exactly once.
- Every confirmed item discount share is non-negative and does not exceed its authoritative line merchandise amount. The complete sum of confirmed item discount shares equals the Order's aggregate confirmed discount total.
- The immutable net item merchandise contribution equals authoritative line merchandise amount minus confirmed item discount share, is always non-negative, and is never recomputed from unit merchandise price multiplied by quantity.
- Buyer-facing Order discount is borne by Creastrix merchant economics by default and does not reduce an already confirmed Manufacturer compensation amount in MVP.
- Order confirmation must reject an economic configuration where a made-to-order net item merchandise contribution after discount attribution cannot support its explicit confirmed Manufacturer compensation amount. No subsidy or external funding model exists in DRAFT 0.1.
- A zero net item merchandise contribution creates no positive ITEM_PROCEEDS Allocation for that Item. Any other Allocation for the Item still requires its own valid purpose and positive amount.
- When a purpose has a beneficiary, the beneficiary type is PLATFORM, USER, or ORGANIZATION.
- PLATFORM identifies the Creastrix platform party and does not require a separate entity.
- USER and ORGANIZATION beneficiary contexts preserve the applicable live entity reference and an immutable historical identity snapshot.
- Beneficiary type and historical snapshot never change after Allocation creation. Later deactivation or profile, User, or Organization changes do not reassign historical economic attribution.
- Beneficiary context identifies the party associated with the economic purpose of an Allocation. It does not by itself establish that an amount has been earned, become due or payable, become eligible for payout or withdrawal, settled, or transferred.
- A MANUFACTURING_COMPENSATION Allocation records an immutable captured-funds attribution reserved toward the confirmed Manufacturer compensation basis. It does not by itself mean that the Manufacturer has earned the amount, that the amount is currently due, payable, or withdrawable, that payout eligibility exists, or that payout occurred.
- ORIGINAL Payment Allocations are created only when Payment capture is accepted. A pre-capture allocation plan may be computed for validation but is not a Payment Allocation record.
- Recognition of Payment as CAPTURED and creation of its complete ORIGINAL Allocation set are one local atomic domain operation.
- For every CAPTURED Payment, the sum of ORIGINAL Allocation amounts equals the Payment captured amount.
- Every ORIGINAL Allocation amount is positive, and every Allocation currency equals Payment currency.
- A confirmed zero-valued monetary component does not create an ORIGINAL Allocation.
- Every captured currency minor unit is allocated exactly once. No amount may be unallocated, allocated twice, or silently treated as Creastrix income.
- Processor or acquirer fees do not reduce buyer captured amount and remain outside the Payment Allocation conservation set.
- An accepted refund never changes an ORIGINAL Allocation. Platform recognition atomically preserves the append-only accepted refund fact and its complete set of REVERSAL Allocations in one local domain operation.
- Every REVERSAL references exactly one ORIGINAL Allocation and exactly one accepted refund-event identity belonging to the same Payment. A REVERSAL can never be correlated with an accepted refund fact of another Payment.
- Every reversal amount is positive, and the sum of REVERSAL amounts correlated with one accepted refund-event identity equals that accepted refund amount.
- Cumulative reversal against an ORIGINAL Allocation must never exceed its original amount.
- If a provider refund succeeds externally but local recognition fails, retry or reconciliation must recognize the same refund event exactly once. The same accepted provider refund event must not create duplicate refund or REVERSAL facts.
- Selection of the ORIGINAL Allocations reversed for an item-specific or Order-level refund follows an explicitly approved refund policy. No additional refund allocation rule is invented by this specification.
- Payment Allocation is internal financial information. Buyer Payment access does not automatically expose Allocations, manufacturer compensation, or merchant-side item proceeds.

## Invariants

- A Payment Allocation always has one stable identity.
- A Payment Allocation always belongs to exactly one Payment.
- A Payment Allocation always has exactly one immutable kind: ORIGINAL or REVERSAL.
- A Payment Allocation always uses the currency of its Payment.
- Every Allocation always has exactly one immutable charge subject and one immutable economic purpose.
- An ORIGINAL Allocation always has a positive amount and is created only for a CAPTURED Payment.
- The complete ORIGINAL Allocation set of a CAPTURED Payment always equals that Payment's captured amount.
- Every captured currency minor unit is attributed exactly once, with no unexplained residual.
- Every confirmed item discount share is between zero and its authoritative line merchandise amount, the sum of item discount shares equals the Order's aggregate confirmed discount total, and every net item merchandise contribution is non-negative.
- A MANUFACTURING_COMPENSATION Allocation always uses a made-to-order Order Item subject and an explicitly confirmed USER or ORGANIZATION beneficiary context.
- A MANUFACTURING_COMPENSATION Allocation amount never exceeds the made-to-order Order Item's net item merchandise contribution.
- Manufacturer Profile assignment alone never creates MANUFACTURING_COMPENSATION.
- ITEM_PROCEEDS and SHIPPING_CHARGE use the Creastrix PLATFORM beneficiary context in MVP.
- TAX never represents platform revenue.
- A REVERSAL always references exactly one ORIGINAL Allocation and exactly one accepted refund-event identity of the same Payment and never changes the ORIGINAL Allocation.
- REVERSAL Allocations correlated with one accepted refund-event identity always sum to that accepted refund amount.
- Cumulative reversal against an ORIGINAL Allocation never exceeds the original amount.
- Original attribution and accepted reversal history are never destructively deleted or rewritten.

## Notes

Payment Allocation is not Payment, buyer-facing price presentation, payout, bank settlement, Royalty, seller identity, payment account ownership, or a profit ledger.

ITEM_PROCEEDS is intentionally not named PLATFORM_REVENUE. Future accounting liabilities, including Royalty, may arise from merchant-side proceeds without changing how captured buyer money was originally attributed.

Royalty remains a separate future domain. Order Item royalty snapshots provide its historical basis, and future Royalty rules will independently determine beneficiary, accrual, earning or release conditions, and reversal. Future Royalty records may reference Payment, Allocation, and refund facts without rewriting ORIGINAL Allocations.

Actual release and transfer to a Manufacturer, designer, or another beneficiary belongs to future Payout and settlement policy. Potential prerequisites may later include fulfillment state, delivery, cancellation or refund state, dispute windows, KYC, reserves, and payout policy, but this specification does not decide them. Payout may aggregate attributed amounts, use licensed PSP payout rails, and fail or retry independently from Payment Allocation.

No Beneficiary, Order Charge, Tax, Discount, Coupon, Refund, or Payout entity is introduced by this specification. Exact refund selection policy, tax treatment, legal accounting classification, beneficiary visibility, settlement, disputes, chargebacks, reserves, and retention duration remain future work.

Allocation and reversal history with accepted economic significance cannot be destructively deleted. Exact retention duration must follow future legal, accounting, privacy, and PSP requirements.

---

Status: DRAFT

Version: 0.1
