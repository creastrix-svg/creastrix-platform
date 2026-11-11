# Creastrix

<a id="vision"></a>

Creastrix is a storefront for discovering physical products, personalising eligible items and exploring designs for manufacture. This repository contains a React buyer interface and a Java backend. You can try the shopping journey locally without creating an account.

## Try the storefront

Use Node **22.14.0** and npm **10.9.2**. From the repository root:

```sh
cd frontend
npm ci
npm run build
npm run preview -- --port 4173 --strictPort
```

Open [the homepage](http://localhost:4173/) or [the catalog](http://localhost:4173/catalog). This preview binds to loopback and has **no backend proxy**. It does not place orders, charge a payment method, or sign you in. For live editing or the separately configured local account pilot, use the [frontend guide](frontend/README.md#local-use).

<a id="current-state"></a>

## What works today

| Area | Implemented | Boundary |
| --- | --- | --- |
| Shopping | Homepage, catalog, local search, product pages, variants, media, saved products, cart and checkout preview | Prices, stock and images come from fixtures. A checkout preview is not an order. |
| Personalisation | A product can offer configured zones, text and artwork with visual previews. Applied designs can become separate cart lines. | Drafts and cart lines are saved in the browser, not in an account. Neither a visual preview nor an AI idea field is a manufacturing service. |
| Accounts | A local, owner-only account pilot uses hosted Cognito sign-in, email confirmation/recovery and the backend session. | Cognito registration alone does not grant access. No email-based account merging or public rollout. |
| Backend | User, organization, workspace, ready-made inventory and scoped staff-policy foundations, with JDBC/PostgreSQL enforcement. | Not a storefront catalog/cart API, a live staff console, an order system or a payment integration. |

The buyer interface is integrated through [PR #45](https://github.com/creastrix-svg/creastrix-platform/pull/45). Sample products illustrate different capabilities; they are not platform entities or permanent navigation sections.

<a id="repository-guide"></a>
<a id="architecture-and-technology"></a>

## Where to work

- [Frontend](frontend/README.md): run, test and locate the buyer or account feature.
- [Backend](backend/README.md): prerequisites, local configuration, tests and package structure.
- [Project context](creastrix-project-context.md): current delivery boundaries, decisions and open questions.
- <a id="approved-domain-specifications"></a><a id="remaining-draft-domain-areas"></a>[Documentation index](docs/index.md) and [domain specifications](docs/domain/README.md): product vocabulary and the approval status of each domain.
- [Team engineering conventions](creastrix-team-code.md) and [contributor instructions](AGENTS.md): code comments, review, verification and integration.

## Verification and history

[Frontend CI](.github/workflows/frontend-ci.yml) and [backend CI](.github/workflows/backend-ci.yml) check their respective changes. A passing build is not a deployment. Historical evidence and retained limits are in the [engineering record](docs/engineering/integration-history.md).

<a id="frontend-ui-foundation"></a>
<a id="s003-cookie-pairing"></a>
<a id="integrated-authentication-backend-pilot"></a>
<a id="controlled-cognito-migration-candidate"></a>
<a id="integrated-cognito-migration-and-auth0-retirement"></a>

For authentication, see the [client contract](frontend/docs/account-pilot.md), [local pilot and public-readiness gate](docs/authentication-pilot.md#open-cookie-follow-up) and [provider history and migration procedure](docs/authentication-provider-migration.md).

<a id="implemented-backend-foundations"></a>
<a id="integrated-foundation-concurrency-corrections"></a>
<a id="integrated-runtime-dependency-update"></a>
<a id="platform-access-policy-foundation"></a>

For the backend foundations, see [coverage](backend/README.md#implemented-domain-foundation), [transaction isolation](backend/README.md#foundation-write-isolation-contract-and-v9-upgrade), [dependency overrides](backend/README.md#runtime-dependency-overrides) and [platform access](backend/README.md#platform-access-policy-foundation).
