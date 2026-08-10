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
- applying the versioned deterministic Refund Allocation Selection Policy to immutable accepted refund components;
- exposing authoritative cumulative reversal targets and current outstanding source amounts;
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
- For one Payment, there is at most one positive ORIGINAL Payment Allocation for one exact pair of charge subject and economic purpose.
- Therefore one Order Item has at most one ORIGINAL ITEM_PROCEEDS Allocation, one made-to-order Order Item has at most one ORIGINAL MANUFACTURING_COMPENSATION Allocation, and a Payment has at most one ORIGINAL SHIPPING_CHARGE for its Order-level SHIPPING subject and at most one ORIGINAL TAX for its Order-level TAX subject.
- A REVERSAL preserves the charge subject, economic purpose, beneficiary context, and currency of the ORIGINAL Allocation it reverses.
- The only ORIGINAL Allocation purposes in MVP are ITEM_PROCEEDS, MANUFACTURING_COMPENSATION, SHIPPING_CHARGE, and TAX.
- PLATFORM_REVENUE, ROYALTY, DISCOUNT, and PROCESSOR_FEE are not Payment Allocation purposes in DRAFT 0.2.
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
- Order confirmation must reject an economic configuration where a made-to-order net item merchandise contribution after discount attribution cannot support its explicit confirmed Manufacturer compensation and calculated original Royalty amounts. No subsidy or external funding model exists in DRAFT 0.2.
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
- The current MVP deterministic Refund Allocation Selection Policy is `PLATFORM_FIRST_WITH_ROYALTY_NO_SUBSIDY_SAFETY_FLOOR_V1`.
- The policy is versioned, reproducible, currency-minor-unit exact, idempotent, cumulative across sequential refunds, and independent from current Listing, Profile, Manufacturer Profile, Workspace, or other mutable state.
- No economically unscoped accepted refund exists in MVP. The policy consumes the immutable normalized refund component snapshot preserved by Payment.
- The only buyer-facing refund component types are ITEM_MERCHANDISE, SHIPPING, and TAX. ITEM_MERCHANDISE references exactly one Order Item, SHIPPING references the Order-level SHIPPING subject, and TAX references the Order-level TAX subject.
- Every refund component has a positive amount, uses the Payment currency, identifies its exact immutable economic subject, respects remaining reversible capacity, and participates exactly once in refund-amount conservation.
- Within one refund instruction, duplicate component keys are normalized so there is at most one component for each exact pair of ITEM_MERCHANDISE and Order Item, at most one SHIPPING component for the Order, and at most one TAX component for the Order.
- DISCOUNT, ITEM_PROCEEDS, MANUFACTURING_COMPENSATION, ROYALTY, and PROCESSOR_FEE are not buyer-facing refund component types.
- The sum of all component amounts in one accepted refund equals both the accepted refund amount and the sum of its complete correlated REVERSAL Allocation set.
- For an Order Item `i`, immutable net item merchandise contribution `N_i` equals its immutable authoritative line merchandise amount minus its immutable confirmed item discount share.
- Cumulative accepted ITEM_MERCHANDISE refund `F_i` for one Order Item must satisfy `0 <= F_i <= N_i`. Accepted remaining merchandise capacity is `N_i - F_i`.
- Before a new provider refund submission, available ITEM_MERCHANDISE capacity additionally subtracts the amount for that Item reserved by the current active or economically unknown committed refund instruction. A new component must be greater than zero and no greater than that available capacity.
- Discount is not refundable because it was never captured and has no positive ORIGINAL Allocation. Maximum full ITEM_MERCHANDISE refund for an Item is its net item merchandise contribution rather than its undiscounted line merchandise amount.
- For a Ready-Made Product Order Item, the complete ORIGINAL item economic amount is ITEM_PROCEEDS equal to `N`. For cumulative accepted ITEM_MERCHANDISE refund `F`, authoritative cumulative ITEM_PROCEEDS reversal target is `P_rev(F) = F`; no MANUFACTURING_COMPENSATION Allocation exists or is reversed.
- For a made-to-order Revision Order Item, all policy monetary variables use currency minor units: `N` is immutable net item merchandise contribution, `M0` is the ORIGINAL MANUFACTURING_COMPENSATION amount, `P0` is the ORIGINAL ITEM_PROCEEDS amount and equals `N - M0`, and `F` is cumulative accepted ITEM_MERCHANDISE refund for that exact Item.
- For that made-to-order Item, `R0` is the immutable calculated original Royalty amount preserved in the Order Item confirmation-time royalty snapshot and `b` is the immutable royalty rate in basis points from that snapshot. `R0` may be zero and never depends on whether the separately reconciled Royalty entity has already been recognized.
- Existing confirmation invariants require `M0 + R0 <= N`; therefore `P0 >= R0`.
- For cumulative refund `F`, authoritative cumulative Royalty reversal target for refund-allocation calculation is `Q(F) = min(R0, HALF_UP_MINOR_UNIT_V1(F * b / 10,000))`, and authoritative remaining Royalty economic amount is `Rout(F) = R0 - Q(F)`.
- The refund-allocation Royalty calculation derives from immutable Order Item royalty terms and accepted refund economics. It does not depend on asynchronous Royalty entity recognition or reconciliation; the Royalty domain independently reconciles its actual reversal facts to the same economics.
- Under `PLATFORM_FIRST_WITH_ROYALTY_NO_SUBSIDY_SAFETY_FLOOR_V1`, authoritative cumulative ITEM_PROCEEDS reversal target is `P_rev(F) = min(F, P0 - Rout(F))` and authoritative cumulative MANUFACTURING_COMPENSATION reversal target is `M_rev(F) = F - P_rev(F)`.
- Because `P0 >= R0` and `Rout(F) <= R0`, the safety floor `P0 - Rout(F)` is non-negative. All operations after HALF_UP Royalty calculation use integer currency minor units.
- The policy reverses ITEM_PROCEEDS as far as possible while retaining enough remaining ITEM_PROCEEDS to support authoritative remaining Royalty. MANUFACTURING_COMPENSATION is reversed only when required by that safety floor. Unconditional platform-first and proportional splitting are not used.
- At every cumulative accepted refund prefix for a made-to-order Item before Payout eligibility, `(M0 - M_rev(F)) + Rout(F) <= N - F`. Equivalently, remaining ITEM_PROCEEDS is at least `Rout(F)`.
- The no-subsidy invariant uses authoritative Royalty outstanding derived from immutable Order Item terms and cumulative accepted ITEM_MERCHANDISE refund rather than potentially lagging recorded Royalty reconciliation.
- Applicable accepted refund events for one Order Item use the same canonical order as Royalty: immutable platform-accepted timestamp ascending, with immutable refund-event identity as the stable tie-breaker.
- For canonical event `E`, `F_before(E)` is cumulative accepted ITEM_MERCHANDISE refund for that Item from canonically earlier accepted events, and `F_after(E)` equals `F_before(E)` plus the ITEM_MERCHANDISE component amount of `E`.
- Event-specific deltas are `delta_P(E) = P_rev(F_after(E)) - P_rev(F_before(E))` and, for a made-to-order Item, `delta_M(E) = M_rev(F_after(E)) - M_rev(F_before(E))`.
- A positive `delta_P(E)` creates one REVERSAL against the exact ITEM_PROCEEDS ORIGINAL, and a positive `delta_M(E)` creates one REVERSAL against the exact MANUFACTURING_COMPENSATION ORIGINAL. A zero delta creates no REVERSAL Allocation.
- For every ITEM_MERCHANDISE component, `delta_P(E) + delta_M(E)` equals its amount, with `delta_M(E)` treated as zero for a ready-made Item. Cumulative targets make the final internal result independent from whether the same cumulative refund arrived through one or several events.
- A SHIPPING component reverses only the Order-level SHIPPING_CHARGE ORIGINAL, may be partial, and cannot exceed its remaining unreversed capacity. It never reverses Item merchandise, MANUFACTURING_COMPENSATION, Royalty, or TAX.
- A TAX component reverses only the Order-level TAX ORIGINAL, may be partial when its explicit amount is supplied by an approved authoritative tax or refund workflow, and cannot exceed remaining unreversed TAX capacity. This policy does not calculate VAT or jurisdictional tax refund.
- ITEM_MERCHANDISE never automatically creates a SHIPPING or TAX refund. Shipping and tax require their own explicit components.
- One accepted refund event may contain components for multiple exact Items together with SHIPPING and TAX. Every component is validated and attributed independently, and no amount is distributed ambiguously across Items or Manufacturer beneficiaries.
- A full remaining Order refund may exhaust every Item's remaining net merchandise capacity together with remaining SHIPPING_CHARGE and TAX. At complete full refund every ORIGINAL Allocation is fully reversed and cumulative accepted refund equals captured amount while Payment remains CAPTURED.
- Refund allocation selection never mutates Order Item lifecycle, releases stock, or implies cancellation. Refund eligibility may depend on separate commerce workflow, but accepted allocation depends only on immutable instruction, immutable monetary snapshots, append-only accepted financial history, and frozen policy version.
- An accepted refund never changes an ORIGINAL Allocation. Platform recognition atomically preserves the append-only accepted refund fact, its immutable accepted component snapshot, and its complete set of positive REVERSAL Allocations in one local domain operation.
- Every REVERSAL references exactly one ORIGINAL Allocation and exactly one accepted refund-event identity belonging to the same Payment. A REVERSAL can never be correlated with an accepted refund fact of another Payment.
- Every reversal amount is positive, and the sum of REVERSAL amounts correlated with one accepted refund-event identity equals that accepted refund amount.
- For one accepted refund-event identity and one ORIGINAL Payment Allocation, there is at most one positive REVERSAL Allocation. The pair never produces duplicate REVERSAL records.
- Cumulative reversal against an ORIGINAL Allocation must never exceed its original amount.
- If a provider refund succeeds externally but local recognition fails, retry or reconciliation must use the same frozen submission identity, component instruction, and policy version and recognize the same refund event exactly once. The same provider refund event must not create duplicate refund or REVERSAL facts or select different Allocations from newer state.
- Accepted refund component snapshots, ORIGINAL Allocations, and REVERSAL Allocations cannot be destructively rewritten to change economic attribution. Correction requires future explicit adjustment or correction architecture rather than mutation.
- Payment Allocation is internal financial information. Buyer Payment access does not automatically expose Allocations, manufacturer compensation, or merchant-side item proceeds.

## Invariants

- A Payment Allocation always has one stable identity.
- A Payment Allocation always belongs to exactly one Payment.
- A Payment Allocation always has exactly one immutable kind: ORIGINAL or REVERSAL.
- A Payment Allocation always uses the currency of its Payment.
- Every Allocation always has exactly one immutable charge subject and one immutable economic purpose.
- An ORIGINAL Allocation always has a positive amount and is created only for a CAPTURED Payment.
- For one Payment, the pair of exact charge subject and economic purpose never has more than one positive ORIGINAL Allocation.
- The complete ORIGINAL Allocation set of a CAPTURED Payment always equals that Payment's captured amount.
- Every captured currency minor unit is attributed exactly once, with no unexplained residual.
- Every confirmed item discount share is between zero and its authoritative line merchandise amount, the sum of item discount shares equals the Order's aggregate confirmed discount total, and every net item merchandise contribution is non-negative.
- A MANUFACTURING_COMPENSATION Allocation always uses a made-to-order Order Item subject and an explicitly confirmed USER or ORGANIZATION beneficiary context.
- A MANUFACTURING_COMPENSATION Allocation amount never exceeds the made-to-order Order Item's net item merchandise contribution.
- Manufacturer Profile assignment alone never creates MANUFACTURING_COMPENSATION.
- ITEM_PROCEEDS and SHIPPING_CHARGE use the Creastrix PLATFORM beneficiary context in MVP.
- TAX never represents platform revenue.
- Every accepted refund uses `PLATFORM_FIRST_WITH_ROYALTY_NO_SUBSIDY_SAFETY_FLOOR_V1` while that policy is the current MVP version and has only normalized ITEM_MERCHANDISE, SHIPPING, and TAX components.
- Cumulative accepted ITEM_MERCHANDISE refund for one Order Item never exceeds its immutable net item merchandise contribution.
- A ready-made Item's cumulative ITEM_PROCEEDS reversal target always equals its cumulative accepted ITEM_MERCHANDISE refund.
- For every made-to-order Item and every cumulative accepted refund prefix, cumulative ITEM_PROCEEDS and MANUFACTURING_COMPENSATION reversal targets follow the approved formulas, sum to cumulative ITEM_MERCHANDISE refund, and preserve the post-refund no-subsidy invariant.
- A REVERSAL always references exactly one ORIGINAL Allocation and exactly one accepted refund-event identity of the same Payment and never changes the ORIGINAL Allocation.
- The pair of one refund-event identity and one ORIGINAL Allocation never has more than one positive REVERSAL Allocation.
- REVERSAL Allocations correlated with one accepted refund-event identity always sum to that accepted refund amount.
- Cumulative reversal against an ORIGINAL Allocation never exceeds the original amount.
- Original attribution and accepted reversal history are never destructively deleted or rewritten.

## Notes

Payment Allocation is not Payment, buyer-facing price presentation, payout, bank settlement, Royalty, seller identity, payment account ownership, or a profit ledger.

ITEM_PROCEEDS is intentionally not named PLATFORM_REVENUE. Accounting liabilities, including current Royalty accruals, may arise from merchant-side proceeds without changing how captured buyer money was originally attributed.

Royalty remains a separate active domain specification. Order Item royalty snapshots provide its immutable calculation basis, Payment Allocation REVERSALS provide accepted item-level refund attribution, and Royalty independently records and reconciles its reversal facts without rewriting any Allocation.

Actual release and transfer to a Manufacturer, designer, or another beneficiary belongs to the separate active Payout domain and future settlement policy. Payment Allocation provides source attribution and current outstanding amounts but does not decide earning, release, transfer, provider execution, or recovery.

No Beneficiary, Order Charge, Tax, Discount, Coupon, Refund, Refund Component, Refund Allocation Plan, Hold, Balance, Recovery, or Adjustment entity is introduced by this specification. Refund components are immutable embedded values of the accepted Payment refund fact, while the durable pre-provider commitment remains workflow or integration persistence. Exact consumer eligibility, jurisdictional tax treatment, legal accounting classification, settlement, disputes, chargebacks, correction, recovery, and retention duration remain future production work.

Allocation and reversal history with accepted economic significance cannot be destructively deleted. Exact retention duration must follow future legal, accounting, privacy, and PSP requirements.

---

Status: DRAFT

Version: 0.2
