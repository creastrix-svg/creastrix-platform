# Royalty

## Purpose

A Royalty represents one immutable original royalty accrual recognized for one confirmed Revision-based Order Item and one User or Organization beneficiary under the royalty terms and rights context frozen at Order confirmation, after the applicable buyer Payment capture has been accepted.

The accrual is a Creastrix-recognized contingent economic royalty amount. It does not by itself establish that the amount has been legally or accountingly classified as unconditional debt, earned, become due, payable, withdrawable, payout-eligible, settled, or transferred.

## Responsibilities

A Royalty is responsible for:

- preserving its immutable qualifying Order Item and originating CAPTURED Payment relationships;
- preserving one immutable User or Organization beneficiary and historical beneficiary context;
- preserving the immutable original amount, currency, and confirmation-time calculation and rights context;
- preserving append-only Royalty reversal facts correlated with accepted Payment refund events;
- exposing the outstanding Royalty amount derived from the immutable original and cumulative reversal amounts;
- keeping royalty accrual and reversal history separate from Payment Allocation, earning, Payout, settlement, and transfer.

## Relationships

A Royalty:

- belongs to exactly one confirmed Revision-based Order Item;
- belongs to exactly one originating CAPTURED Payment of that Order Item's Order;
- identifies exactly one immutable beneficiary, which is either one User or one Organization;
- preserves zero or more embedded reversal facts;
- has no direct Workspace, Designer Profile, Manufacturer Profile, or Payment Allocation relationship.

## Business Rules

- A Royalty is recognized only for a Revision-based Order Item whose immutable confirmation snapshot contains a calculated original royalty amount greater than zero and exactly one valid User or Organization beneficiary.
- A qualifying Order Item creates at most one Royalty in MVP. A Ready-Made Product Order Item never creates a Royalty in the current MVP.
- A zero royalty rate or a calculated amount that rounds to zero remains preserved in the Order Item royalty snapshot and does not create a zero-value Royalty.
- The original Royalty amount is copied from the immutable calculated amount preserved by the Order Item at confirmation and is never recalculated from current Listing, Designer Profile, Workspace, Project, Revision, Personalization, Manufacturer Profile, or other mutable state.
- Order confirmation has already validated that confirmed Manufacturer compensation plus calculated original Royalty amount does not exceed net item merchandise contribution. Royalty recognition does not repeat or change that frozen commercial calculation.
- Royalty currency always equals the currencies of its Order Item, Order, and originating Payment. Foreign exchange inside Royalty is unsupported in MVP.
- Royalty recognition requires an accepted full Payment capture for the Order. The accepted capture is a durable, idempotent trigger, but Royalty recognition remains a separate domain operation from the atomic recognition of Payment as CAPTURED and creation of its complete ORIGINAL Payment Allocation set.
- A temporary failure to recognize Royalty never changes the meaning of an already accepted CAPTURED Payment. Retry and reconciliation must eventually recognize exactly one Royalty for every qualifying captured Order Item without creating duplicates.
- The originating Payment relationship is immutable. Current MVP Payment rules make the full successful capture origin unambiguous for every qualifying Order Item.
- The Royalty beneficiary comes only from the explicitly validated royalty-rights context frozen in the Order Item. Project Created By, Revision Created By, Listing Created By, Workspace owner, Project Effective Business Rights Holder, Buyer, Manufacturer Profile, and Designer Profile do not automatically determine the beneficiary.
- Designer Profile represents publication eligibility rather than monetary recipient identity. Later Designer Profile verification, suspension, deletion, or other status changes never revalidate or rewrite a confirmed Order Item royalty snapshot or an existing Royalty.
- Beneficiary type, live User or Organization reference, immutable historical identity snapshot, and royalty-right source or basis never change after Royalty recognition. Later deactivation, renaming, Workspace change, or Listing change does not reassign historical Royalty.
- Royalty has no stored lifecycle state. Unreversed, partially reversed, and fully reversed are derived monetary conditions based on original amount minus cumulative accepted reversal amount.
- Royalty existence and outstanding amount do not by themselves mean that the beneficiary has earned the amount or that it is due, payable, withdrawable, payout-eligible, settled, or transferred.
- Only an authorized platform financial workflow may recognize Royalty from accepted capture or recognize a Royalty reversal from an accepted refund and item-level refund attribution.
- A beneficiary User may view the User's own Royalty information through User authorization. Royalty of an Organization beneficiary may be viewed by a currently authorized Organization actor; until future explicit delegation exists, current general Organization authority requires an ACTIVE Organization Membership with the role OWNER.
- Platform-authorized finance or administration workflows may view Royalty. Buyer status, Manufacturer participation, Workspace ownership, Workspace Membership, and the PROJECTS, READY_MADE_PRODUCTS, or LISTINGS scopes do not expose Royalty or grant mutation authority.
- A Royalty preserves zero or more append-only embedded reversal facts. Each positive reversal fact preserves exactly one immutable accepted refund-event identity of the originating Payment, the refunded royalty-basis amount attributed to the Order Item for that event, a positive Royalty reversal amount, accepted timestamp, and appropriate reason or evidence context.
- For one Royalty and one accepted refund-event identity, at most one positive Royalty reversal fact may exist. Replaying or reconciling the same refund event must not reverse the Royalty twice.
- For one accepted refund event, refunded royalty-basis amount is determined from accepted Payment Allocation REVERSAL facts for the same Order Item whose referenced ORIGINAL purposes are ITEM_PROCEEDS or MANUFACTURING_COMPENSATION. SHIPPING_CHARGE and TAX are excluded.
- Royalty does not require permanent direct relationships to the Payment Allocations used as refund attribution evidence and never rewrites ORIGINAL or REVERSAL Payment Allocations.
- Cumulative refunded royalty-basis amount is the sum of refunded royalty-basis amounts attributed to the Order Item across accepted refund events and must remain between zero and the immutable original Royalty basis amount.
- Target cumulative Royalty reversal equals the lesser of the original Royalty amount and the HALF_UP_MINOR_UNIT_V1 result of cumulative refunded royalty-basis amount multiplied by the immutable rate basis points and divided by 10,000.
- A new reversal amount equals target cumulative Royalty reversal minus already recorded cumulative Royalty reversal. A result of zero creates no reversal fact.
- Order Item cancellation alone never reverses Royalty. Cancellation before capture creates no Royalty; cancellation after capture leaves Royalty unchanged until an accepted refund produces applicable refunded royalty basis.
- A Royalty and its accepted economic history cannot be destructively deleted.

## Invariants

- A Royalty always has one stable identity.
- A Royalty always belongs to exactly one Revision-based Order Item and exactly one originating CAPTURED Payment of that Item's Order.
- A qualifying Order Item never has more than one Royalty in MVP.
- A Royalty always has exactly one immutable beneficiary of type USER or ORGANIZATION.
- A Royalty original amount is always positive, immutable, and equal to the calculated original royalty amount frozen in its Order Item snapshot.
- A Royalty always uses the currency of its Order Item, Order, and originating Payment.
- A Royalty never has a stored lifecycle state.
- Every Royalty reversal amount is positive and correlates to exactly one accepted refund-event identity of the originating Payment.
- The pair of Royalty and accepted refund-event identity never produces more than one positive reversal fact.
- Cumulative refunded royalty-basis amount never exceeds the immutable original royalty-basis amount.
- Cumulative Royalty reversal never exceeds the original Royalty amount.
- Outstanding Royalty amount is never negative.
- Original Royalty data and accepted reversal history are never destructively deleted or rewritten.
- Royalty never mutates Payment Allocation or establishes payout, settlement, or transfer.

## Notes

Royalty is not Payment, Payment Allocation, Payout, Designer Profile, Listing, Order Item, beneficiary payout destination, accounting entry, or settlement record. No Royalty Reversal, Royalty Beneficiary, Royalty Terms, Royalty Share, Contributor, or Payout entity is introduced by this specification.

The current percentage-only royalty calculation is performed and frozen at Order confirmation by Order Item using NET_ITEM_MERCHANDISE_CONTRIBUTION_V1 and HALF_UP_MINOR_UNIT_V1. Royalty recognition later copies that amount after accepted capture rather than recalculating it.

Payment CAPTURED and the complete ORIGINAL Payment Allocation set retain their existing atomic conservation boundary. Royalty recognition is separately durable, idempotent, and reconcilable. No ROYALTY Payment Allocation purpose is introduced, and ITEM_PROCEEDS remains unchanged when Royalty is recognized.

Exact refund selection policy remains separate production and business work. Royalty consumes the accepted item-level refund attribution expressed by Payment Allocation REVERSAL facts without owning or redefining that selection policy.

Future earning and release rules may consider fulfillment, delivery, cancellation and refund state, dispute windows, compliance, and contractual rules. Future Payout may consider outstanding Royalty, separate earning or release eligibility, KYC, reserves, and payout destination, but none of those conditions are decided here.

Chargebacks, disputes, recovery after a future payout, multiple beneficiaries, layered royalty rights, ready-made royalties, exact rights-evidence storage, accounting classification, tax treatment, retention duration, and pseudonymization remain future work.

---

Status: DRAFT

Version: 0.1
