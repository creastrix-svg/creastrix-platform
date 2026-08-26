# Ready-Made Product

## Purpose

A Ready-Made Product represents the stable domain identity of one independently stocked physical product configuration that can be fulfilled from existing stock without requiring a new manufacturing process after purchase.

It exists independently from Project, Revision, and Listing lifecycles.

## Responsibilities

A Ready-Made Product is responsible for:

- representing a stable physical product identity;
- preserving its relationship with one Workspace and that Workspace's commercial and operational context;
- representing one product-defining physical configuration;
- recording the User who created the record;
- managing the ACTIVE and ARCHIVED lifecycle;
- maintaining simple available quantity in MVP;
- owning available-quantity state, enforcing accounted physical quantity capacity using outstanding allocated quantity derived from immutable ready-made Order Item allocation facts, and applying quantity effects from confirmation, eligible pre-dispatch release, dispatch, and explicit manual quantity adjustment;
- preserving the internal idempotency state and immutable historical outcome required to apply each registered manual quantity-delta command at most once.

## Relationships

A Ready-Made Product:

- belongs to exactly one Workspace;
- has exactly one immutable Created By User;
- may be targeted by zero or more Listings over time;
- may have stock allocated through zero or more ready-made Order Items;
- may be referenced by Audit Log events in the future.

## Business Rules

- A Ready-Made Product may be created or edited only by a User with effective write authorization for the READY_MADE_PRODUCTS scope.
- Every new Ready-Made Product starts in the ACTIVE lifecycle state. The creating User cannot select ACTIVE or ARCHIVED as creation input.
- Initial available quantity is required creation input and must be an integer from 0 through 9,223,372,036,854,775,807 inclusive.
- After effective READY_MADE_PRODUCTS write authorization succeeds, the Workspace relationship, immutable Created By User, initial ACTIVE lifecycle state, and initial available quantity are established as one coherent creation result.
- Creating a Ready-Made Product does not create a Listing automatically.
- The Workspace relationship of a Ready-Made Product cannot be changed in MVP.
- The Workspace owner provides the platform-recognized commercial context in which the Ready-Made Product is managed.
- This commercial context does not prove legal title, physical custody, seller-of-record, manufacturer, supplier, importer, brand ownership, or intellectual-property ownership.
- Created By identifies only the User who created the Ready-Made Product record in Creastrix and does not determine ownership, commercial context, seller, manufacturer, supplier, importer, brand ownership, creative authorship, royalty rights, or Listing publication authority.
- Deactivation of the Created By User does not rewrite the Created By relationship.
- A Ready-Made Product has the lifecycle state ACTIVE or ARCHIVED and may transition from ACTIVE to ARCHIVED or from ARCHIVED to ACTIVE.
- An ACTIVE Ready-Made Product is operational and may participate in commerce subject to Listing, Order, and other applicable domain rules, but ACTIVE does not mean published or in stock.
- An ARCHIVED Ready-Made Product is not intended for new commercial use, retains required historical references, and may return to ACTIVE.
- A Ready-Made Product cannot be destructively deleted in MVP, whether ACTIVE, ARCHIVED, listed, purchased, allocated, or unused. ARCHIVED remains the retained non-active lifecycle state and no DELETED state is introduced.
- The global no-destructive-delete rule protects immutable Listing source relationships, historical ready-made Order Item source identity, confirmed allocation history, and stable commercial product identity without conditional predicates based on Listing count, Order Item count, stock quantity, or allocation state.
- Any future deletion of a never-commercialized Ready-Made Product requires separate explicit deletion and retention rules.
- A Ready-Made Product exists independently from Listing, may exist without a Listing, and is never published directly.
- A new Listing may be created for a Ready-Made Product only while the product is ACTIVE, and no more than one Listing for that product may be ACTIVE at the same time in MVP.
- If a Ready-Made Product becomes ARCHIVED, existing Listing lifecycle status does not change automatically, but the Listing becomes non-orderable. Orderability may recover after the product returns to ACTIVE when all other conditions hold.
- Available quantity changes never change Listing lifecycle.
- A Ready-Made Product represents product identity and product-defining physical characteristics, while public commercial offer and presentation concerns belong to Listing.
- One independently stocked physical configuration is represented by one Ready-Made Product in MVP.
- Ready-Made Product owns available-quantity state, accounted physical quantity capacity validation, non-negativity, and application of quantity effects. Order Item owns the immutable confirmed allocation fact, original allocated quantity, allocation history, lifecycle, and cancellation eligibility. Shipment provides authoritative dispatch-state input for the release gate and outstanding-allocation boundary but does not own stock or change available quantity.
- Available quantity represents the number of whole physical units that are currently sellable, free for new allocation, and not already allocated to a confirmed Order Item in the single MVP stock pool.
- Outstanding allocated quantity is the mathematical sum of the original quantities of confirmed ready-made Order Item allocations that have neither been released through eligible pre-dispatch cancellation nor ceased to be outstanding through dispatch.
- Accounted physical quantity is the mathematical sum of available quantity and outstanding allocated quantity and is always an integer from 0 through 9,223,372,036,854,775,807 inclusive.
- Available quantity is not total physical on-hand quantity, does not include units already allocated to confirmed Order Items, and does not represent historical sales.
- Available quantity does not determine lifecycle; an ACTIVE Ready-Made Product may have quantity zero, and an ARCHIVED Ready-Made Product may retain quantity greater than zero.
- Successful confirmation of a ready-made Order Item requires sufficient available quantity and atomically establishes its immutable allocation fact, decreases Ready-Made Product available quantity by exactly the full allocated quantity, and increases outstanding allocated quantity by that same quantity. Accounted physical quantity does not change.
- If the full Order Item quantity cannot be allocated, Order confirmation fails and no partial confirmed Order is created.
- The confirmation-time transfer from available quantity to outstanding allocated quantity is applied exactly once. Available quantity never becomes negative, and the same stock capacity cannot be confirmed for more than one Order Item.
- The original confirmed allocation and quantity are retained permanently in Order Item history. Ending the allocation's operational effect never deletes or rewrites that historical fact.
- Eligible pre-dispatch Order Item cancellation releases a confirmed ready-made allocation only when all of the following conditions are satisfied:
  1. the Order Item has a confirmed ready-made allocation;
  2. the normal cancellation transition is allowed by current Order Item rules;
  3. physical dispatch has not occurred;
  4. no covering non-CANCELLED Shipment has reached SHIPPED, DELIVERED, or UNDELIVERED;
  5. release has not previously been applied for that allocation; and
  6. if the Order Item is a current member of a PREPARING Shipment, its applicable membership resolution is included in the same atomic cancellation-and-release result so the cancelled Order Item does not remain planned for dispatch.
- Eligible cancellation, allocation release, and any required PREPARING Shipment membership or lifecycle resolution automatically form one atomic domain result. The release decreases outstanding allocated quantity and increases available quantity by the same full original confirmed Order Item quantity, leaves accounted physical quantity unchanged, and is applied at most once. Because the release transfers quantity within accounted physical quantity, it cannot overflow when the accounted physical quantity invariant held before the operation.
- When such a PREPARING Shipment has at least one other valid current member after removal, the cancelled Order Item is removed inside that atomic result. When the cancelled Order Item is the final current member, the Shipment transitions to CANCELLED inside the same result and preserves its current membership as frozen history.
- No eligible ready-made cancellation may commit before its release and required PREPARING Shipment resolution and leave Shipment cleanup for a later operation. Rollback of any part rolls back the Order Item cancellation, allocation release, and related Shipment membership or lifecycle change.
- An already CANCELLED Order Item, a repeated cancellation command, or repeated event processing does not apply another release.
- Pre-dispatch cancellation and release serialize with a covering Shipment's transition to SHIPPED. If cancellation and release commit first, the Order Item cannot be shipped; if SHIPPED commits first, pre-dispatch release is unavailable. Both outcomes cannot commit for the same allocation.
- When a covering Shipment commits SHIPPED, each corresponding confirmed ready-made allocation ceases to contribute to outstanding allocated quantity. Available quantity remains unchanged, and accounted physical quantity decreases by the dispatched allocation quantity.
- Shipment transition to DELIVERED or UNDELIVERED, Order Item transition to FULFILLED, and ordinary fulfillment or completion apply no further available, outstanding allocated, or accounted physical quantity change. Post-dispatch terminal non-delivery cancellation never releases the original allocation or restores any quantity automatically.
- Return-to-sender or other UNDELIVERED evidence does not itself restore stock because it does not prove physical receipt, inspection, sellable condition, or restocking acceptance.
- Payment never directly changes Ready-Made Product available quantity or releases a confirmed allocation. Payment-resolution failure may lead commerce workflow to attempt eligible Order Item cancellation, but stock changes only through the successful cancellation-and-release result defined here.
- A User with effective READY_MADE_PRODUCTS write authorization may apply an explicit signed 64-bit integer manual quantity delta from -9,223,372,036,854,775,808 through 9,223,372,036,854,775,807 inclusive, excluding zero.
- A positive manual delta increases free sellable available quantity and accounted physical quantity by the same amount. It may be APPLIED only when the mathematical sum of current accounted physical quantity and the delta does not exceed 9,223,372,036,854,775,807; otherwise it reaches terminal REJECTED with reason OVERFLOW.
- A negative manual delta decreases only available quantity and therefore decreases accounted physical quantity by the same amount; it cannot reduce, consume, or absorb outstanding allocated quantity. It may be APPLIED only when the mathematical sum of current available quantity and the delta is not below 0; otherwise it reaches terminal REJECTED with reason UNDERFLOW.
- Available quantity uses the non-negative signed 64-bit integer range from 0 through 9,223,372,036,854,775,807 inclusive.
- Every quantity arithmetic decision uses checked mathematical arithmetic under serialization and never wraps, clamps, or partially applies a delta.
- Manual quantity adjustment performs an atomic current-state check and serializes with ready-made Order Item confirmation and allocation, eligible pre-dispatch release, and dispatch so no operation observes an incompatible intermediate quantity state.
- A manual quantity-delta command identity is the composite pair of Ready-Made Product identity, or Product ID, and caller-supplied Command ID.
- The caller or workflow creates a stable caller-supplied Command ID before the first application attempt and must present the same composite identity and signed non-zero delta on every registration, application, retry, or replay attempt.
- A Command ID by itself is not globally unique, is not reserved across all Products or Workspaces, and is not an authority or authentication credential. The same Command ID value may be used independently for different Ready-Made Products: `(Product-A, C)` and `(Product-B, C)` are different command identities and never conflict.
- Before successful durable registration commits, preserving the binding between the composite command identity and delta is the caller or workflow's responsibility. The platform has no durable record from which it could detect a changed delta after a crash or rollback before that commit.
- A failure or rollback before registration commits leaves no platform-enforced binding and is not an applied command.
- Platform-enforced immutable binding begins only when registration commits successfully. After that commit, the exact composite identity is permanently occupied, its Product ID cannot be replaced on retry, and its delta cannot change. Another Product with the same Command ID is a different composite identity rather than a payload mismatch.
- Registration, application, retry, replay, and result disclosure first require current authorization of the represented actor relative to the Product ID supplied in the composite identity.
- Before that authorization succeeds, the platform must not disclose whether the Product exists, whether a command record exists, its REGISTERED, APPLIED, or REJECTED state, its delta binding, its terminal outcome, a payload mismatch, or use of the same Command ID for another Product.
- A request for a Product that is inaccessible or cannot be disclosed has an externally indistinguishable inaccessible-or-nonexistent outcome under the future API policy. This specification does not define HTTP status codes or a response schema.
- After authorization succeeds, command lookup, registration, retry, application, and replay operate only inside the namespace of the supplied Product ID. A command record for another Product with the same Command ID is not read, compared, or allowed to affect the result.
- Registration requires an existing Ready-Made Product, valid input, an existing ACTIVE represented actor User, and current effective Workspace-layer READY_MADE_PRODUCTS write authorization for that Product. A zero delta, missing Product, inactive or missing actor, or authorization failure creates no command record.
- Input validation, Product authorization and disclosure eligibility, Product existence, and actor status are all checked before durable registration is created, without exposing their internal evaluation order through distinguishable pre-authorization outcomes.
- Registration durably binds the exact composite identity to the signed non-zero delta in the initial internal state REGISTERED. Registration itself neither changes available quantity nor means that the command has been applied.
- Repeating registration with the same Product ID, Command ID, and delta resolves to the existing internal command record without applying the delta. After authorization succeeds, registration with the same Product ID and Command ID but a changed delta is rejected as a payload mismatch.
- Concurrent registration attempts serialize relative to the exact `(Product ID, Command ID)` pair. At most one durably registered command record may exist for that pair, and no contender makes a final decision from an uncommitted assumption about another attempt. Attempts using the same Command ID for different Products are independent.
- After one registration for an exact composite identity commits, a contender presenting the same delta resolves to that existing durably registered command record in its current internal state, while a contender presenting another delta is rejected as a payload mismatch after current Product authorization is verified.
- If the first registration transaction for an exact composite identity rolls back completely, no durable record or binding exists for that pair. A waiting or repeated attempt may then register it only after Product existence, actor state, and current authorization are checked again.
- After a registration commit whose caller-visible outcome is unknown, retry first resolves the durable state for the exact composite identity and only then decides whether registration is still required.
- The internal command state machine has exactly the states REGISTERED, APPLIED, and REJECTED. It is an internal idempotency state machine and not a public Ready-Made Product lifecycle.
- REGISTERED means that the exact Product ID and caller-supplied Command ID pair is durably registered with one immutable signed non-zero delta, available quantity has not changed, and no historical applied result exists. REGISTERED may transition only to APPLIED or REJECTED.
- Infrastructure failure or an unknown application outcome that does not complete a durable application or rejection commit leaves the command REGISTERED. A REGISTERED command is applied only through an explicit application attempt; registration does not trigger automatic or background application.
- Every application attempt revalidates that the represented actor exists, is ACTIVE, and currently has effective Workspace-layer READY_MADE_PRODUCTS write authorization for the Product ID before command lookup, state disclosure, or result disclosure. Actor identity is not part of the immutable command payload, so another currently authorized actor may apply or resolve the same composite command identity.
- Successful application is one atomic commit that together performs the final arithmetic decision against the serialized current quantity state, changes available quantity exactly once, transitions REGISTERED to APPLIED, and persists the complete immutable historical applied outcome required for stable replay. That outcome preserves exactly the caller-supplied Command ID, Ready-Made Product identity, immutable signed delta, and resulting available quantity.
- If that single commit does not complete, the quantity mutation is rolled back in full, no terminal state is persisted, no partial applied outcome is persisted, and the already durably registered command remains REGISTERED.
- APPLIED is terminal. After current authorization is verified, an identical replay returns the original historical applied outcome as recorded, without recomputing the arithmetic against the current available quantity and without applying the delta again. Later quantity changes never rewrite that historical outcome.
- A REGISTERED command transitions to REJECTED only when a negative delta would make available quantity less than 0 or a positive delta would make accounted physical quantity greater than 9,223,372,036,854,775,807. The terminal reason is UNDERFLOW for the former and OVERFLOW for the latter.
- Terminal rejection is one atomic commit that together performs the applicable serialized arithmetic decision, leaves available quantity and every derived quantity unchanged, transitions REGISTERED to REJECTED, and persists the complete immutable rejection outcome.
- The complete REJECTED outcome contains exactly the caller-supplied Command ID, Ready-Made Product identity, immutable signed delta, terminal reason UNDERFLOW or OVERFLOW, and the available quantity observed in the serialized quantity state for that terminal arithmetic decision.
- The stored available quantity is historical evidence of one input to the terminal decision and is not current Ready-Made Product state. No additional arbitrary diagnostic data is a domain requirement, and the outcome contains no actor identity, personal data, exception or error text, full Product snapshot, general audit history, or timestamp.
- If that single commit does not complete, the command remains REGISTERED, available quantity is unchanged, and no partial rejection state or outcome is persisted.
- REJECTED is terminal. After current authorization is verified, an identical replay reproduces that same terminal rejection as recorded, without recomputing the arithmetic against the current available quantity.
- Later quantity changes never revive a REJECTED command. Applying the intended delta after conditions change requires a new caller-supplied Command ID for that Product and therefore a new composite command identity.
- Authorization failure is not a REJECTED outcome. It leaves an existing REGISTERED, APPLIED, or REJECTED record unchanged and blocks application or replay until a later explicit attempt has current authorization.
- The only allowed internal state transitions are REGISTERED to APPLIED and REGISTERED to REJECTED. Same-state transition commands, every transition from APPLIED or REJECTED, and mutation of the bound Product, delta, historical applied result, or any field of the immutable rejection outcome are forbidden.
- Retry always uses the same Product ID, caller-supplied Command ID, and signed delta. Current authorization for that Product ID is checked before command lookup or any stored state, applied result, rejection, or mismatch is disclosed, and neither the Command ID nor the composite command identity provides authority or authentication.
- After an unknown caller outcome, the platform resolves the local command state for the exact composite identity before attempting application again or treating the intent as unregistered.
- If no durable command record exists for the exact composite identity after an unknown outcome, the platform cannot prove that an earlier attempt occurred. The caller must preserve the original Product ID, Command ID, and delta and submit that same composite command for registration again. A new independent delta intent for that Product receives a new caller-supplied Command ID and therefore a new composite identity.
- If the command is REGISTERED, an explicit retry attempts application again. If it is APPLIED, retry returns the original historical result. If it is REJECTED, retry returns the original terminal rejection.
- After durable registration commits, the exact `(Product ID, Command ID)` pair is permanently occupied in MVP. The internal command record, its immutable signed non-zero delta binding, and its defined terminal outcome when one exists do not expire, are not deleted, are not removed by any retention, cleanup, or compaction policy, and are never replaced by a new command registered with that same pair. This applies equally to a REGISTERED, an APPLIED, and a REJECTED command. Permanent occupation of one pair does not reserve the same Command ID for another Product.
- An APPLIED record is retained so that no later retry of the same composite identity can apply its delta a second time. A REJECTED record is retained so that a later available-quantity change can never revive that composite identity.
- Absence of a durable command record for an exact composite identity after its registration has already committed successfully is not a valid normal outcome and is never a reason to register that same pair again.
- No retry time window, automatic retry, or background recovery is introduced in MVP, and no deletion or archival lifecycle for command records exists. Any future retention, archival, or compaction policy requires a separate architecture decision and must preserve at-most-once application, the immutable registered binding, and the replayable terminal outcome.
- Manual quantity-delta command persistence is an internal immutable idempotency record required for exactly-once application and replay. It is not a Manual Adjustment, Stock Movement, Inventory Transaction, Reservation, Audit Log, stock-history query model, or other core entity, and its exact persistence representation remains implementation work.
- A manual delta is a change to available quantity rather than an input total. Its positive capacity decision includes outstanding allocated quantity through accounted physical quantity, while a negative delta never rewrites confirmed allocation history or consumes outstanding allocations. A manual delta does not change Ready-Made Product lifecycle.
- Manual quantity adjustment is allowed independently of whether the Ready-Made Product is ACTIVE or ARCHIVED and never changes its identity, Workspace, Created By User, or lifecycle state.
- Procurement, physical receipt, inspection, returns, restocking, and a future Inventory domain remain separate future workflows. Manual quantity adjustment is not any of those workflows and is never triggered automatically by Payment, Shipment, refund, UNDELIVERED, or return-to-sender evidence.
- Physical units exist before the customer order, and ordinary ready-made fulfillment does not require a new manufacturing process or a Manufacturer assignment because of that order.
- Pick, pack, label, and shipment handling do not by themselves turn ready-made fulfillment into made-to-order manufacturing.
- An order requiring fabrication, cutting, engraving, production, or other changes to product-defining physical characteristics is not ordinary Ready-Made Product fulfillment in MVP.
- If a change creates a materially different independently stocked physical configuration, a new Ready-Made Product is required; corrections that do not change the real product identity or configuration may remain on the existing record.
- A Ready-Made Product does not require a Manufacturer Profile or a mandatory Manufacturer or Supplier relationship.

## Invariants

- A Ready-Made Product always has one stable domain identity.
- A Ready-Made Product always belongs to exactly one Workspace.
- The Workspace of a Ready-Made Product remains unchanged in MVP.
- A Ready-Made Product always has exactly one immutable Created By User.
- A Ready-Made Product always has exactly one lifecycle state: ACTIVE or ARCHIVED.
- A Ready-Made Product never has PUBLISHED or DELETED as a lifecycle state in MVP.
- A Ready-Made Product is never destructively deleted in MVP.
- Available quantity is always an integer from 0 through 9,223,372,036,854,775,807 inclusive.
- Outstanding allocated quantity is always the non-negative derived sum of confirmed ready-made allocations that have neither been released nor dispatched.
- Accounted physical quantity always equals available quantity plus outstanding allocated quantity and is always an integer from 0 through 9,223,372,036,854,775,807 inclusive.
- Every successful ready-made Order Item confirmation transfers exactly the full confirmed Order Item quantity from available quantity to outstanding allocated quantity exactly once and leaves accounted physical quantity unchanged.
- The same available stock capacity is never confirmed for more than one Order Item.
- An eligible pre-dispatch cancellation release transfers exactly the original confirmed Order Item quantity from outstanding allocated quantity to available quantity at most once and leaves accounted physical quantity unchanged.
- Cancellation, its eligible pre-dispatch release, and any required PREPARING Shipment membership or lifecycle resolution always commit or roll back together.
- A covering Shipment cannot both commit SHIPPED and permit pre-dispatch release for the same confirmed allocation.
- SHIPPED ends the corresponding allocation's contribution to outstanding allocated quantity exactly once without changing available quantity, and no post-dispatch event restores that quantity automatically.
- Confirmed allocation identity and original quantity remain permanently preserved in Order Item history even after operational release or fulfillment.
- Shipment SHIPPED, DELIVERED, or UNDELIVERED and Order Item FULFILLED never change available quantity.
- Payment, refund, and return-to-sender evidence never directly change available quantity.
- Post-dispatch terminal non-delivery cancellation never restores the original confirmed allocation to available quantity.
- Manual quantity adjustment never rewrites confirmed allocation history, never consumes outstanding allocations, never makes available quantity negative, and never makes accounted physical quantity exceed 9,223,372,036,854,775,807.
- For one Ready-Made Product, every manual quantity-delta quantity effect serializes with every ready-made Order Item confirmation and allocation effect, every eligible pre-dispatch release effect, and dispatch. No such operation makes its arithmetic decision from an incompatible concurrent Product state or exposes an intermediate quantity transfer, and neither overselling nor a lost quantity update may commit.
- Before durable registration commits, the caller or workflow preserves the binding between the exact composite `(Product ID, caller-supplied Command ID)` identity and signed non-zero delta; the platform does not claim enforcement before that durable boundary.
- After durable registration commits, the exact composite identity remains permanently bound to its Product ID and signed non-zero delta, and neither part of that binding ever changes.
- At most one durably registered manual quantity-delta command record exists for one exact `(Product ID, caller-supplied Command ID)` pair. Concurrent registration outcomes for that pair always resolve from its single committed binding rather than an uncommitted assumption, while the same Command ID used for another Product identifies an independent command.
- Every registered manual quantity-delta command has exactly one internal state: REGISTERED, APPLIED, or REJECTED.
- A registered manual quantity-delta command transitions only from REGISTERED to APPLIED or from REGISTERED to REJECTED.
- APPLIED and REJECTED are terminal, and their historical outcomes never change.
- An APPLIED manual quantity-delta command changes available quantity exactly once and preserves its original resulting available quantity.
- A REJECTED manual quantity-delta command never changes available quantity, is never revived by later quantity changes, and preserves exactly its caller-supplied Command ID, Ready-Made Product identity, immutable signed delta, terminal UNDERFLOW or OVERFLOW reason, and available quantity observed in the serialized quantity state for the terminal decision.
- A durably registered manual quantity-delta command record, its immutable composite-identity binding, and its terminal outcome are never deleted, never expire, and are never replaced for that exact pair, whether the command is REGISTERED, APPLIED, or REJECTED.
- A manual quantity-delta command reaches APPLIED or REJECTED only through one atomic commit that persists its complete terminal outcome together with the quantity change or its guaranteed absence, and a commit that does not complete leaves the command REGISTERED with available quantity unchanged.
- Manual quantity arithmetic never wraps, clamps, or partially applies. A negative delta is UNDERFLOW when available quantity plus the delta is below 0, and a positive delta is OVERFLOW when accounted physical quantity plus the delta is above 9,223,372,036,854,775,807.
- Registration, application, retry, replay, and result disclosure require current effective authorization relative to the supplied Product ID before lookup or disclosure of Product existence, command existence, state, binding, outcome, mismatch, or cross-Product use of the same Command ID.
- After authorization, command handling remains confined to the supplied Product namespace; a command with the same Command ID under another Product is never read, compared, or allowed to affect the result.
- Neither a caller-supplied Command ID nor its composite command identity provides authority or authentication.
- A REGISTERED command is never applied automatically or in the background.
- A Ready-Made Product lifecycle remains independent from its stock availability.
- A Ready-Made Product remains independent from Project and Revision lifecycles.
- The platform-recognized commercial context of a Ready-Made Product is always derived from the owner of its Workspace in MVP.
- Workspace roles and permission scopes never change the Workspace owner or the commercial context of a Ready-Made Product.

## Notes

The simple available quantity and explicit manual integer delta are intentional MVP mechanisms. Confirmed allocation transfers quantity from available to outstanding allocated quantity once, eligible pre-dispatch release reverses that transfer once, and dispatch ends the allocation's contribution to outstanding allocated quantity without restoring available quantity. Post-dispatch terminal non-delivery cancellation never restores it. UNDELIVERED or return-to-sender evidence alone is not restock. Procurement, returns, physical receipt, inspection, restocking, and technical concurrency mechanisms remain future integration concerns involving Order, Shipment, Inventory, or related domains as applicable. Quantity may later move into an Inventory domain without changing Ready-Made Product identity.

Outstanding allocated quantity is a derived total from immutable Order Item allocation facts and their release or dispatch outcomes, not a new core entity or public lifecycle state. Accounted physical quantity is the mathematical sum of available quantity and that derived total. Their exact persistence, derivation, transaction, and locking mechanisms remain implementation work; this specification does not define tables, indexes, or a concrete lock order.

Durable manual quantity-delta registration defines the honest platform-enforcement boundary. Before registration commits, a crash or rollback may leave no durable record, so the platform cannot detect whether a later submission changed the `(Product ID, caller-supplied Command ID)` pair or its delta; the caller or workflow must preserve that binding. After registration commits, the platform owns the immutable binding between that exact composite identity and its delta and the three-state internal idempotency outcome. This boundary does not weaken the requirement that an APPLIED command changes quantity at most once.

The command record is internal idempotency persistence rather than a core entity, public lifecycle, general stock history, reservation, or Inventory architecture. Its exact persistence representation, uniqueness enforcement, locking, retry mechanism, and application interface remain implementation work. The domain requirement is uniqueness within the exact `(Product ID, caller-supplied Command ID)` namespace, independent reuse of one Command ID across Products, the immutable registered delta, the closed REGISTERED/APPLIED/REJECTED state machine, and the immutable terminal outcome needed for replay. Same-pair concurrency, cross-Product namespace isolation, and lock interactions must be verified empirically on `postgres:18.4-alpine` before authoring IMPLEMENTATION 005C.

The supported application path must validate the represented actor and current authorization relative to the supplied Product ID before Product or command lookup and before any state, binding, outcome, mismatch, or cross-Product Command ID use is disclosed. Only then may it resolve durable registration and exactly-once application semantics inside that Product namespace. Ordinary database constraints may enforce structural invariants, but the current migration-owner and runtime-role model does not make arbitrary raw SQL actor-authorized and cannot honestly require a command record for a malicious table-owner direct quantity update. Neither the Command ID nor the composite command identity provides authentication. Database-role separation remains a future security milestone; this specification does not define session markers, privilege mechanisms, or other database security design.

Resolving a Ready-Made Product and its Workspace internally for authorization does not approve distinguishable external `not found` and `unauthorized` responses. Authentication and an HTTP API are not yet defined; their future response and error mapping and existence-disclosure policy require a separate explicit decision. Until current authorization for the supplied Product ID succeeds, an external caller must receive no Product-existence or command-existence oracle, including no disclosure from state, binding, terminal outcome, payload mismatch, or reuse of the same Command ID under another Product. Inaccessible and nonexistent Products remain externally indistinguishable under that future policy.

Every Listing has exactly one immutable commercial source: either a FINALIZED Revision or a Ready-Made Product, never both. A source may have multiple Listings over time, but the Listing specification permits no more than one ACTIVE Listing for the same source in MVP.

Canonical or reference product media versus Listing promotional media remains to be designed. SKU uniqueness, brand and model metadata, physical shipping data, shipping promises, and offer identifiers also remain future boundaries.

Supplier, manufacturer, and importer traceability may be modeled later if required by procurement, compliance, safety, or marketplace rules. The manufacturer of an existing physical product is not the same concept as the Manufacturer assigned to a made-to-order Order Item.

No Product Variant entity exists in MVP. Future product-family or variant grouping may be introduced without changing the stable identities of existing Ready-Made Products.

Creastrix-first selling is platform policy rather than a Ready-Made Product invariant. Third-party seller verification, seller-of-record, and seller self-service remain future commercial concerns.

Order Items preserve the purchased-product and commercial snapshots required for history without depending on the current mutable presentation of a Ready-Made Product.

Destructive deletion means physical or domain removal of the stable Ready-Made Product identity such that existing references can no longer resolve it. It is distinct from ARCHIVED lifecycle, which remains reversible under current rules. Exact retention duration, legal deletion, privacy treatment, archival storage, and pseudonymization remain future legal and compliance work.

Future relational persistence must prevent destructive cascade deletion that would break Listing source or historical Order Item references. Exact database constraints remain implementation work.

Future executable implementation must prove coherent ACTIVE creation with required initial quantity, exact once-only confirmation transfer from available to outstanding allocated quantity, exact once-only eligible pre-dispatch reverse transfer, atomic PREPARING Shipment membership resolution inside cancellation and release, permanent allocation history, exact dispatch removal from outstanding allocated quantity, serialization of release against SHIPPED, and absence of any post-dispatch available-quantity restoration. Manual quantity-delta validation must additionally prove the exact signed 64-bit input boundaries, positive capacity against accounted physical quantity, negative capacity only against available quantity, durable registration boundary, one durable binding under concurrent registration of the same `(Product ID, caller-supplied Command ID)` pair, independent use of the same Command ID for different Products, commit, rollback, and commit-unknown registration outcomes, the exact REGISTERED/APPLIED/REJECTED transition set, duplicate registration for the same pair, identical replay, changed-delta rejection for the same pair after registration, failure during application, response loss after APPLIED commit, immutable minimal terminal outcomes, terminal underflow and overflow without wrapping, clamping, or partial application, no later revival of REJECTED, explicit retry from REGISTERED, authorization relative to the supplied Product before every registration, application, retry, replay, or result disclosure, absence of pre-authorization Product and command existence or cross-Product reuse disclosure, Product-scoped lookup isolation, quantity mutation exactly once, historical outcome stability, serialization with confirmation, release, and dispatch, concurrency and deadlock behavior, and the raw-SQL authorization boundary. Exact locking, transaction-isolation, version-check, arithmetic, persistence, and future API response mechanisms remain implementation validation.

Significant creation, lifecycle, and quantity events may later be recorded through Audit Log behavior.

---

Status: APPROVED

Version: 1.1
