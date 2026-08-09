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
- maintaining simple available quantity in MVP.

## Relationships

A Ready-Made Product:

- belongs to exactly one Workspace;
- has exactly one immutable Created By User;
- may be targeted by zero or more Listings over time;
- may have stock allocated through zero or more ready-made Order Items;
- may be referenced by Audit Log events in the future.

## Business Rules

- A Ready-Made Product may be created or edited only by a User with effective write authorization for the READY_MADE_PRODUCTS scope.
- The Workspace relationship of a Ready-Made Product cannot be changed in MVP.
- The Workspace owner provides the platform-recognized commercial context in which the Ready-Made Product is managed.
- This commercial context does not prove legal title, physical custody, seller-of-record, manufacturer, supplier, importer, brand ownership, or intellectual-property ownership.
- Created By identifies only the User who created the Ready-Made Product record in Creastrix and does not determine ownership, commercial context, seller, manufacturer, supplier, importer, brand ownership, creative authorship, royalty rights, or Listing publication authority.
- Deactivation of the Created By User does not rewrite the Created By relationship.
- A Ready-Made Product has the lifecycle state ACTIVE or ARCHIVED and may transition from ACTIVE to ARCHIVED or from ARCHIVED to ACTIVE.
- An ACTIVE Ready-Made Product is operational and may participate in commerce subject to Listing, Order, and other applicable domain rules, but ACTIVE does not mean published or in stock.
- An ARCHIVED Ready-Made Product is not intended for new commercial use, retains required historical references, and may return to ACTIVE.
- A Ready-Made Product exists independently from Listing, may exist without a Listing, and is never published directly.
- A new Listing may be created for a Ready-Made Product only while the product is ACTIVE, and no more than one Listing for that product may be ACTIVE at the same time in MVP.
- If a Ready-Made Product becomes ARCHIVED, existing Listing lifecycle status does not change automatically, but the Listing becomes non-orderable. Orderability may recover after the product returns to ACTIVE when all other conditions hold.
- Available quantity changes never change Listing lifecycle.
- A Ready-Made Product represents product identity and product-defining physical characteristics, while public commercial offer and presentation concerns belong to Listing.
- One independently stocked physical configuration is represented by one Ready-Made Product in MVP.
- Available quantity represents whole physical units currently available for allocation from the single MVP stock pool.
- Available quantity does not determine lifecycle; an ACTIVE Ready-Made Product may have quantity zero, and an ARCHIVED Ready-Made Product may retain quantity greater than zero.
- Confirmation of a ready-made Order Item establishes allocation of its full quantity against the Ready-Made Product only when that quantity is currently available.
- If the full Order Item quantity cannot be allocated, Order confirmation fails and no partial confirmed Order is created.
- Allocation succeeds atomically at the domain level, available quantity never becomes negative, and the same stock capacity cannot be confirmed for more than one Order Item.
- A confirmed allocation remains associated with its Order Item until fulfillment or consumption, or until an applicable release.
- Successful cancellation may release a confirmed allocation when it is no longer required.
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
- Available quantity is always a non-negative integer.
- The same available stock capacity is never confirmed for more than one Order Item.
- A Ready-Made Product lifecycle remains independent from its stock availability.
- A Ready-Made Product remains independent from Project and Revision lifecycles.
- The platform-recognized commercial context of a Ready-Made Product is always derived from the owner of its Workspace in MVP.
- Workspace roles and permission scopes never change the Workspace owner or the commercial context of a Ready-Made Product.

## Notes

The simple available quantity is an intentional MVP model. Temporary reservation, payment-failure release, returns, restocking, manual adjustment, procurement, and technical concurrency mechanisms remain future integration concerns involving Order, Payment, Inventory, or related domains as applicable. Quantity may later move into an Inventory domain without changing Ready-Made Product identity.

Every Listing has exactly one immutable commercial source: either a FINALIZED Revision or a Ready-Made Product, never both. A source may have multiple Listings over time, but the Listing specification permits no more than one ACTIVE Listing for the same source in MVP.

Canonical or reference product media versus Listing promotional media remains to be designed. SKU uniqueness, brand and model metadata, physical shipping data, shipping promises, and offer identifiers also remain future boundaries.

Supplier, manufacturer, and importer traceability may be modeled later if required by procurement, compliance, safety, or marketplace rules. The manufacturer of an existing physical product is not the same concept as the Manufacturer assigned to a made-to-order Order Item.

No Product Variant entity exists in MVP. Future product-family or variant grouping may be introduced without changing the stable identities of existing Ready-Made Products.

Creastrix-first selling is platform policy rather than a Ready-Made Product invariant. Third-party seller verification, seller-of-record, and seller self-service remain future commercial concerns.

Order Items preserve the purchased-product and commercial snapshots required for history without depending on the current mutable presentation of a Ready-Made Product.

Significant creation, lifecycle, and quantity events may later be recorded through Audit Log behavior.

---

Status: DRAFT

Version: 0.4
