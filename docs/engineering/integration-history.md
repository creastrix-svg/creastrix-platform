# Engineering integration history

This is a dated record of earlier delivery slices, not a description of what is running now. Branch names, test counts, “current” wording and outstanding gates below belong to their original checkpoints. For today's scope, start with [project context](../../creastrix-project-context.md). For decisions about operating the authentication pilot, use the [current runbook](../authentication-pilot.md).

## Backend foundations at these milestones

Specification approval and implementation coverage are separate dimensions. An APPROVED specification records accepted architecture for its entity; it does not mean that every approved rule has been implemented.

The `main` branch currently contains these backend foundations:

- backend bootstrap;
- User and User Profile foundation;
- User repository port with an explicit JDBC adapter;
- Organization and Organization Membership foundation;
- Workspace and Workspace Membership structural foundation, including User-owned and Organization-owned Workspace creation with an atomic initial ACTIVE ADMIN Membership;
- Workspace repository port with an explicit JDBC adapter and V4 PostgreSQL structural-invariant enforcement;
- Ready-Made Product structural foundation as partial implementation coverage of APPROVED 1.1: a stable UUID identity, exactly one immutable Workspace, exactly one immutable Created By User, stored ACTIVE or ARCHIVED state, and a non-negative integer available quantity including zero;
- atomic initial ACTIVE Ready-Made Product creation with creation-time validation of an ACTIVE creator User and effective Workspace-layer READY_MADE_PRODUCTS write authorization;
- Ready-Made Product repository port, explicit JDBC adapter, Flyway V6, and an independent PostgreSQL creation gate and structural enforcement with tested concurrency and rollback boundaries;
- bounded Ready-Made Product lifecycle operations for ACTIVE → ARCHIVED and ARCHIVED → ACTIVE through separate public archive and activate application methods, without a generic caller-supplied target status;
- lifecycle transition authorization for an ACTIVE actor User with effective Workspace-layer READY_MADE_PRODUCTS write access, a final persisted-status decision after locking the Product row, expected-state update semantics, and Flyway V7 structural transition enforcement with same-state rejection and tested rollback and concurrency boundaries;
- durable manual quantity-delta commands for ACTIVE and ARCHIVED Ready-Made Products through separate registration and application/retry/replay service operations, explicit JDBC persistence, and Flyway V8 structural enforcement.

This is not a full product implementation and does not mean that all behavior in the approved specifications has been delivered.

The source checkout's bounded CFA-03 correction admits Organization creation
under a current User status lock before inserts, retained through the surrounding
READ COMMITTED transaction. It serializes other status writers, not the same
transaction's later authorized status change: admission is not ACTIVE-at-commit
or a permanent active-User OWNER invariant. Organization/initial Membership
remain atomic, all stronger Workspace/RMP guards still apply, and V1–V14 remain
unchanged. No authenticated Organization API, new ownership rule or rollout follows.

The merged Workspace foundation is partial implementation coverage of the independently APPROVED 1.0 Workspace and Workspace Membership specifications, not complete delivery of every approved rule or workflow. It is not exposed through an authenticated Workspace HTTP API; invitations or invitation acceptance, general Workspace Membership creation or mutation workflows beyond the atomic initial Membership, ownership transfer, Workspace deletion or archival workflows, and recovery remain unimplemented. The Workspace implementation does not approve any downstream DRAFT specification.

Foundation V9 / PROD-002 and V10 / PROD-001 are integrated corrections, separate from the Ready-Made Product IMPLEMENTATION 005A/V6, 005B/V7, and 005C/V8 slices. V9 permits writes to `organizations`, `organization_memberships`, `workspaces`, and `workspace_memberships` only at actual READ COMMITTED isolation; guards reject unsupported isolation with SQLSTATE `0A000`. This is a contract for those four foundation tables, not support for arbitrary operations at other isolation levels.

V9 validates existing foundation data from a fresh snapshot after the complete migration barrier; validation, DDL, and successful history insertion commit in one physical transaction. The verified Flyway configuration uses `postgresql.transactional-lock=false`, `execute-in-transaction=true`, and `group=false`; session advisory locking still serializes migrators and is released after success or rollback. These proofs cover the configured direct PostgreSQL execution path, not transaction-pooling proxies or an external deployment. With the current Hikari policy, `0A000` closes the connection; Spring ambient rollback can expose a different outer exception while retaining the original application exception. Continuation of that same pooled transaction through a savepoint is not guaranteed. Verified recovery uses a new READ COMMITTED service transaction after the failed transaction completes. See the [backend compatibility details](../../backend/README.md#foundation-write-isolation-contract-and-v9-upgrade).

V10 changes exactly three creation-lock sites in `workspaces_require_initial_foundation()` from `FOR UPDATE` to `FOR NO KEY UPDATE`, fixing the reproduced FK-lock upgrade deadlock during concurrent Workspace creation. Deferred validation, predicates, ordering, and the other foundation and Ready-Made Product protocols remain unchanged. The fix covers the tested scenarios, not global deadlock freedom or a universal retry policy. MIN-01 remains an accepted nonblocking coverage limitation: the defensive early-admission test branch did not execute, while required queued-writer scenarios were verified.

The integrated V10 baseline is confirmed by [PR #24](https://github.com/creastrix-svg/creastrix-platform/pull/24) and [post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/34280907486): 426 tests, 0 failures/errors/skipped, tests and package BUILD SUCCESS, PostgreSQL 18.4-alpine, and Flyway V1 → V10. PROD-001 and PROD-002 are fixed in main within the agreed boundaries; this does not close FULL AUDIT 001 or eliminate its other risks. User and external databases were not updated; repository integration is not rollout.

### Runtime dependency update

DEPENDENCY-REMEDIATION-001 / SC-01 is also integrated in main through [PR #26](https://github.com/creastrix-svg/creastrix-platform/pull/26). Four explicit POM overrides update nine runtime artifacts without changing Boot/Spring or the remaining resolved coordinates; see the [runtime override details](../../backend/README.md#runtime-dependency-overrides). Independent compatibility verification preceded integration, and [post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/34392178931) confirmed 426 tests, zero failures/errors/skipped, and tests/package BUILD SUCCESS. SC-01 is fixed only within that agreed scope, not a claim that all dependency risks are absent. External deployments and running applications were not updated.

### Ready-made product foundation

The merged Ready-Made Product foundation is partial implementation coverage of the independently APPROVED 1.1 specification, not complete delivery of every approved rule or workflow. IMPLEMENTATION 005A delivers the structural foundation and safe creation through the repository port, explicit JDBC adapter, Flyway V6, and a PostgreSQL creation-authorization gate that enforces creation authorization independently of the application-service path.

IMPLEMENTATION 005B adds only the bounded ACTIVE → ARCHIVED and ARCHIVED → ACTIVE lifecycle slice. Separate public archive and activate application methods require an ACTIVE actor User with effective Workspace-layer READY_MADE_PRODUCTS write authorization; callers cannot supply an arbitrary target status. The repository makes the final decision from persisted status after locking the Product row and uses an expected-state update, while Flyway V7 independently restricts structural transitions and rejects same-state changes. Integration tests cover authorization rollback and relevant transition concurrency.

IMPLEMENTATION 005C adds bounded durable manual quantity-delta commands for both ACTIVE and ARCHIVED Products. Separate registration and application/retry/replay entry points use REQUIRES_NEW: external Spring-proxy calls commit independently of any suspended caller transaction. Registration permanently binds the composite `(Product ID, Command ID)` identity to one immutable non-zero signed 64-bit delta in REGISTERED state, without changing quantity or triggering application. Current Product authorization precedes command lookup and disclosure on each operation. Checked arithmetic and the quantity change commit atomically with the APPLIED outcome, or an unchanged quantity and terminal UNDERFLOW/OVERFLOW outcome commit as REJECTED. Authorized terminal replay returns the stored historical outcome without recalculation or reapplication. The explicit JDBC adapter and Flyway V8 preserve the closed state/outcome structure, immutable binding and terminal outcomes, and permanent command retention. REQUIRES_NEW can require an additional connection when an outer transaction holds one; outer locks needed by the inner operation can prevent completion.

Allocation persistence is absent, so reachable outstanding allocated quantity is zero and accounted physical quantity currently equals available quantity. Integration of accounted quantity with Order Item allocation, eligible release, and Shipment dispatch remains unimplemented. IMPLEMENTATION 005C is partial coverage of APPROVED 1.1, not a complete stock or commerce workflow.

Ready-Made Product services are not exposed through an authenticated HTTP API; the integrated authentication pilot below does not provide caller identity proof for those operations. Raw SQL is structurally constrained but not actor-authorized, migration/table-owner privileges are not separated from the runtime database role, and global deadlock freedom or a general SQLSTATE 40P01 retry policy is not established. Generic editing, Order Item confirmation-time allocation, eligible pre-dispatch exact release, dispatch accounting and serialization with Shipment SHIPPED, Listing, Order, Order Item, Shipment, Payment or other commerce integration, list, search and paging, and a Ready-Made Product HTTP API remain unimplemented. No next Ready-Made Product implementation slice is selected or started here.

Ready-Made Product references to the DRAFT Listing, Order Item, and Shipment specifications preserve accepted Ready-Made Product boundaries only; they do not approve those specifications or authorize their production implementation.

The remaining DRAFT domains are active architecture work and require their own architecture review and independent specification approval before implementation.

### Platform Access Grant: bounded I1 + I2A coverage

Platform Access Grant APPROVED 1.1 is separately accepted architecture.
Main contains the unchanged I1 pure policy plus integrated I2A storage/generation
and constructor-only workflows for named intent registration, user-security
READ, other-SUPPORT-grant REVOKE and separately authorized receipts.
V12 retains the non-REVOKED slot (including expired/suspended grants and
inactive recipients), immutable partial-slice scope/expiry, eligibility
invalidation and access-workflow history. It is not raw-SQL actor authorization
or protection against the database owner.
See the [backend coverage and limits](../../backend/README.md#platform-access-policy-foundation).

Owned READ COMMITTED transactions reject ambient work, refresh current facts
and time after waits, and require durable audit/outcome before acknowledged
commit and disclosure. UNKNOWN is not rollback, success or permission to retry
under a new identity. Read admission is not network delivery or human reading.
The trusted-facts adapter is synthetic; no live authentication assurance,
Spring/HTTP staff wiring, issuance/invitations, CHANGE/SUSPEND/RESUME execution,
maintenance, UI or full Audit Log is delivered.

[PR #33](https://github.com/creastrix-svg/creastrix-platform/pull/33) integrated the
reviewed bounded coverage; its separate 2026-10-05
[post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37312461778)
passed 1513 tests and tests/package BUILD SUCCESS with PostgreSQL 18.4 / Flyway
V1 → V12. These historical checks do not activate live privileges.
No next frontend, commerce or maintenance slice is selected.
The [local cookie disposition and retained public gate](../../docs/authentication-pilot.md#open-cookie-follow-up) records scoped local closure. Separate S003 source, scoped browser and
runtime evidence are distinguished in the
[authentication boundary](../../docs/authentication-pilot.md#s003-evidence-and-open-gates).
They do not supply the missing live Platform Access wiring or close its gates.

The bounded CFA-01/02 correction in this source checkout separates acknowledged
owned-phase completion from cleanup/result release: known commit is not a
fabricated failure or UNKNOWN when its payload must be withheld. Scoped
authority/SQL/budget/audit diagnostics and V14's two confirmed-failure reasons
leave APPROVED 1.1 permissions, generic authentication classification and V1–V13
unchanged. Coverage stays constructor-only; verification and delivery evidence
are separate, with no live staff wiring, rollout or next business slice implied.

### Integrated authentication backend pilot

The owner authorized AUTH-FIRST-LOGIN-001 against reviewed AUTH-SELECTION-003:
single-instance backend/nonproduction database, fixed loopback backend and
browser-origin contract, exact issuer/subject admission with controlled restart,
server-side OIDC/HttpOnly session, 30-minute idle and 8-hour absolute limits.
The integrated AUTH-FIRST-LOGIN-001 adds the bounded V11 identity
binding and account HTTP path. Historical AUTH-001-R1 backend B passed
local author verification: 91 policy tests, 20 PostgreSQL authentication tests,
113 real HTTP/test-IdP cases, one complete 650-test suite with zero
failures/errors/skipped, and package BUILD SUCCESS. These and the earlier native
IDE policy 91/91 and HTTP 113/113 are not fresh AUTH-001-R2 results.
The owner-authorized 2026-09-14 R2 contract keeps per-session callback ownership
and a short local client-save, standard Spring session-ID/CSRF rotation,
SecurityContext and outcome-selection section coordinated with cooperative logout.
Publication is not physical HTTP commit or browser delivery; one second bounds
coordinator-lock acquisition, not all Servlet operations or HTTP I/O. Cancellation
before publication prevents client/principal save. Detected cancellation during
publication requires that the rejected, still-uncommitted failure 303 contain no
setting/deletion session cookie owned by that attempt, while unrelated cookies
and any newer session/flow/client/principal remain untouched. The callback stays
pinned to its original session, and durable identity-binding commits are not undone.
Historical R2 author verification on 2026-09-14 passed three consecutive 27-case focused runs and
one complete 658-test suite (policy 97, PostgreSQL authentication 20, HTTP/OIDC 115
within that suite), with zero failures/errors/skipped and package BUILD SUCCESS.
All 650 preceding testcase identities remain. Subsequent author-side native IDE
verification (policy 97/97, HTTP 115/115) and independent R2 re-review completed.
[PR #28](https://github.com/creastrix-svg/creastrix-platform/pull/28) integrated the
reviewed change; its separate
[post-merge CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/36115810224)
passed 658/658, failures/errors/skipped 0/0/0, tests/package BUILD SUCCESS,
PostgreSQL 18.4 and Flyway V1 → V11. See the exact merge and distinct verification
evidence in the [authentication boundary](../../docs/authentication-pilot.md#verification-gates).
No tests were rerun by this documentation change; none of these results closes
the separate cookie follow-up below. Subsequent finite local React/browser F is
recorded below; a separate bounded real Auth0 owner walkthrough P completed on 2026-10-11. No Workspace is created by
login; no Workspace/RMP API, account linking or new commerce decision is added.

### S003 cookie pairing

S003 is integrated through [PR #34](https://github.com/creastrix-svg/creastrix-platform/pull/34);
its separate [post-merge CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37917170767)
passed 1618/1618 and tests/package BUILD SUCCESS on 2026-10-09. These are historical
server results, not frontend/browser F evidence. The process-local Q registry binds a session to one immutable stamp.
Private access captures it before current-User lookup and checks that same stamp
at final admission, after lookup. G1 denies a revoked/superseded same-Q generation.
G2 protects fresh requests from the current cookie jar: late success-cookie
delivery must not silently restore the old authorized Q_A/SID_A pair. A mismatched
or obsolete pair receives 401 and needs explicit recovery; retaining B without
interruption is not guaranteed.

L1 is retained, not labelled a fixed defect: a previously sent request under a
different still-live Q_A may first gain final admission after B/Q_B and return A.
Already-admitted responses may finish after revocation too. Future frontend state
must reject stale account responses; future business writes and global user or
all-device revocation are not covered by this exception. One-time Q issuance,
echo, exact-owner cleanup, restart/expiry recovery and bounded retained records
are specified in the [cookie contract](../../docs/authentication-pilot.md#s003-cookie-pairing-contract).
OIDC, CSRF, the late-failure correction and committed identity bindings are preserved.

The isolated exact candidate's 2026-10-07 full run passed 1618/1618 in 32 suites,
failures/errors/skipped 0/0/0, and package BUILD SUCCESS. Root dispositions separately
accepted scoped browser/server evidence; original incomplete/failed reports remain
historical and were not rewritten. These are external dated results, not new
runtime, IDE or CI execution for this transfer. PA observer/HOLD and separate
combined foundation/HTTP/CI candidates are not composed into this change.

The [local cookie disposition and retained public gate](../../docs/authentication-pilot.md#open-cookie-follow-up) records accepted local-only closure; public/staff/business-write/deployment/multi-instance readiness remains unauthorized.
The 2026-09-14 temporary local-pilot acceptance did not automatically accept the
later B-to-A consequence or public operation. Finite local React/dev-proxy/browser F
subsequently completed; bounded Auth0 P later completed separately. Natural frequency, account takeover and the separate post-commit
network-delay scenario are not proved. Revisit the follow-up at browser verification
and before public access/invitations/rollout. No next implementation slice is selected.

### Frontend UI foundation

FRONTEND-BOOTSTRAP-001 established a UI scaffold in a separately built `frontend` directory in
the existing repository: React 19.3.0, TypeScript 6.0.3, Vite 8.3.1, Redux Toolkit
2.13.0 / React Redux 9.3.0 and React Router 7.18.4, using pinned Node 22.14.0 /
npm 10.9.2 and a lockfile. [PR #35](https://github.com/creastrix-svg/creastrix-platform/pull/35)
integrated the bounded hosted-login client, current own `id/status` rendering, local
logout/switch, explicit recovery and fixed development proxy. Private Redux state
is memory-only; a non-secret replaceable intent marker is separate from language
persistence. Owned result checks, peer invalidation and a synchronous DOM curtain
protect UI state without claiming server authorization or absolute cross-tab ordering.
The fictional profile is removed. DE/EN, keyboard/focus support, the unchanged
3C logo and voluntary Workspace messaging remain; palette/Atelier decisions stay open.

Independent review accepted the finite local F matrix from actual React/Vite,
the integrated backend, a synthetic IdP and isolated native Chrome evidence.
Source/unit/build and native IDE results remain separate from that browser proof.
The 2026-10-09
[post-merge Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37962373086)
passed 135 tests, typecheck, lint and build; it did not run the browser matrix.
The npm installation reported one HIGH warning; that historical result is retained.
See the [lockfile follow-up and installed-environment limits](../../frontend/README.md#dependency-follow-up)
for the accompanying `source-map-js` 1.2.2 update and isolated verification.
Bounded Auth0 P completed separately on 2026-10-11; the local Cognito owner transfer
completed separately on 2026-10-13, without public rollout. G1/G2 and L1
remain distinct; stale UI response rejection does not close the old cross-Q server
limitation or cover future business writes. Storage unavailability fails closed;
completion/lifecycle events never republish the shared intent marker. Early native
login refusal may show opaque backend JSON outside React. The PR #35 client included no dependency/backend,
Workspace/RMP API/forms, signup/social login or business decision changes.
The connected origin is fixed `http://localhost:3000`, with loopback strict port;
build preview is disconnected. See the [frontend guide](../../frontend/README.md).
See the [local cookie disposition and retained public gate](../../docs/authentication-pilot.md#open-cookie-follow-up) for accepted local-only closure and OPEN public readiness obligations.

<a id="controlled-cognito-owner-migration-accepted-decision-author-wip"></a>

### Integrated controlled Cognito owner migration and recovery

The owner accepted ordinary external Cognito self-registration in Frankfurt with
closed exact-pair Creastrix admission, not public Creastrix signup or access.
The PR #37 slice introduced one selected AUTH0/COGNITO registration; Cognito
requires a verified signed ID token with raw Boolean email verification, numeric
auth_time and token_use=id and does not call UserInfo. At that integration, Auth0
retained its checks and remained the source default.
V13/current selector and immutable receipts permit an explicitly authorized
offline switch while retaining all historical pairs and the same internal
User/Profile/status/eligibility generation and rights. No email auto-linking or
simultaneously active aliases are accepted. Owner existing-only resolution denies
unknown and superseded pairs before account creation.

The reviewed migration/recovery source is integrated through [PR #37](https://github.com/creastrix-svg/creastrix-platform/pull/37)
at accepted main `e5b58d43a9dc07c76efc905bc63d8269feaa40fe`. This is the bounded owner-only
local/nonproduction slice, not complete authentication or public rollout. Separately,
the authorized 2026-10-13 local operator action verified backup/restore, applied
V13 and acknowledged one owner switch with independent read-back. The same
User UUID/Profile/status/generation/rights/business rows, both historical pairs
and one receipt were retained. The local runtime selects Cognito existing-only
with one exact owner subject through private environment. That operator action
did not change source defaults. Owner login, local logout/private 401, repeat login and login after
the owner-reported password change/private 200 returned the same ACTIVE User.
That action retained Auth0 tenant/client/account/private configuration, not a current alias.

The historical Auth0 P, synthetic author tests, independent backend 1690/package
and frontend 140 checks, native compilation/417 tests/scoped views and the local
operator results have distinct scopes. Overall IDE schema/background completion
remains UNKNOWN/INCOMPLETE. Real unverified/admitted backend refusal is NOT RUN;
hosted UNCONFIRMED refusal and synthetic raw-Boolean rejection remain different
proofs. On 2026-10-13 the owner explicitly accepted their combination with verified
owner login for limited owner-only nonproduction source readiness, not reproduction
of the missing real signed-false case, public access or approval of new recovery
code. The exact disposition is retained in the runbook. Later independent review
and PR #37 publication/integration are distinct completed gates. The previous status-only alignment did not rerun backend
tests or perform another provider/database action.
The [runbook](../../docs/authentication-provider-migration.md) retains authorization,
identity proof, backup and stopped-app prerequisites for future switches.
Own SES à la carte remains a separate selected setup, not an already connected
sender or SES Essentials. Social/TOTP, public access/rollout and
OPEN public-readiness obligations remain outside that migration action.

The integrated recovery slice adds explicit transient `/confirm-email` confirmation
and resend for anonymous selected-Cognito requests, with exact Host/Origin and
fresh standard session CSRF but no live-Q/echo prerequisite. Bounded backend-only
ConfirmSignUp/ResendConfirmationCode preserve the entered email case and never
link identities, create internal accounts/Workspace/grants or log in automatically.
Provider email confirmation still needs a fresh ordinary OIDC flow and exact
Creastrix admission. Author tests, native IDE and actual provider/browser recovery
have distinct evidence; no rollout or public self-admission is implied. That
recovery slice retained Auth0 and its source default; cookie/native/publication gates are not closed
by the limited EMAIL disposition.

Fresh isolated recovery author verification passed focused 528/528, the narrow
diagnostic-catalog check 163/163, full 1826/1826 in 33 suites and package;
PostgreSQL 18.4 / Flyway V1 → V13 were observed. Frontend 234/234, typecheck,
lint and build passed. All prior backend 1690/frontend 140 testcase identities
remain; failed attempts are retained separately. This is author source/mock/
owned-Testcontainers evidence, not native/browser or CI proof. Original target/live
resources were preserved. Separate scoped native, focused independent source/JAR
review and real owner recovery/fresh OIDC results on 2026-10-14 are recorded in the
[dated recovery evidence](../../docs/authentication-provider-migration.md#2026-10-14-scoped-recovery-evidence).
Overall IDE background/schema remains UNKNOWN/INCOMPLETE. PR #37 preserved the
reviewed tree; separate post-merge [Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930693)
passed 1826/1826 with zero failures/errors/skipped and tests/package BUILD SUCCESS;
[Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930663)
passed 234/234, locked install, typecheck, lint and build. These are integration CI,
not new browser/native proofs. Local operator results remain distinct; rollout
and broader cookie safety are not authorized by source integration.

<a id="auth0-source-retirement-candidate"></a>

### Integrated Auth0 source retirement

On 2026-10-14 the owner authorized removal of active Auth0 support while keeping
Cognito and the same internal account. [PR #39](https://github.com/creastrix-svg/creastrix-platform/pull/39)
integrated the reviewed source as `6d9653c338702cb0530ec31b8d0c04a1ef796ed0`.
The backend uses only the Cognito
Frankfurt adapter, defaults to COGNITO/existing-only, and rejects legacy AUTH0
selection and entry/callback routes. It preserves strict signed ID-token claims,
common OIDC/session/Q checks, User UUID/Profile/rights, the current identity
selector, immutable historical bindings and receipts. No new owner transfer,
schema change or domain decision is needed.

Future providers require a separately reviewed adapter plus an explicitly
authorized, verified exact-identity operation through the preserved V13 boundary.
Internal account mapping is exact issuer/subject; email auto-linking, bulk
migration and password export are not provided. The planned unified email/password,
Google and Apple experience needs separately implemented/reviewed secure linking,
not additional AWS prerequisites for using Cognito.
Fresh post-merge [Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38562911789)
passed 1832/1832, zero failures/errors/skipped, tests/package BUILD SUCCESS,
PostgreSQL 18.4 / Flyway V1 → V13; [Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38562912006)
passed 234/234, typecheck, lint and build. The live backend and its 359 target files
were not rebuilt/restarted at that milestone; the observed runtime still used the older Cognito-configured binaries.
Source integration and CI are not a new-source real-browser result.
Separately, exact-target owner confirmation preceded deletion of the external
Auth0 application Creastrix Local Pilot on 2026-10-14. Tenant, Default App, users
and private configuration remain; a fresh account recheck on the previous live
runtime still showed the same ACTIVE User. See the [operator evidence](../../docs/authentication-provider-migration.md#2026-10-14-auth0-retirement-boundary).
Historical Auth0 P and migration results remain historical. Local cookie closure,
L1 and the OPEN public-readiness gate are unchanged.

### Buyer storefront integration

[PR #43](https://github.com/creastrix-svg/creastrix-platform/pull/43) integrated the product page; [PR #44](https://github.com/creastrix-svg/creastrix-platform/pull/44) integrated the local cart and checkout preview. [PR #45](https://github.com/creastrix-svg/creastrix-platform/pull/45) integrated the existing studio, buyer homepage and shared product-card layout. These milestones cover frontend functionality, not a commerce API or deployment.
