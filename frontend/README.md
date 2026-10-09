# Creastrix frontend

FRONTEND-BOOTSTRAP-001 established this separately built React/TypeScript/Vite
application in the existing repository. The current `solar_wind/react-authentication-f`
change proposes a bounded authentication client on that foundation. It is WIP,
not integrated in main or independently approved; real browser result F and
Auth0 walkthrough P are still pending.

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
disposal removes owned listeners. Paused tabs, pixels already delivered and actual
bfcache behavior still require the browser gate.

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

Fresh author-side typecheck, lint, Vitest and build evidence belongs to the external
author report, not backend CI, native IDE or browser verification. Vitest uses
Testing Library/happy-dom, injected finite responses and deferred bodies; it is
not an actual HTTP/OIDC/cookie/bfcache proof. Independent source review, IDE and
real React + Vite + exact integrated backend + isolated browser F are separate
remaining gates. Real Auth0 P has not been performed.

`AUTH-COOKIE-FOLLOWUP-001` remains **OPEN**. S003 is integrated through PR #34;
the historical server/scoped browser/native/CI results do not close full F/P or
authorize public access. No dependency, backend, workflow or domain decision is
changed by this client candidate.

Use the existing WebStorm/project settings; do not commit IDE state. Ignored
dependencies/build output may remain locally. [Frontend CI](../.github/workflows/frontend-ci.yml)
has separate frontend/workflow path filters; no CI or backend execution occurred
in this authoring stage. See the [root context](../README.md#frontend-ui-foundation)
and [authentication contract](../docs/authentication-pilot.md#react-client-candidate-and-f-boundary).
