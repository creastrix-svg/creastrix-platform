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
- owning available-quantity state, validating free stock capacity and non-negativity, and applying available-quantity effects from confirmation, eligible pre-dispatch release, and explicit manual quantity adjustment.

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
- Initial available quantity is required creation input and must be a non-negative integer, including zero.
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
- Ready-Made Product owns available-quantity state, capacity validation, non-negativity, and application of quantity effects. Order Item owns the immutable confirmed allocation fact, original allocated quantity, allocation history, lifecycle, and cancellation eligibility. Shipment provides authoritative dispatch-state input for the release gate but does not own stock or change available quantity.
- Available quantity represents the number of whole physical units that are currently sellable, free for new allocation, and not already allocated to a confirmed Order Item in the single MVP stock pool.
- Available quantity is not total physical on-hand quantity, does not include units already allocated to confirmed Order Items, and does not represent historical sales.
- Available quantity does not determine lifecycle; an ACTIVE Ready-Made Product may have quantity zero, and an ARCHIVED Ready-Made Product may retain quantity greater than zero.
- Successful confirmation of a ready-made Order Item atomically establishes allocation of its full quantity and decreases Ready-Made Product available quantity by exactly that full quantity.
- If the full Order Item quantity cannot be allocated, Order confirmation fails and no partial confirmed Order is created.
- The confirmation-time decrease is applied exactly once. Available quantity never becomes negative, and the same stock capacity cannot be confirmed for more than one Order Item.
- The original confirmed allocation and quantity are retained permanently in Order Item history. Ending the allocation's operational effect never deletes or rewrites that historical fact.
- Eligible pre-dispatch Order Item cancellation releases a confirmed ready-made allocation only when all of the following conditions are satisfied:
  1. the Order Item has a confirmed ready-made allocation;
  2. the normal cancellation transition is allowed by current Order Item rules;
  3. physical dispatch has not occurred;
  4. no covering non-CANCELLED Shipment has reached SHIPPED, DELIVERED, or UNDELIVERED;
  5. release has not previously been applied for that allocation; and
  6. if the Order Item is a current member of a PREPARING Shipment, its applicable membership resolution is included in the same atomic cancellation-and-release result so the cancelled Order Item does not remain planned for dispatch.
- Eligible cancellation, allocation release, and any required PREPARING Shipment membership or lifecycle resolution automatically form one atomic domain result. The release increases available quantity by exactly the Order Item's original confirmed quantity and is applied at most once.
- When such a PREPARING Shipment has at least one other valid current member after removal, the cancelled Order Item is removed inside that atomic result. When the cancelled Order Item is the final current member, the Shipment transitions to CANCELLED inside the same result and preserves its current membership as frozen history.
- No eligible ready-made cancellation may commit before its release and required PREPARING Shipment resolution and leave Shipment cleanup for a later operation. Rollback of any part rolls back the Order Item cancellation, allocation release, and related Shipment membership or lifecycle change.
- An already CANCELLED Order Item, a repeated cancellation command, or repeated event processing does not apply another release.
- Pre-dispatch cancellation and release serialize with a covering Shipment's transition to SHIPPED. If cancellation and release commit first, the Order Item cannot be shipped; if SHIPPED commits first, pre-dispatch release is unavailable. Both outcomes cannot commit for the same allocation.
- Shipment transition to SHIPPED, DELIVERED, or UNDELIVERED, Order Item transition to FULFILLED, and ordinary fulfillment or completion never decrement available quantity again and never increase it. Post-dispatch terminal non-delivery cancellation never releases the original allocation.
- Return-to-sender or other UNDELIVERED evidence does not itself restore stock because it does not prove physical receipt, inspection, sellable condition, or restocking acceptance.
- Payment never directly changes Ready-Made Product available quantity or releases a confirmed allocation. Payment-resolution failure may lead commerce workflow to attempt eligible Order Item cancellation, but stock changes only through the successful cancellation-and-release result defined here.
- A User with effective READY_MADE_PRODUCTS write authorization may apply an explicit non-zero integer manual quantity delta.
- A positive manual delta records additional verified free sellable units. A negative manual delta removes only free stock capacity, and the resulting available quantity must remain non-negative.
- Manual quantity adjustment performs an atomic current-state check and serializes with ready-made Order Item confirmation and eligible pre-dispatch release.
- Before its first application attempt, every manual quantity delta command receives a stable unique command identity that is immutably bound to the exact Ready-Made Product and exact signed non-zero delta.
- The first successfully accepted command applies its delta exactly once. Repeating the same identity with the same Ready-Made Product and delta returns or resolves to the already recorded result without changing available quantity again; reuse of that identity with another Product or delta is rejected.
- When the outcome of the first attempt is unknown to the caller, the platform first resolves the locally preserved result for the same command identity instead of applying a new delta. A fully rolled-back unsuccessful attempt is not considered applied.
- Manual quantity-delta command identity and result persistence do not introduce a Manual Adjustment, Stock Movement, Inventory Transaction, or other core entity. Exact persistence representation remains an implementation detail.
- A manual delta is not an input total that includes allocated units, does not rewrite confirmed allocation history, and does not change Ready-Made Product lifecycle.
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
- Available quantity is always a non-negative integer.
- Every successful ready-made Order Item confirmation decreases available quantity exactly once by the full confirmed Order Item quantity.
- The same available stock capacity is never confirmed for more than one Order Item.
- An eligible pre-dispatch cancellation release increases available quantity at most once by exactly the original confirmed Order Item quantity.
- Cancellation, its eligible pre-dispatch release, and any required PREPARING Shipment membership or lifecycle resolution always commit or roll back together.
- A covering Shipment cannot both commit SHIPPED and permit pre-dispatch release for the same confirmed allocation.
- Confirmed allocation identity and original quantity remain permanently preserved in Order Item history even after operational release or fulfillment.
- Shipment SHIPPED, DELIVERED, or UNDELIVERED and Order Item FULFILLED never change available quantity.
- Payment, refund, and return-to-sender evidence never directly change available quantity.
- Post-dispatch terminal non-delivery cancellation never restores the original confirmed allocation to available quantity.
- Manual quantity adjustment never rewrites confirmed allocation history and never makes available quantity negative.
- One manual quantity-delta command identity never changes its bound Ready-Made Product or signed non-zero delta and never changes available quantity more than once.
- A Ready-Made Product lifecycle remains independent from its stock availability.
- A Ready-Made Product remains independent from Project and Revision lifecycles.
- The platform-recognized commercial context of a Ready-Made Product is always derived from the owner of its Workspace in MVP.
- Workspace roles and permission scopes never change the Workspace owner or the commercial context of a Ready-Made Product.

## Notes

The simple available quantity and explicit manual integer delta are intentional MVP mechanisms. Confirmed allocation decreases available quantity once, while only the defined eligible pre-dispatch cancellation gate restores that quantity once. Post-dispatch terminal non-delivery cancellation never does. UNDELIVERED or return-to-sender evidence alone is not restock. Procurement, returns, physical receipt, inspection, restocking, and technical concurrency mechanisms remain future integration concerns involving Order, Shipment, Inventory, or related domains as applicable. Quantity may later move into an Inventory domain without changing Ready-Made Product identity.

Every Listing has exactly one immutable commercial source: either a FINALIZED Revision or a Ready-Made Product, never both. A source may have multiple Listings over time, but the Listing specification permits no more than one ACTIVE Listing for the same source in MVP.

Canonical or reference product media versus Listing promotional media remains to be designed. SKU uniqueness, brand and model metadata, physical shipping data, shipping promises, and offer identifiers also remain future boundaries.

Supplier, manufacturer, and importer traceability may be modeled later if required by procurement, compliance, safety, or marketplace rules. The manufacturer of an existing physical product is not the same concept as the Manufacturer assigned to a made-to-order Order Item.

No Product Variant entity exists in MVP. Future product-family or variant grouping may be introduced without changing the stable identities of existing Ready-Made Products.

Creastrix-first selling is platform policy rather than a Ready-Made Product invariant. Third-party seller verification, seller-of-record, and seller self-service remain future commercial concerns.

Order Items preserve the purchased-product and commercial snapshots required for history without depending on the current mutable presentation of a Ready-Made Product.

Destructive deletion means physical or domain removal of the stable Ready-Made Product identity such that existing references can no longer resolve it. It is distinct from ARCHIVED lifecycle, which remains reversible under current rules. Exact retention duration, legal deletion, privacy treatment, archival storage, and pseudonymization remain future legal and compliance work.

Future relational persistence must prevent destructive cascade deletion that would break Listing source or historical Order Item references. Exact database constraints remain implementation work.

Future executable implementation must prove coherent ACTIVE creation with required initial quantity, exact once-only confirmation decrement, exact once-only eligible pre-dispatch release, atomic PREPARING Shipment membership resolution inside cancellation and release, permanent allocation history, serialization of release against SHIPPED, absence of any post-dispatch or fulfillment quantity mutation, atomic manual-delta serialization against confirmation and release, and command-identity idempotency under duplicate, changed-payload, unknown-outcome, and fully rolled-back attempts. Exact locking, transaction-isolation, version-check, and persistence mechanisms remain implementation validation.

Significant creation, lifecycle, and quantity events may later be recorded through Audit Log behavior.

---

Status: APPROVED

Version: 1.0
