# Creastrix Glossary

> **Supporting terminology only.** This glossary explains terms used in Creastrix documentation. It does not approve architecture, replace individual specifications, or prove implementation coverage.

Use the [Domain Specifications Index](domain/README.md) for current specification statuses and the individual specifications for authoritative accepted decisions. See the [Repository README](../README.md) for current implementation orientation and [Creastrix Project Context](../creastrix-project-context.md) for technical memory and current direction.

## Governance and delivery

- **APPROVED** — A specification status for content that has completed independent review and approval. Approval applies to the accepted specification content, not automatically to later edits.
- **DRAFT** — A specification status for active architecture work that remains subject to review and approval.
- **PLANNED** — A recognized domain concept for which no active specification exists yet.
- **Accepted integrated repository baseline** — The repository state accepted through the governed delivery process as the current integrated implementation state. Working-tree and unreviewed or unmerged branch changes are proposed changes, not part of that baseline.
- **Implementation coverage** — The extent to which repository evidence implements accepted domain decisions. Repository presence alone does not prove deployment, full delivery, or completion of every approved rule or delivery task.

## Identity, participation, and access

Definitions linked to DRAFT specifications in this section are provisional and remain subject to approval in their own specifications.

- **[User](domain/user.md)** — The core individual identity that can authenticate and participate in Creastrix.
- **[User Profile](domain/user-profile.md)** — Personal information associated with a User, kept separate from authentication and account identity.
- **Buyer** — A role term for a User acting in a purchase context; it is not a separate core entity.
- **[Organization](domain/organization.md)** — A shared business participant that may own Workspaces and participate through authorized Users. Organization participation alone does not establish seller-of-record, merchant-of-record, beneficiary, payment-recipient, or payout identity.
- **[Organization Membership](domain/organization-membership.md)** — The relationship that records a User's participation in an Organization, including membership role and status. Ordinary Organization authority also requires an ACTIVE associated User, applicable role semantics, and every more specific domain rule.
- **[Workspace](domain/workspace.md)** — The proposed shared operational context for collaborative Project and Ready-Made Product work. Workspace ownership is distinct from participant access.
- **[Workspace Membership](domain/workspace-membership.md)** — The proposed relationship that records a User's scoped Workspace access relationship, status, role, and granted permission scopes and provides the basis for determining effective Workspace authorization. Membership alone does not automatically produce effective access; Workspace ownership and Organization Membership remain separate.
- **Designer** — A role term for a User or Organization acting in a design context; it is not a separate core entity.
- **[Designer Profile](domain/designer-profile.md)** — The proposed public professional design identity and publication capability of a User or Organization. It is not required for every Project or Revision action and does not by itself establish authorship, intellectual-property ownership, design-specific publication rights, or monetary beneficiary identity.
- **Manufacturer** — Domain shorthand for the Manufacturer Profile assigned to made-to-order work, not a separate core entity. Its Profile Holder is a User or Organization, but holding the profile or being its Profile Holder does not by itself establish seller, beneficiary, payment-recipient, or payout identity.
- **[Manufacturer Profile](domain/manufacturer-profile.md)** — The proposed manufacturing-capability identity held by a User or Organization and assignable to made-to-order work. It does not by itself establish seller, beneficiary, payment-recipient, or payout identity.
- **Profile Holder** — The User or Organization that holds a Designer Profile or Manufacturer Profile; the profile remains distinct from its holder.
- **Created By** — Provenance identifying the User who created a record. It does not automatically establish ownership, legal authorship, authority, beneficiary identity, or payout rights.

## Product and commerce

Definitions linked to DRAFT specifications in this section are provisional and remain subject to approval in their own specifications.

- **[Project](domain/project.md)** — The proposed stable manufacturable product concept developed within a Workspace, distinct from its technical Revisions and commercial Listings.
- **[Revision](domain/revision.md)** — The proposed versioned technical and design state of a Project.
- **[FINALIZED Revision](domain/revision.md)** — A Revision whose design content is frozen for downstream publication and commerce; this term describes Revision state rather than a separate entity.
- **[Ready-Made Product](domain/ready-made-product.md)** — The proposed independently stocked product configuration for existing-stock commerce. It can exist without a Listing.
- **[Listing](domain/listing.md)** — The proposed public commercial offer for an eligible Revision or Ready-Made Product; it is distinct from the offered source.
- **[Personalization](domain/personalization.md)** — The proposed buyer-specific private configuration associated with eligible Revision-based commerce, distinct from the Project, Revision, and Listing.
- **[Order](domain/order.md)** — The proposed confirmed purchase record that groups its purchased Order Items and preserves buyer and monetary context.
- **[Order Item](domain/order-item.md)** — The proposed confirmed purchased line that preserves its source, quantity, commercial snapshot, and fulfillment history.
- **Fulfillment path** — The conceptual route by which an Order Item is fulfilled: made-to-order from a Revision or existing-stock fulfillment from a Ready-Made Product. It is not a separate core entity.
- **Seller-of-record** — The party contractually presented as the seller to the Buyer for an Order. It is not a synonym for Workspace owner, Organization, Designer Profile, Manufacturer Profile, beneficiary, or payout recipient.
- **Merchant-of-record** — The party responsible for the buyer-facing payment transaction and related merchant obligations for an Order. It is distinct from Workspace ownership, profile holding, beneficiary identity, and payout receipt.
- **[Payment](domain/payment.md)** — The proposed durable attempt to collect buyer funds for an Order, including accepted provider-correlated collection history.
- **[Payment Allocation](domain/payment-allocation.md)** — The proposed immutable attribution or reversal of accepted captured buyer funds; it is not a Payment, Royalty, Payout, or proof of earnings.
- **[Royalty](domain/royalty.md)** — The proposed immutable designer-rights accrual recognized from qualifying confirmed and captured commerce, kept separate from captured-funds attribution and outbound transfer.
- **[Payout](domain/payout.md)** — The proposed durable outbound transfer attempt for an eligible User or Organization beneficiary; it is not a balance, Royalty, or Payment.
- **[Shipment](domain/shipment.md)** — The proposed physical-delivery grouping and history for Order Items under a shared fulfillment context; it does not determine payment or ownership.
