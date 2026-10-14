# Creastrix frontend

FRONTEND-BOOTSTRAP-001 established this separately built React/TypeScript/Vite
application in the existing repository. [PR #35](https://github.com/creastrix-svg/creastrix-platform/pull/35)
integrated the independently reviewed bounded authentication client and finite
local React/dev-proxy/browser F. A separate bounded real Auth0 owner walkthrough P
completed on 2026-10-11; this historical evidence is not a Cognito transfer.
Public access and rollout remain unverified and unauthorized.

## Local use

Use pinned Node **22.14.0** / npm **10.9.2**, without upgrading the locked packages.
From the repository root:

```sh
cd frontend
npm ci
npm run dev
```

Connected authentication requires exactly `http://localhost:3000` and the separately
configured backend on `127.0.0.1:8080`. Vite binds loopback `127.0.0.1:3000` with
strict port selection. Resolve conflicts explicitly; do not stop unrelated
processes, bind publicly or select another origin automatically.

```sh
npm run typecheck
npm run lint
npm test
npm run build
npm run preview
```

Stop your own dev server before preview. Both support direct reloads of `/`,
`/login`, `/account` and not-found routes. Build preview serves `dist`, has **no
backend proxy**, and disables connected authentication with a visible explanation.
It is not a deployment service or an authenticated account demo.

## Bounded client behavior

- The selected **3C / SYMMETRY** logo, neutral layout, DE/EN copy, keyboard navigation
  and focus remain. Colour palette and Atelier/Studio decisions remain open.
- Redux holds language and **memory-only** authentication state. Own-account display
  requires a current validated `/api/me` response containing only `id/status`.
  No fictional Alex, name/email/role claims or inferred Workspace inventory is shown.
- The states are CHECKING, ANONYMOUS, AUTHENTICATED, TRANSITION, RECOVERY_REQUIRED,
  DENIED and UNAVAILABLE. Every state except AUTHENTICATED closes private content.
  A URL, callback redirect or `auth=failed` parameter is not proof of identity.
- Explicit hosted login performs two sequential bounded `GET /auth/csrf` requests
  (bootstrap/echo), then a native same-window form POST to `/auth/login`.
  CSRF is transient, not Redux/localStorage state. No fetch-follow to the provider,
  password fields, OAuth SDK, browser token or provider-claim persistence is added.
- Logout obtains current CSRF and posts once. Only actual 204 reports presented-Q
  revocation; 409 is caller-SID cleanup only. Security refusal, 503, malformed
  response, timeout or transport loss is not logout success or proof of rollback.
  Recovery/switch clears private state, performs cleanup, and only after 204 or the
  defined cleanup-only 409 performs bootstrap/echo. A new full sign-in remains an
  explicit next action; actions are not automatically retried.
- Google, Apple and separate signup remain unavailable. No Workspace/RMP API/forms,
  staff/admin access, linking, commerce or rollout is included. Workspace is
  voluntary; account/login does not create one or grant business authority.

## Ownership and lifecycle

One per-document controller is created outside StrictMode effects. Every operation
captures document, epoch, request and shared revision ownership. Checks surround
fetch/body awaits, error handling and further steps; the reducer also rejects
stale owners. Old A success, denial, parse error, CSRF or cleanup completion cannot
replace or clear a newer owned B. Abort controls resources; it does not undo
server effects or prevent browser cookie delivery. Reads have a 10-second deadline;
each client preparation/cleanup action has a 30-second deadline, not 30 seconds per
request. Native submission ends that client deadline; it does not bound OIDC flow,
browser navigation or delivery. A stalled navigation requires explicit return/reload.

The only new persisted value is `creastrix.auth.boundary`: a replaceable opaque
nonce plus intent phase, never identity, credential, stable browser ID or permission.
It is written only at document startup or explicit auth intent. Completion, peer
events, recheck, focus and lifecycle events **do not write it**. Phase describes
intent, not completion; there is no completion CAS, nonce restoration or outcome
broadcast. Events wake a synchronous current-storage re-read, not adoption of event
payload. Invalid/unavailable storage disables connected authentication instead of
falling back to an unsupported multi-tab guarantee. Language persistence remains
separate and usable when storage is blocked.

Peer invalidation and hide/pagehide close a direct DOM curtain and clear private
state/pending material synchronously. Visible/focus/pageshow re-entry requires a
fresh owned check before reopening after the current React DOM commit. Hot-reload
disposal removes owned listeners. Actual bfcache behavior has finite local browser
coverage; instant clearing of paused tabs or already-delivered pixels is not guaranteed.

This is not a cross-tab lock or absolute latest-login-wins. Server G1/G2 remain:
G2 may deny with 401 and require explicit recovery instead of preserving B
seamlessly. L1 (an earlier request under another still-live Q can first be admitted
after B and return A) is not closed by discarding stale UI responses. Future
business writes and global/provider/all-device logout are not covered.

## Fixed development transport and limits

The development proxy routes segment namespaces `/auth`, `/api`, `/oauth2` and
`/login/oauth2`, including percent-encoded letters, before SPA fallback. `/login`
and `/account` remain React routes. Unknown backend namespace routes must reach
backend denial, not HTML. The fixed target is `http://127.0.0.1:8080`; configuration
does not rewrite URI/query, Host, Origin, Location, cookie Domain/Path or Set-Cookie,
does not follow redirects, and does not add forwarded-header trust or CORS.
Unit regex/options checks do **not** prove actual Vite proxy or browser cookie behavior.

Early top-level login 403/409/503 may display the backend's opaque JSON page outside
React. This bounded UX debt is not hidden by rewriting a response or adding a
backend endpoint. The user explicitly returns/reloads; there is no blind retry.

## Verification and remaining gates

Historical author-side typecheck, lint, Vitest and build evidence belongs to the external
author report, not backend CI, native IDE or browser verification. Vitest uses
Testing Library/happy-dom, injected finite responses and deferred bodies; it is
not an actual HTTP/OIDC/cookie/bfcache proof. Separate source review and native
WebStorm verification completed before integration. Consolidated independent
review accepted the finite local F matrix from actual React/Vite/backend,
synthetic OIDC and isolated native Chrome runs, including both late-success A
branches, explicit recovery, late-failure preservation and stale UI responses.
Earlier INCOMPLETE attempts and helper failures retain their original outcomes.
Bounded Auth0 P subsequently completed separately on 2026-10-11.

The 2026-10-09
[post-merge Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37962373086)
passed 135 tests, typecheck, lint and build. It is a separate unit/static/build
gate, not a browser run. Both PR and post-merge npm installations reported one
HIGH vulnerability warning; those historical results are retained.

<a id="dependency-follow-up"></a>

The accompanying single-record lockfile update pins `source-map-js` 1.2.2 for
[GHSA-68fv-2mgg-jv7q / CVE-2026-93749](https://github.com/advisories/GHSA-68fv-2mgg-jv7q).
Isolated author verification passed 234 tests, typecheck, lint, build and both
development/omit-dev lockfile audits with zero findings; evidence is retained in
`frontend-high-triage-20261014-YiOqAj/ISOLATED-REPORT.md`.
Installed environments need reinstallation/restart to use the patched version.
The lockfile update is integrated through [PR #38](https://github.com/creastrix-svg/creastrix-platform/pull/38);
installed-environment updates and deployment remain separate.
Audit zero covers known advisories for that lock snapshot, not full security.

The [local cookie disposition and retained public gate](../docs/authentication-pilot.md#open-cookie-follow-up) records scoped local closure. S003 is integrated through PR #34;
finite local F and bounded Auth0 P are complete. Cognito owner transfer completed
separately on 2026-10-13. Real unverified/admitted backend refusal remains NOT RUN;
the owner accepted a narrower EMAIL evidence combination for owner-only local
source readiness, not that missing real scenario or public access. The original follow-up
is closed only for the accepted local pilot scope; `AUTH-PUBLIC-READINESS-001` remains OPEN for public-access/rollout. L1 and future business-write limits
are unchanged. The PR #35 client changed no dependencies, backend, workflows or domain decisions.

Use the existing WebStorm/project settings; do not commit IDE state. Ignored
dependencies/build output may remain locally. [Frontend CI](../.github/workflows/frontend-ci.yml)
has separate frontend/workflow path filters; no tests or CI were run by this
documentation change. See the [root context](../README.md#frontend-ui-foundation)
and [authentication contract](../docs/authentication-pilot.md#react-client-and-f-boundary).

<a id="selected-provider-boundary-author-wip"></a>

## Integrated selected provider boundary

The frontend keeps its native POST to `/auth/login`; it does not choose the
provider, store provider tokens or collect a password. Backend configuration
selects exactly one registration. [PR #39](https://github.com/creastrix-svg/creastrix-platform/pull/39)
integrated Cognito-only source support on 2026-10-14; legacy AUTH0 configuration
fails closed and the Auth0 entry/callback paths are unsupported. The existing proxy namespaces route
`/oauth2/authorization/cognito` and `/login/oauth2/code/cognito`, including accepted
encoded segments, without rewriting headers, URI/query, Location or cookies.
Config-unit checks are not a real Cognito/browser proxy proof. The live backend
still runs older Cognito-configured binaries; integration did not rebuild/restart it.

Hosted Cognito self-registration and provider email/recovery are separate from
the owner's exact closed Creastrix admission. No separate React signup form,
social provider, Workspace creation or rollout is delivered by this client.
The [controlled migration runbook](../docs/authentication-provider-migration.md)
records the separately authorized 2026-10-13 local owner transfer: same internal
ACTIVE User, local logout/private refusal, repeat login and new-password login.
The local backend selects Cognito existing-only for that one owner through private
environment. Integrated retirement makes COGNITO/existing-only the source
defaults without changing that internal account or the frontend's provider-neutral
login form. The 2026-10-13 transfer retained Auth0 externally, not as a current alias;
the separately confirmed deletion of its old client is recorded in the
[operator evidence](../docs/authentication-provider-migration.md#2026-10-14-auth0-retirement-boundary).
The DE/EN pilot notice
reflects the bounded local Cognito runtime only.

Matching email never merges Creastrix accounts; the owner continuity above came
from an authorized exact-identity transfer. The planned unified email/password,
Google and Apple experience needs separate secure linking implementation/review,
not a new AWS prerequisite. This client has no such linking or social-login feature.

PR #39's separate post-merge [Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38562912006)
passed 234/234, typecheck, lint and build. This is unit/static/build evidence,
not a new browser or real-provider walkthrough on the newly integrated backend.

The earlier migration/recovery source was integrated by [PR #37](https://github.com/creastrix-svg/creastrix-platform/pull/37) as
`e5b58d43a9dc07c76efc905bc63d8269feaa40fe`. Historical independent frontend 140,
backend 1690/package and native 417/scoped views remain separate; overall IDE
schema/background completion is UNKNOWN/INCOMPLETE. Real unverified/admitted
backend refusal is NOT RUN, and hosted-provider refusal before code/token is not
that proof. This alignment does not rerun browser/provider tests, rebuild the
live backend or extend the accepted local cookie scope. PR #37
publication/integration is complete; public access and rollout remain unauthorized.

<a id="email-confirmation-recovery-author-wip"></a>

## Integrated email-confirmation recovery

`/confirm-email` is reachable from `/login` and directly after closing the hosted
confirmation page. It has only transient email/code fields and explicit Confirm,
Resend, Cancel and Back to login actions; it does not collect a password or create
a provider/internal account. Exact input case is retained. Form/request data is
not written to URL/query/history, Redux, storage or logs and is cleared on
unmount/suspension. Edits, cancellation, navigation and unsafe account/lifecycle changes
invalidate older results. Disconnected preview/known authenticated or transitioning
state cannot submit; no live-Q/echo/canStart prerequisite is added.

Each explicit action fetches fresh standard `/auth/csrf` once, then performs one
same-origin POST under a 30-second whole-action deadline. Success/CSRF bodies are
streamed with an 8192-byte cap before decoding; incomplete, invalid UTF-8 and
malformed responses fail closed. Strict success schemas
distinguish confirmed 200 from conditional resend 202; HTML/arbitrary 200 and
technical/transport uncertainty are never confirmation/delivery proof. No automatic
retry, logout, Q reset or login occurs. The anonymous selected-Cognito backend
boundary is authoritative; ordinary OIDC and exact admission are still needed for
Creastrix access. Existing login/logout/private-state ownership is unchanged.
Mock-fetch/DOM tests do not prove actual Cognito email delivery or browser recovery.
Retiring the Auth0 source adapter does not change these recovery claims.
The limited owner EMAIL disposition is in the
[runbook](../docs/authentication-provider-migration.md#limited-email-gate-disposition);
it did not itself approve recovery code or close native/cookie/publication gates.
The later independent review and PR #37 integration are separate evidence.

The bounded CFA06 correction retains a visible anonymous form's fields and settled
result only through passive, same-document/same-shared-revision
ANONYMOUS → CHECKING → ANONYMOUS revalidation. Focus still starts a fresh owned
`/api/me` and closes the private curtain; confirmation actions are disabled during
CHECKING. Every authentication transition is observed synchronously, so an unsafe
intermediate state cannot disappear inside a React batch. Peer revision/document
changes, hidden/pagehide/pageshow, departure/unmount, explicit clearing,
TRANSITION/AUTHENTICATED, DENIED, UNAVAILABLE and RECOVERY_REQUIRED erase the old
form/result permanently. The latter three retain the existing eligibility for a
new empty provider-only form; no live-Q prerequisite or broader admission is added.

Passive revalidation cancels old confirmation work before it can advance. CSRF
fetch/body cancellation is NOT_SENT: the provider action was not submitted, not
a claim that no HTTP request occurred. Once POST invocation begins, including a
throwing transport or delayed headers/body, cancellation is UNKNOWN because the
provider action may have happened. Already-recorded terminal results are not
downgraded before cleanup. Retention never restores old network ownership or
continues/retries an action automatically. The small production lifecycle binder
is shared with enabled-controller/store DOM regressions; these finite mocks are
not new native/browser, Cognito/email, cookie-race or rollout evidence.

Fresh isolated author verification of this recovery frontend passed 234/234 in
seven Vitest files, typecheck, lint and build; all 140 preceding testcase
identities remain. The first lint attempt failed on render-time ref use and was
retained in external evidence; lifecycle-owned initialization was then verified.
These remain mocked fetch/DOM/static/build results, not browser or CI proof.
Separate scoped native, independent source/JAR review and owner recovery/fresh
OIDC results on 2026-10-14 are recorded in the
[dated recovery evidence](../docs/authentication-provider-migration.md#2026-10-14-scoped-recovery-evidence).
Overall IDE background/schema remains UNKNOWN/INCOMPLETE. The separate PR #37
post-merge [Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930663)
passed 234/234, locked install, typecheck, lint and build. It did not rerun browser
F or settle global IDE health. Public-access readiness remains OPEN; local cookie closure is separately scoped.
