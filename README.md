# Creastrix

> AI Design & Manufacturing Platform

Status: Active early development focused on the backend domain foundation

Version: 0.1.0

## Vision

Creastrix transforms user ideas into manufacturable products using artificial intelligence.

## Current State

Specification approval and implementation coverage are tracked separately.

### APPROVED Domain Specifications

User, User Profile, Organization, Organization Membership, Workspace, Workspace Membership, Ready-Made Product, and [Platform Access Grant](docs/domain/platform-access-grant.md) have APPROVED domain specifications. Ready-Made Product is currently APPROVED 1.1; Platform Access Grant is APPROVED 1.1. Approval records the accepted architecture for those entities; it does not mean that every approved rule is already implemented.

### Implemented Backend Foundations

The current backend foundation covers:

- the executable backend bootstrap;
- the User and mandatory User Profile foundation;
- the User repository port with an explicit JDBC persistence adapter;
- the Organization and Organization Membership foundation;
- the Workspace and Workspace Membership structural foundation, including
  User-owned and Organization-owned creation with an atomic initial `ACTIVE`
  `ADMIN` Membership;
- the Workspace repository port with an explicit JDBC persistence adapter and
  V4 PostgreSQL structural-invariant enforcement;
- the Ready-Made Product structural foundation (IMPLEMENTATION 005A) as partial
  implementation coverage of APPROVED 1.1: a stable UUID identity, exactly one immutable
  Workspace, exactly one immutable Created By User, stored `ACTIVE` / `ARCHIVED`
  state, and a non-negative integer available quantity including zero;
- atomic initial `ACTIVE` Ready-Made Product creation with creation-time
  validation of an `ACTIVE` creator User and effective Workspace-layer
  `READY_MADE_PRODUCTS` write authorization;
- the Ready-Made Product repository port, explicit JDBC adapter, Flyway V6,
  independent PostgreSQL creation gate and structural enforcement, with
  concurrency and rollback boundaries covered by integration tests;
- bounded Ready-Made Product archive and activate operations (IMPLEMENTATION 005B)
  for `ACTIVE` → `ARCHIVED` and `ARCHIVED` → `ACTIVE` through separate public application
  methods, without a generic caller-supplied target status;
- lifecycle authorization for an `ACTIVE` actor User with effective Workspace
  `READY_MADE_PRODUCTS` write access, a final persisted-status decision after
  locking the Product row, expected-state updates, and Flyway V7 structural
  transition enforcement, including same-state rejection and rollback and
  concurrency integration coverage;
- durable manual quantity-delta commands (IMPLEMENTATION 005C) for both `ACTIVE`
  and `ARCHIVED` Products, through separate registration and application/retry/replay
  service operations; external Spring-proxy calls use `REQUIRES_NEW` for independent
  commit boundaries, and registration does not change quantity or trigger application;
- composite `(Product ID, Command ID)` identity, immutable non-zero signed 64-bit
  delta binding, permanent `REGISTERED` / `APPLIED` / `REJECTED` persistence through
  the explicit JDBC adapter, and Flyway V8 structural enforcement;
- current Product authorization before command lookup or disclosure, checked
  arithmetic with terminal `UNDERFLOW` / `OVERFLOW`, atomic quantity and terminal
  outcome persistence, and historical terminal replay without reapplying the delta.

This is a partial foundation, not a complete Ready-Made Product or MVP implementation or full delivery of every behavior in the approved specifications.

IMPLEMENTATION 005A, IMPLEMENTATION 005B, and IMPLEMENTATION 005C remain partial
implementation coverage of Ready-Made Product APPROVED 1.1.
Allocation persistence is absent, so reachable outstanding allocated quantity is
zero and accounted physical quantity currently equals available quantity.
Integration of accounted quantity with allocation, release, and dispatch remains
unimplemented; manual delta is not a complete stock or commerce workflow.

`REQUIRES_NEW` can require an additional connection when an outer transaction holds
one, and an outer transaction holding locks needed by the inner operation can
prevent completion. Independent commit boundaries do not remove those constraints.

The integrated local authentication pilot is described below; authenticated
Workspace/RMP HTTP APIs remain unimplemented. Raw SQL is structurally constrained but not actor-authorized, migration/table-owner
privileges are not separated from the runtime database role, and global
deadlock freedom or a general SQLSTATE `40P01` retry policy is not established.
Deferred behavior includes generic editing, Order Item confirmation-time
allocation, eligible pre-dispatch exact release,
dispatch accounting and serialization with Shipment `SHIPPED`, Listing, Order,
Order Item, Shipment, Payment and other commerce integrations, list, search and
paging, and a Ready-Made Product HTTP API. This documentation step does not select or begin a next
implementation slice.

### Integrated Foundation Concurrency Corrections

Foundation V9 / PROD-002 and V10 / PROD-001 are integrated separately from the
Ready-Made Product IMPLEMENTATION 005A/V6, 005B/V7, and 005C/V8 slices.
V9 enforces a `READ COMMITTED`-only write contract on `organizations`,
`organization_memberships`, `workspaces`, and `workspace_memberships`, rejecting
unsupported isolation. V10 fixes the reproduced FK-lock upgrade deadlock in
the tested concurrent Workspace creation scenarios while preserving deferred
validation. Neither correction establishes global deadlock freedom or a universal
retry policy. See the [backend concurrency details](backend/README.md#foundation-write-isolation-contract-and-v9-upgrade)
for the verified Flyway atomicity/advisory-locking boundary and Hikari limitations.

[PR #24](https://github.com/creastrix-svg/creastrix-platform/pull/24) and its
[post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/34280907486)
confirm the integrated baseline: 426 tests, zero failures/errors/skipped, tests
and package `BUILD SUCCESS`, PostgreSQL `18.4-alpine`, and Flyway V1 → V10.
PROD-001 and PROD-002 are fixed within their agreed boundaries, not a closure of
FULL AUDIT 001 or proof that other risks are absent. The accepted MIN-01 coverage
limitation remains; coverage is not complete. Integration is not rollout:
user and external databases were not updated.

### Integrated Runtime Dependency Update

[PR #26](https://github.com/creastrix-svg/creastrix-platform/pull/26) integrated
four explicit POM overrides affecting nine runtime artifacts; Boot/Spring and
the remaining resolved coordinates are unchanged. See the
[runtime override details](backend/README.md#runtime-dependency-overrides).
Independent compatibility verification preceded integration; the subsequent
[post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/34392178931)
confirmed 426 tests, zero failures/errors/skipped, and tests/package `BUILD SUCCESS`.
SC-01 is fixed in main within the agreed dependency-update scope, not a claim
that the entire dependency graph is free of vulnerabilities. External deployments
and running applications were not updated; integration is not rollout.

### Platform Access Policy Foundation

Platform Access Grant APPROVED 1.1 is accepted architecture, not activation.
Main contains the unwired I1 pure policy plus integrated I2A storage/generation
and named registration, user-security READ, SUPPORT-grant REVOKE and receipt
workflows. V12 provides bounded storage; owned READ COMMITTED transactions
couple required access-workflow audit and outcomes before disclosure.
See the [backend coverage and limits](backend/README.md#platform-access-policy-foundation).

[PR #33](https://github.com/creastrix-svg/creastrix-platform/pull/33) integrated this
bounded coverage; its 2026-10-05
[post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37312461778)
passed 1513 tests and tests/package BUILD SUCCESS. These are historical results,
not new runs by this documentation change.
Constructor-only workflows and synthetic trusted facts are not a staff HTTP API
or live session/MFA/step-up proof. Grant issuance, invitations,
CHANGE/SUSPEND/RESUME execution, maintenance and full Audit Log remain deferred;
no real owner, privilege, rollout or next slice is activated.
The [local cookie disposition and retained public gate](docs/authentication-pilot.md#open-cookie-follow-up) records scoped local closure. Integrated S003 and its distinct
scoped browser evidence are described in the
[authentication boundary](docs/authentication-pilot.md#s003-evidence-and-open-gates);
they do not activate Platform Access workflows.

### Remaining DRAFT Domain Areas

The downstream Listing, Order Item, Shipment, and other remaining DRAFT domain
areas are unimplemented and require their own independent specification
approval before ordinary production implementation. Neither the Workspace and
Workspace Membership structural foundation nor Ready-Made Product approval
approves any downstream DRAFT specification.

### Integrated Authentication Backend Pilot

AUTH-FIRST-LOGIN-001 is integrated in main through
[PR #28](https://github.com/creastrix-svg/creastrix-platform/pull/28). It implements a bounded
server-side OIDC/session backend, exact external identity binding through V11,
and the account/CSRF/login/logout HTTP contract for a single-instance local
nonpublic pilot. Author-side native IDE verification and independent R2 re-review
are complete. The separate
[post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/36115810224)
passed 658 tests, zero failures/errors/skipped, tests/package BUILD SUCCESS,
PostgreSQL 18.4 and Flyway V1 → V11; see the exact merge and historical evidence in the
[authentication contract and verification boundary](docs/authentication-pilot.md).
No tests were rerun by this documentation change. The owner's 2026-09-14 local-pilot
contract bounds publication to a short client/session/context/outcome section,
not physical HTTP commit or browser delivery. The R2 remediation targets only the
rejected attempt's session cookie on an uncommitted failure response and preserves
durable binding commits and newer login state. That integrated R2 fix alone did
not prevent the separate late-success cookie effect. Its 2026-09-14 temporary
local-pilot acceptance is historical, not automatic acceptance of the subsequently
observed B-to-A account selection or permission for public access.
Author verification, native IDE execution, independent re-review, PR CI and
post-merge CI are distinct evidence; none closes the cookie follow-up.
The bounded React/Redux client, dev proxy and finite local browser F are now
independently reviewed and integrated through PR #35, as summarized below.
A separate bounded real Auth0 owner walkthrough P completed on 2026-10-11
(registration/verification, local logout, repeat login and password reset with the same internal User).
That historical external evidence is not a Cognito run or public rollout.
Login does not create a Workspace. No Workspace/RMP HTTP API, linking, social
login or commerce implementation is included. Verified local login with a synthetic
IdP is not complete authentication/MVP delivery or real-provider verification.

### S003 Cookie Pairing

S003 is integrated through [PR #34](https://github.com/creastrix-svg/creastrix-platform/pull/34).
Its separate [post-merge CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37917170767)
passed 1618/1618 and tests/package BUILD SUCCESS on 2026-10-09; these are historical
server results, not a new frontend/browser run. An immutable process-local Q/session stamp is captured before current-User lookup
and checked again at final admission. G1 rejects a revoked or superseded
same-Q stamp. G2 checks the current cookie jar's Q/SID pairing: a late A success
cookie can require 401 and explicit recovery, rather than silently selecting A;
uninterrupted availability of B is not promised.

L1 remains explicit: an earlier request under a different still-live Q_A can
first pass final admission after B was accepted under Q_B and return A. This is
not a request already admitted before B, a global revocation guarantee or proof
for future business writes. Already-admitted responses may also finish later.
See the [cookie contract](docs/authentication-pilot.md#s003-cookie-pairing-contract)
for one-time issuance, logout/recovery, resource bounds and frontend obligations.

The exact isolated candidate passed 1618 tests in 32 suites, zero
failures/errors/skipped, and package BUILD SUCCESS on 2026-10-07. Separate root
dispositions accepted scoped browser and server evidence while retaining the
original incomplete/failed orchestration reports. These are dated external
evidence, not fresh IDE or CI results for this documentation change. The subsequent
finite local React/dev-proxy/browser F is described below. The later bounded Auth0 P
and the 2026-10-13 local Cognito owner transfer are separate evidence; neither
closes the follow-up or constitutes public rollout.
See the [local cookie disposition and retained public gate](docs/authentication-pilot.md#open-cookie-follow-up). Scoped local closure does not authorize public access.

## Frontend UI Foundation

FRONTEND-BOOTSTRAP-001 provides a separately built [frontend](frontend/README.md)
in this repository. [PR #35](https://github.com/creastrix-svg/creastrix-platform/pull/35)
integrated a bounded client: hosted-login native form, owned `/api/me` id/status
display, local logout/switch and explicit recovery through a fixed development
proxy. Fictional account data is removed; Redux private state is memory-only.
DE/EN, the selected 3C logo, keyboard support and voluntary Workspace messaging
remain. A non-secret shared intent marker and synchronous private-view curtain
reject stale UI results; neither is server authorization or global login ordering.
G1/G2 and the cross-Q L1 limitation remain; suppressing an old response is not
undoing its server effects or making future business writes safe.

Independent review accepted the finite local F matrix using actual React/Vite,
the integrated backend, a synthetic IdP and isolated native Chrome evidence.
This is separate from source/unit/build and native IDE results, not proof of every
browser or delivery order. The 2026-10-09
[post-merge Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37962373086)
passed 135 tests, typecheck, lint and build; CI does not execute that browser matrix.
Its npm installation reported one HIGH warning; that historical result is retained.
See the [lockfile follow-up and installed-environment limits](frontend/README.md#dependency-follow-up)
for the accompanying `source-map-js` 1.2.2 update and isolated verification.
The bounded Auth0 owner P completed separately. The local owner was subsequently
transferred to Cognito on 2026-10-13; social login, Workspace/RMP API/forms,
public operation and rollout remain absent.
The original cookie follow-up is closed only for the accepted local pilot scope;
`AUTH-PUBLIC-READINESS-001` is retained OPEN in the [local cookie disposition and retained public gate](docs/authentication-pilot.md#open-cookie-follow-up).

### Controlled Cognito Migration Candidate

The Cognito migration and email-confirmation recovery source is integrated by
[PR #37](https://github.com/creastrix-svg/creastrix-platform/pull/37) as `e5b58d43a9dc07c76efc905bc63d8269feaa40fe`. Its exact reviewed tree was preserved;
separately authorized local operator actions remain distinct from integration.
That integrated slice added a selected AUTH0/COGNITO registration,
Cognito signed-ID-token-only validation and existing-only owner resolution.
At PR #37 integration, Auth0 remained the source default. V13 adds one
current pair per User, immutable historical pairs and exact migration receipts;
the offline operation must preserve the internal UUID/Profile/status/generation
and existing rights. Unknown or superseded owner identities do not create Users.
See the [migration runbook](docs/authentication-provider-migration.md).

On 2026-10-13 a separately authorized local operation verified backup/restore,
applied V13 and acknowledged one owner switch with independent read-back. It
retained the same User UUID/Profile/status/generation/rights and business rows,
both historical pairs and one receipt. The local existing-only runtime admits
only the owner's exact Cognito identity. Real owner login, local logout (204
then `/api/me` 401), repeat login and login after the owner-reported password
change returned the same ACTIVE account. That operation retained the Auth0
tenant/client/account and private configuration, not a current authentication alias.

The owner authorized an Auth0 source-retirement candidate on 2026-10-14.
In that candidate, Cognito Frankfurt is the only supported runtime adapter;
provider/default admission settings are `COGNITO` and existing-only. Legacy
`AUTH0` configuration fails closed and its entry/callback routes are unsupported.
This is a new candidate, not an integrated or browser-verified retirement result.
The internal account and retained identity history need no new transfer. Standard
OIDC and V13's controlled exact-identity operation remain available as foundations
for a future separately reviewed provider adapter, not automatic account linking
or password export. Separately, the owner-approved external Auth0 application
Creastrix Local Pilot was deleted on 2026-10-14; tenant, Default App, users and
private configuration remain. See the [separate operator evidence](docs/authentication-provider-migration.md#2026-10-14-auth0-retirement-boundary).

External Cognito self-registration remains distinct from closed Creastrix
admission. Real unverified/admitted backend refusal is **NOT RUN**; hosted-provider
UNCONFIRMED refusal is not that signed-claim proof. On 2026-10-13 the owner explicitly
accepted the narrower combination of verified owner login, UNCONFIRMED/unadmitted
provider refusal and synthetic admitted raw-Boolean controls for owner-only local
pilot source readiness. This is not public signup/admission expansion or new-code
approval; see the [limited evidence disposition](docs/authentication-provider-migration.md#limited-email-gate-disposition).
Historical synthetic/independent runtime checks and native 417 tests retain their
own scopes; overall IDE schema/background completion remains UNKNOWN/INCOMPLETE.
PR #37 publication/integration and its post-merge CI are complete; own SES,
public access and rollout are not delivered. Cookie closure remains scoped locally
as recorded in the [local cookie disposition and retained public gate](docs/authentication-pilot.md#open-cookie-follow-up).

The integrated recovery slice adds a transient `/confirm-email` page, reachable from `/login`
or directly after closing the provider confirmation page. Anonymous selected-Cognito
confirmation/resend require exact Host/Origin and fresh standard session CSRF,
not a live Q/echo prerequisite. They use only bounded backend ConfirmSignUp and
ResendConfirmationCode calls with the existing confidential client. No password,
signup, automatic login, identity linking or internal account/Workspace creation
is added. Confirming a provider email does not grant Creastrix access: a fresh
ordinary OIDC login and exact admission remain necessary. Author tests alone do
not prove real recovery/browser execution.

Fresh isolated recovery author checks passed focused 528/528, diagnostic catalog
163/163, full 1826/1826 and package; frontend 234/234, typecheck, lint and build.
All preceding backend 1690 and frontend 140 testcase identities are preserved.
These remain source/mock/owned-PostgreSQL results, not native/browser or CI proof.
Separate scoped native, independent source/JAR review and owner recovery/fresh
OIDC results on 2026-10-14 are recorded in the
[dated recovery evidence](docs/authentication-provider-migration.md#2026-10-14-scoped-recovery-evidence).
Overall IDE background/schema remains UNKNOWN/INCOMPLETE. Separate 2026-10-14
post-merge [Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930693)
passed 1826/1826 and tests/package BUILD SUCCESS; [Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930663)
passed 234/234, locked install, typecheck, lint and build. These are CI results,
not reruns of browser/native proofs. Public-operation gates remain separate.

Using Node 22.14.0 / npm 10.9.2, run from `frontend`:

```sh
npm ci
npm run dev
```

Connected development requires exactly `http://localhost:3000` (loopback, strict port)
and the separately configured loopback backend. Build preview is disconnected and
disables authentication; it is not a deployment service.
Separate checks: `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`.
Frontend CI runs locked install, typecheck, lint, tests and build for changes under
`frontend/**` or to `.github/workflows/frontend-ci.yml`; backend CI has separate filters.

## Architecture and Technology

- Java 25 and Spring Boot;
- modular monolith;
- Maven Wrapper;
- PostgreSQL with Flyway migrations;
- explicit JDBC repositories;
- PostgreSQL integration tests based on Testcontainers.

## Repository Guide

- [Project context](creastrix-project-context.md)
- [Team code](creastrix-team-code.md)
- [Domain specifications](docs/domain/README.md)
- [Backend](backend/README.md)
- [Frontend UI scaffold](frontend/README.md)
