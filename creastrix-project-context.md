# Creastrix Project Context

Creastrix connects buyers, designers and makers. The buyer should be able to discover an item, see exactly what can be customised and understand what they are buying. The frontend can demonstrate this journey before the corresponding commerce services exist.

## Current implementation state

- **Storefront:** the homepage, catalog, product pages, studio, favourites, cart and checkout preview share the storefront introduced in [PR #45](https://github.com/creastrix-svg/creastrix-platform/pull/45). The default build uses fixtures; the optional connected build reads catalog offers and recommendations from local PostgreSQL through the backend. Drafts and cart lines stay in IndexedDB and favourites in localStorage. Checkout does not reserve stock or guarantee a price. See the [frontend guide](frontend/README.md).
- **Authentication:** the owner-only, single-instance local pilot uses Cognito Frankfurt and a server-side session. Admission uses the current exact issuer/subject, not an email match. Auth0 is historical, not an active provider. Sign-in does not create a workspace or confer staff rights. Email confirmation, account transfer and public admission are separate operations. See the [pilot](docs/authentication-pilot.md) and [provider runbook](docs/authentication-provider-migration.md).
- **Backend:** the modular monolith implements the User, Organization and Workspace foundations and part of Ready-Made Product: creation, archive/activate and durable manual quantity deltas. The optional [local catalog](docs/local-catalog.md) is read-only and separate from those domain tables. Platform-access policy and storage workflows exist without a live admin interface. Account carts, guest-account cart merge, ordering, checkout stock allocation and payments are not implemented. See [implementation coverage and limits](backend/README.md#implemented-domain-foundation).
- **Readiness:** the original cookie follow-up has a local-only disposition. Public access, staff use, business writes and multi-instance deployment have a separate [open readiness gate](docs/authentication-pilot.md#open-cookie-follow-up). Historical test results do not clear that gate. Do not infer current external provider configuration or deployment state from Git.

Working-tree changes and feature branches are proposals. Inspect the accepted main baseline before describing a capability as integrated; review, CI, local operation and rollout are different events.

The local session contract still permits an already-sent request under a different, still-live generation to receive its first admission after a newer login. Discarding stale UI responses is not a guarantee for future business writes. See the [session pairing contract](docs/authentication-pilot.md#s003-cookie-pairing-contract) before changing account or write flows.

## Domain decisions

The [domain index](docs/domain/README.md) tracks status per entity. User, User Profile, Organization, Organization Membership, Workspace, Workspace Membership, Ready-Made Product and Platform Access Grant have approved specifications. Only the latest independently approved version establishes accepted domain rules; approval does not imply full implementation. Listing, Revision, Personalization, Order, Payment and other draft domains do not become approved by being referenced here.

Read the [domain decision notes](docs/engineering/domain-decision-notes.md) for cross-entity relationships, invariants, financial and fulfillment boundaries. They supplement, never replace, the individual specifications.

## Product direction

- The owner selected a [persistent catalog and private buyer design store](docs/engineering/account-cart-activation.md#accepted-storage-decision) for the product card and account cart. The decision sets file lifetimes, not an account-cart launch or a change to the authentication pilot.
- Import and same-session recovery may be implemented and tested under a [separate local synthetic sign-in](docs/engineering/account-cart-activation.md#accepted-local-test-sign-in-write-scope). This is not authorisation to enable writes for live Cognito accounts or the public storefront.
- A [two-buyer local check](docs/engineering/account-cart-activation.md#two-local-buyers-never-guess-the-owner) is authorised for the ambiguous-ownership case. A later buyer must never inherit an unproven guest contribution.

- Work locally on buyer scenarios through the database, backend and existing
  frontend together. Loop and Tide are the first two catalog records, not
  separate page designs. The [local catalog contract](docs/local-catalog.md)
  is a read-only step; it does not change Listing approval or fulfillment rules.
- Creastrix is the single buyer-facing seller and merchant for the current MVP direction. Seller self-service and third-party fulfillment are later decisions.
- A product's fulfillment format and its optional personalisation are distinct. In the storefront a ready-made product can be purchased plain or personalised if its configured areas permit it. Design concepts alone are not purchasable offers. Product names, photos and sample prices are fixtures, not platform rules.
- The product author configures areas, allowed operations and tools. The buyer works within those areas. The studio can preview text and artwork, including visual cut/engraving examples, but cannot certify a production cut, generate manufacturing files or promise AI generation. See the [owner's configured-area decision](docs/frontend-personalization-zones.md#accepted-direction-for-existing-stock-products).
- That UI direction does not amend Ready-Made Product APPROVED 1.1 or approve the Personalization/Listing/Revision drafts. The hybrid workflow for decorating existing stock requires separate architecture reconciliation before backend implementation.

## Next steps and gates

1. Keep local storefront iteration separate from production commerce contracts. Selecting a demo variant or applying a design is not reserving stock or placing an order.
2. Select the next backend slice explicitly and review the relevant specifications first. Approval of a stock entity alone does not authorise Listing, shipment, checkout or account-cart integration.
3. Financial implementation requires its own decisions; in particular, a concrete approved, versioned release policy is required before functional Payout work.
4. Preserve the [engineering history](docs/engineering/integration-history.md) and dated evidence when updating this summary. Record what changed once, in its authoritative location.

<a id="controlled-cognito-owner-migration-accepted-decision-author-wip"></a>
<a id="auth0-source-retirement-candidate"></a>

For the historical [controlled owner transfer](docs/engineering/integration-history.md#integrated-controlled-cognito-owner-migration-and-recovery) and [Auth0 source retirement](docs/engineering/integration-history.md#integrated-auth0-source-retirement), use the dated record. For today's developer workflow see the [team code](creastrix-team-code.md) and [agent instructions](AGENTS.md).
