# Payout

## Purpose

A Payout represents one durable outbound transfer execution attempt initiated by Creastrix for exactly one User or Organization beneficiary.

It preserves the immutable amount, currency, beneficiary, non-sensitive destination context, source portions, and provider-correlated history of that attempt without becoming a balance, settlement ledger, accounting entry, legal earning determination, or proof that the transferred value can never be returned.

## Responsibilities

A Payout is responsible for:

- preserving one immutable User or Organization beneficiary and historical beneficiary identity context;
- preserving one positive immutable transfer amount and one immutable currency;
- preserving one immutable non-sensitive destination snapshot and provider routing context;
- preserving one or more immutable embedded source portions;
- reserving every included source amount while the Payout is active;
- managing the PENDING, PROCESSING, SUCCEEDED, FAILED, and CANCELLED transfer-attempt lifecycle;
- preserving accepted provider correlation and outcome evidence idempotently;
- releasing source reservations only when the attempt reaches a terminal no-transfer outcome;
- preserving successful transfer history without rewriting it after later source reversals or provider returns.

## Relationships

A Payout:

- identifies exactly one immutable beneficiary, which is either one User or one Organization;
- contains one or more immutable embedded source portions;
- references exactly one ORIGINAL Payment Allocation with the purpose MANUFACTURING_COMPENSATION for each MANUFACTURING_COMPENSATION_ALLOCATION source portion;
- references exactly one Royalty for each ROYALTY source portion;
- has no direct Workspace, Workspace Membership, Organization Membership, Designer Profile, Manufacturer Profile, Project, Revision, Listing, Order, or Order Item relationship;
- may preserve provider execution correlation and accepted provider evidence without introducing a Provider entity.

## Business Rules

- A Payout beneficiary must be exactly one User or one Organization, never both and never the Creastrix PLATFORM context.
- The beneficiary is derived only from every included source: the beneficiary context of an ORIGINAL MANUFACTURING_COMPENSATION Payment Allocation or the beneficiary of a Royalty.
- Manufacturer Profile, Designer Profile, Workspace owner, Workspace Membership, Organization Membership, Project Effective Business Rights Holder, Created By, publication context, and an Order Item alone never determine the Payout beneficiary.
- A Payout preserves the beneficiary type, live User or Organization reference, and immutable historical identity snapshot. Beneficiary matching uses stable identity and type rather than mutable display text.
- Later User or Organization renaming, deactivation, membership change, or profile status change never rewrites the Payout beneficiary context.
- A Payout has exactly one positive immutable amount and exactly one immutable currency.
- A Payout contains one or more immutable embedded source portions whose amounts sum exactly to the Payout amount.
- Every source portion has exactly one source type: MANUFACTURING_COMPENSATION_ALLOCATION or ROYALTY.
- A MANUFACTURING_COMPENSATION_ALLOCATION portion references exactly one ORIGINAL Payment Allocation whose purpose is MANUFACTURING_COMPENSATION.
- A ROYALTY portion references exactly one Royalty.
- Every source portion preserves its source type and identity, positive full-source amount, currency, beneficiary identity and type, and the applicable release-policy basis and version snapshot.
- The same economic source may appear at most once within one Payout.
- All source portions in one Payout must have the same beneficiary identity and type, the same currency, and current eligibility when selected.
- Sources from multiple Orders and a mixture of MANUFACTURING_COMPENSATION_ALLOCATION and ROYALTY portions may be aggregated only when all Payout rules are satisfied.
- Partial-source Payout is unsupported in MVP. An eligible source is included only for its complete current outstanding amount.
- The current outstanding amount of a MANUFACTURING_COMPENSATION_ALLOCATION source equals its ORIGINAL Allocation amount minus cumulative REVERSAL Payment Allocation amounts against that ORIGINAL Allocation.
- The current outstanding amount of a ROYALTY source equals its immutable original amount minus cumulative accepted Royalty reversal amounts.
- An Order Item must be FULFILLED before either an associated MANUFACTURING_COMPENSATION_ALLOCATION or Royalty can become a release candidate. CONFIRMED, IN_FULFILLMENT, and CANCELLED Order Items are not release candidates.
- Payment capture, completion of manufacturing, Shipment creation, and Shipment SHIPPED status are insufficient for financial release candidacy. For the current physical-delivery MVP, the applicable non-CANCELLED Shipment must provide DELIVERED evidence and all other fulfillment obligations must be complete before the Order Item may become FULFILLED.
- No separate Buyer receipt confirmation is required for the current MVP FULFILLED gate. Buyer non-receipt complaints, delivery disputes, return or refund conditions, and fraud or compliance concerns may still cause the applicable release or hold policy to keep a source payout-ineligible without rewriting immutable Shipment or Order history merely because of the complaint.
- FULFILLED does not automatically make a source payout-eligible, earned, due, payable, or withdrawable.
- A release candidate is payout-eligible only when the applicable versioned release or hold policy has passed, current compliance and provider capability checks pass, a valid destination is available, current outstanding amount is positive, and no conflicting active reservation or successful consumption exists.
- A ROYALTY source is payout-eligible only when its recorded cumulative Royalty reversal equals the authoritative cumulative Royalty reversal target derived from all applicable accepted Payment Allocation REVERSAL facts.
- Once an accepted Payment refund and its complete Payment Allocation REVERSAL set are committed for a Royalty-bearing Order Item, the resulting authoritative cumulative Royalty reversal target applies immediately to Payout eligibility. A Royalty cannot be included in a new Payout or submitted through an existing Payout while its recorded cumulative reversal is below that target.
- Payout eligibility is a platform transfer condition and does not establish the legal or accounting classification of an amount as earned, due, or unconditional debt.
- No separate Hold or Reserve entity is introduced in MVP. The applicable release-policy basis and version are preserved for historical explanation; arbitrary hold durations must not be invented without an approved policy.
- For one source, uncommitted amount is `max(0, current outstanding amount - successfully paid amount - active reserved amount)`.
- Successfully paid amount is the sum of that source's portions in SUCCEEDED Payouts. Active reserved amount is the sum of that source's portions in PENDING or PROCESSING Payouts.
- Payout-available amount equals uncommitted amount only when release-policy, compliance, provider-capability, and destination requirements pass; otherwise it is zero.
- Because partial-source Payout is unsupported, a source may be selected only when its payout-available amount equals its complete positive current outstanding amount.
- Creation of a PENDING Payout, creation of all immutable source portions, and reservation of every complete included source amount form one atomic domain operation.
- A source may have at most one active reservation at a time and may be successfully consumed by at most one SUCCEEDED Payout.
- A later reversal or provider return does not reopen a source for another Payout after successful consumption.
- The immutable destination snapshot includes provider identity and type, provider recipient or account reference, destination type, non-sensitive masked or display metadata, provider-side destination identifier or reference where applicable, and the relevant onboarding or payout-capability context at creation.
- Raw bank credentials, complete account details, secrets, and authentication credentials are never Payout domain data.
- The provider, provider account or routing context, and destination snapshot are frozen when the Payout is created. A later destination change applies only to future Payouts.
- Before any provider execution command is sent, the platform must durably establish one stable submission and economic-idempotency identity associated with the Payout.
- A provider execution identifier may be assigned after creation when submission occurs, but once assigned it is immutable for that Payout.
- An external provider command and the local database transaction are not assumed to form one cross-system ACID transaction. The provider execution command must be economically idempotent under the Payout's stable submission identity.
- One Payout execution attempt may cause at most one outbound economic transfer.
- If the provider may have accepted the transfer but the application loses the response or acknowledgement, the Payout remains PROCESSING with its reservations active. The platform must retry, query, or reconcile the same provider execution through the stable submission identity and must not send an independent transfer or create a retry Payout solely because the response was lost.
- Current provider and platform compliance, onboarding, and payout-capability requirements must be revalidated before submission. This does not introduce a KYC Profile or detailed compliance-document model.
- If no valid destination exists before creation, no Payout is created.
- If the frozen destination becomes invalid while the Payout is PENDING and before submission, the Payout is CANCELLED and all its source reservations are released.
- If destination failure after submission provides definitive evidence that no transfer occurred or will occur, the Payout becomes FAILED. Ambiguous evidence leaves it PROCESSING.
- A Payout has exactly one lifecycle state: PENDING, PROCESSING, SUCCEEDED, FAILED, or CANCELLED.
- PENDING means the immutable attempt and source reservations exist, but the durable local submission commitment has not occurred.
- The transition from PENDING to PROCESSING is the durable local submission commitment boundary for exactly one outbound provider execution associated with the Payout.
- Immediately before committing PENDING to PROCESSING, every source must be revalidated for current outstanding amount, equality with its immutable source-portion amount, absence of a newly accepted source reduction, ownership of its active reservation by this Payout, absence of successful prior consumption, beneficiary and currency consistency, current release or hold policy, compliance and provider payout capability, and destination eligibility. Every ROYALTY source must also be reconciliation-complete against all applicable accepted Payment Allocation REVERSAL facts.
- Source revalidation and the PENDING-to-PROCESSING transition must commit as one local atomic or serializable operation. A stale source or reconciliation-incomplete Royalty prevents the transition.
- PROCESSING means Creastrix has durably committed exactly one outbound provider execution for the Payout, whether or not the provider acknowledgement has been received, and the final safe economic outcome of that same execution is not yet known.
- SUCCEEDED means the platform has reliable accepted evidence that the complete immutable Payout amount was transferred. It does not prove final bank settlement, irrevocability, absence of future return, or absence of recovery exposure.
- FAILED means the platform has definitive accepted evidence that the complete amount did not and will not transfer through this attempt.
- CANCELLED means the attempt was intentionally terminated with reliable evidence that no recognized transfer occurred.
- PENDING and PROCESSING are active states. SUCCEEDED, FAILED, and CANCELLED are terminal.
- The allowed transitions are PENDING to PROCESSING, FAILED, or CANCELLED; and PROCESSING to SUCCEEDED, FAILED, or CANCELLED.
- PROCESSING may transition to CANCELLED only when reliable void or no-transfer evidence exists. An ambiguous provider outcome remains PROCESSING and keeps every reservation active.
- A PENDING Payout may be cancelled before submission. FAILED and CANCELLED release all included source reservations; SUCCEEDED consumes them successfully.
- A terminal Payout never returns to an active state. A retry is always a new Payout with a new stable identity and newly validated source eligibility and destination context.
- Provider evidence is not automatic domain truth. Before accepting submission or outcome evidence, the authorized platform workflow validates provider and account identity, external reference, beneficiary, amount, currency, destination correlation, authenticity, duplicate economic-event identity, current Payout state, and the allowed transition.
- The same external transfer submission or outcome event must never be recognized more than once, and repeated delivery of accepted evidence must not create another Payout or duplicate state change.
- Provider evidence of a partial transfer cannot make the Payout SUCCEEDED or safely FAILED. The Payout remains PROCESSING, reservations remain active, and an explicit reconciliation exception is required. No partial-success lifecycle state is introduced in MVP.
- Acceptance of a source reversal and commitment of an affected Payout from PENDING to PROCESSING must follow one serialized domain ordering.
- If the accepted source reversal commits first while an affected Payout is PENDING, recognition of that reversal and invalidation or cancellation of the stale Payout must share one coordinated local atomic or serializable boundary, or an equivalent serialized operation. The Payout cannot enter PROCESSING, the entire Payout is CANCELLED, every reservation is released, and any later attempt is a new Payout with recalculated source amounts. The immutable amount and source portions are never edited.
- If the PENDING-to-PROCESSING submission commitment commits first after successful source revalidation, a subsequent source reversal does not cancel that provider execution and follows the PROCESSING and recovery rules.
- An accepted reversal that has already committed can never be followed by a successful stale PENDING-to-PROCESSING transition for the affected Payout.
- If an included source is reduced while the Payout is PROCESSING, the Payout and portions remain unchanged, reservations stay active, and transfer outcome processing continues. The reversal may create recovery exposure.
- A source reduction after SUCCEEDED never rewrites the Payout, releases its successful consumption, or permits a second Payout for that source.
- For a source, recovery exposure is derived as `max(0, successfully paid amount - current outstanding amount)` and is not a Payout lifecycle state or a separate entity in MVP.
- A provider return after SUCCEEDED does not rewrite the successful transfer attempt or reopen its sources. Return recognition and recovery treatment require a future production workflow.
- Source portion amount equals beneficiary transfer amount in MVP. Provider fees paid by Creastrix remain outside source conservation and do not reduce the Payout amount; beneficiary-paid payout fees are unsupported.
- Only an authorized platform payout or finance workflow may create a Payout, submit it, accept provider evidence, cancel it, or otherwise mutate its lifecycle.
- A beneficiary User may view summary information for that User's own Payouts.
- Payouts for an Organization beneficiary may be viewed by a currently authorized Organization actor; until future explicit delegation exists, current general Organization authority requires an ACTIVE Organization Membership with the role OWNER.
- Platform-authorized finance or administration workflows may view Payouts and internal reconciliation context.
- Buyer status, Manufacturer participation, Designer participation, Workspace ownership, Workspace Membership, and the PROJECTS, READY_MADE_PRODUCTS, or LISTINGS scopes do not expose Payout or grant mutation authority. No PAYOUTS Workspace permission scope exists in MVP.
- Payouts, source portions, accepted provider evidence, and historical destination and beneficiary context cannot be destructively deleted or rewritten.

## Invariants

- A Payout always has one stable identity.
- A Payout always has exactly one immutable beneficiary of type USER or ORGANIZATION.
- A Payout amount is always positive and immutable.
- A Payout always has exactly one immutable currency.
- A Payout always contains one or more immutable source portions whose positive amounts sum exactly to the Payout amount.
- Every source portion always references exactly one valid source of type MANUFACTURING_COMPENSATION_ALLOCATION or ROYALTY.
- One Payout never contains the same economic source more than once.
- Every source portion always matches the Payout beneficiary identity and type and Payout currency.
- Every source portion always represents the complete eligible current outstanding amount of its source at Payout creation.
- One source never has more than one active Payout reservation and is never successfully consumed by more than one Payout.
- A PENDING or PROCESSING Payout always reserves all its source portions.
- Every transition from PENDING to PROCESSING is committed against revalidated current sources and never against a source reduction that has already been accepted.
- Every ROYALTY source is reconciliation-complete against applicable accepted Payment Allocation REVERSAL facts at the decision boundary where it is selected for Payout creation and at the decision boundary where its Payout transitions from PENDING to PROCESSING.
- One Payout execution attempt never causes more than one outbound economic transfer.
- A Payout always has exactly one lifecycle state: PENDING, PROCESSING, SUCCEEDED, FAILED, or CANCELLED.
- SUCCEEDED, FAILED, and CANCELLED never return to an active state.
- A Payout never reaches SUCCEEDED without reliable accepted evidence for transfer of its complete immutable amount.
- Ambiguous or partial provider outcomes remain PROCESSING and keep all source reservations active.
- Accepted reversals, provider returns, beneficiary changes, and destination changes never rewrite immutable Payout history.
- Payout and its accepted financial and provider-correlated history are never destructively deleted.

## Notes

Payout is not a balance, Payment, Payment Allocation, Royalty, Payout Profile, bank account, withdrawal request, settlement record, accounting ledger, legal earning determination, or proof that transferred value cannot later be returned.

No Payout Profile, Payout Account, Payout Destination, Payout Source, Payout Item, Balance, Recovery, Adjustment, Reserve, Hold, Withdrawal Request, Provider, KYC Profile, Settlement, or Accounting Entry entity is introduced by this specification. Source portions and destination context are embedded immutable values of Payout.

Actual provider selection, licensed payout-rail architecture, legal earning and due classification, tax reporting, KYC, AML, sanctions screening, payout limits, release timing, disputes, chargebacks, provider returns, recovery enforcement, accounting treatment, retention duration, and reconciliation operating procedures require production policy, legal, compliance, finance, and integration validation.

Technical coordination may use row or advisory locking, optimistic versioning, serializable transactions, compare-and-transition, durable outbox or command processing, inbox processing, provider idempotency keys, or equivalent mechanisms. These mechanisms implement the required domain ordering and economic idempotency without introducing a Reservation, Payout Command, or other core entity.

Future recovery or balance domains may consume immutable Payout history but must not rewrite it. Blockchain or other enhanced royalty-tracking infrastructure may improve auditability only if it preserves the same domain distinctions, idempotency, privacy, provider evidence validation, and correction model.

---

Status: DRAFT

Version: 0.2
