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
This checkout contains the unwired I1 pure policy plus I2A storage/generation
and named registration, user-security READ, SUPPORT-grant REVOKE and receipt
workflows. V12 provides bounded storage; owned READ COMMITTED transactions
couple required access-workflow audit and outcomes before disclosure.
See the [backend coverage and limits](backend/README.md#platform-access-policy-foundation).

Verification and integration status are established by separate delivery evidence.
Constructor-only workflows and synthetic trusted facts are not a staff HTTP API
or live session/MFA/step-up proof. Grant issuance, invitations,
CHANGE/SUSPEND/RESUME execution, maintenance and full Audit Log remain deferred;
no real owner, privilege, rollout or next slice is activated.
`AUTH-COOKIE-FOLLOWUP-001` remains OPEN, browser proof INCOMPLETE and S003
browser scenarios NOT RUN.

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
durable binding commits and newer login state. Residual late-success cookie risk,
including cooperative logout before HTTP commit, remains temporarily accepted
only for the local nonpublic pilot under `AUTH-COOKIE-FOLLOWUP-001` (OPEN).
It must be revisited during browser verification and before any public access,
external-user invitations or rollout; test counts and merge cannot close it.
Author verification, native IDE execution, independent re-review, PR CI and
post-merge CI are distinct evidence; none closes the cookie follow-up.
React/Redux authentication integration, its dev proxy and browser verification F are not implemented here;
the real Auth0 walkthrough P, tenant setup and rollout have not been performed.
Login does not create a Workspace. No Workspace/RMP HTTP API, linking, social
login or commerce implementation is included, and no working user-facing login
or complete authentication/MVP delivery is claimed.

## Frontend UI Foundation

FRONTEND-BOOTSTRAP-001 provides a separately built [frontend](frontend/README.md)
in this repository: React/TypeScript/Vite, Redux Toolkit for the shared DE/EN UI
preference, the selected 3C logo and neutral demo routes `/`, `/login`, `/account`.
This is a UI-only scaffold, not a production-ready account area. The demo account
is public and fictional; planned sign-in methods are disabled. A Workspace remains optional.
There is no backend/Auth0 connection, proxy, credential collection, real sign-in,
Workspace/RMP API or rollout. This does not verify authentication F/P or close
`AUTH-COOKIE-FOLLOWUP-001` (OPEN).

Using Node 22.14.0 / npm 10.9.2, run from `frontend`:

```sh
npm ci
npm run dev
```

The UI is local-only at `http://localhost:3000` (loopback, strict port).
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
