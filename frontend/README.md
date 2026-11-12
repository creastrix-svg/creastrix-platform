# Creastrix frontend

The buyer storefront and the local account pilot are two journeys in one React application. The storefront works without a server or sign-in. Connected login requires a separately configured local backend; it does not save the guest cart to an account.

## Local use

Use **Node 22.14.0** and **npm 10.9.2**. From the repository root:

```sh
cd frontend
npm ci
```

**Work on the interface:** run `npm run dev` and open [the storefront](http://localhost:3000/). Vite binds to loopback on a strict port. For the connected account pilot, use the exact origin `http://localhost:3000` and follow the [backend runbook](../backend/README.md#connected-local-authentication-pilot). Do not put provider credentials in frontend configuration.

**Show the built interface without connecting authentication:**

```sh
npm run build
npm run preview -- --port 4173 --strictPort
```

Open [the built storefront](http://localhost:4173/). The preview serves `dist/`, has no backend proxy and is not a production deployment. Prices, stock, personalisation fees and offers in this storefront are local examples. Checkout neither places an order nor takes payment.

To run the existing catalog and product cards against PostgreSQL instead,
use the separate [connected local catalog](../docs/local-catalog.md) mode.

## Find a feature

| Change | Start here |
| --- | --- |
| Homepage campaigns and product discovery | [Home](src/product/home/) |
| Catalog cards, filters, sorting, search results and design concepts | [Browse](src/product/browse/) |
| Cart lines, totals and undo | [Cart](src/product/cart/) |
| Local checkout preview and its transient details | [Checkout](src/product/checkout/) |
| Saved products | [Favorites](src/product/favorites/) |
| Help and information screens | [Info](src/product/info/) |
| Product reviews and review form | [Reviews](src/product/reviews/) |
| Product photos, 3D viewing and media fallbacks | [Media](src/product/media/) |
| Personalisation tools, design documents, preview and local saves | [Studio](src/product/studio/) |
| Local example products and the catalog adapter | [Product fixtures](src/product/fixtures/) and [catalog mapping](src/product/browse/catalogFixtures.ts) |
| Shared product layout | [ProductCardLayout.tsx](src/product/detail/ProductCardLayout.tsx) |
| Storefront shell and URL wiring | [ProductExperience.tsx](src/product/ProductExperience.tsx) |
| Sign-in and account screens | [App.tsx](src/App.tsx) and the [client contract](docs/account-pilot.md) |

Keep tests, styles and interface copy near the feature they exercise. A product name is appropriate in example data, media and compatibility URLs, not as a condition in general catalog rules. The catalog matches supplied offers; the product detail routes and local storage formats still have fixture-specific adapters. Adding a browse record does not automatically create a purchasable product page. Preserve existing links, stored browser keys and cart-line identities when moving code.

<a id="buyer-homepage-and-catalog-local-preview"></a>
<a id="product-experience--frontend-product-001"></a>
<a id="personalisation-studio--interface-prototype"></a>

## Follow a buyer journey

Start at `/` → `/catalog` → a product page. Buy as offered or open the studio if the product supports personalisation. Applying a design makes a snapshot; adding it to the cart does not change a previously added, different design. Go to `/preview/cart`; `/preview/checkout` is a local preview only.

[Storefront behaviour and fixtures](docs/storefront.md) covers search, languages, stock limits, image/model fallbacks and navigation. [Studio and persistence](docs/personalisation.md) describes allowed zones, artwork/fonts, cart snapshots and storage limits. The [domain boundary](../docs/frontend-personalization-zones.md) explains why visual personalisation is not a production specification.

## Verify a change

From `frontend/` run the same checks used by [frontend CI](../.github/workflows/frontend-ci.yml):

```sh
npm run typecheck
npm run lint
npm test
npm run build
```

For media or navigation changes, also exercise the affected path in a browser. Unit tests do not render WebGL or prove a browser session flow. Generated media has separate [studio fixture checks](docs/personalisation.md). No account, live provider or Docker is needed for frontend unit checks.

<a id="dependency-follow-up"></a>
<a id="selected-provider-boundary-author-wip"></a>
<a id="email-confirmation-recovery-author-wip"></a>
<a id="integrated-selected-provider-boundary"></a>
<a id="integrated-email-confirmation-recovery"></a>

**Account pilot references:** [dependency follow-up](docs/account-pilot.md#dependency-follow-up), [Cognito boundary](docs/account-pilot.md#integrated-selected-provider-boundary), [email confirmation and resend](docs/account-pilot.md#integrated-email-confirmation-recovery), and [public-readiness gate](../docs/authentication-pilot.md#open-cookie-follow-up).

<a id="bounded-client-behavior"></a>
<a id="ownership-and-lifecycle"></a>
<a id="fixed-development-transport-and-limits"></a>
<a id="verification-and-remaining-gates"></a>

For the [account state model](docs/account-pilot.md#bounded-client-behavior), [request ownership](docs/account-pilot.md#ownership-and-lifecycle), [development proxy](docs/account-pilot.md#fixed-development-transport-and-limits) and [verification history](docs/account-pilot.md#verification-and-remaining-gates), see the account guide.
