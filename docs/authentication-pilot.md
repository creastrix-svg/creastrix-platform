# Backend authentication pilot contract

AUTH-FIRST-LOGIN-001 implements an owner-authorized, bounded technical proposal
on `solar_wind/auth-first-login-backend`. It is WIP, not an APPROVED domain
specification or an integrated release. Earlier backend result B passed local
author verification; the current AUTH-001-R2 remediation has its own verification gate.
React/browser result F and actual Auth0 walkthrough P
are not done; backend tests do not prove working user-facing login.

## Identity and persistence boundary

The confidential backend uses the standard authorization-code OIDC flow with
S256 PKCE, `openid profile email`, `prompt=login` and `max_age=0`. It preserves
Spring signature, issuer, audience/azp, timestamps, state, nonce and UserInfo
subject validation. Raw `email_verified` must be JSON boolean; raw `auth_time`
must be a finite, nonnegative integral numeric date, before claim conversion.
Both returned claim sources must provide a nonblank email and verified=true;
the ID-token authentication time must fall between the saved request start
minus 60 seconds and now plus 60 seconds. The complete flow expires at 5 minutes.
Protocol, freshness and identity-allowlist checks precede binding access or creation.
Callback ownership is admitted before that work and rechecked before session publication.

Only exact `(issuer, subject)` identifies an internal [User](domain/user.md).
There is no email merge, browser-selected UUID authority, reactivation, linking,
or automatic Workspace creation. Inactive Users retain their identity and fail
login. The mandatory [User Profile](domain/user-profile.md) remains unchanged.

V11 adds one technical binding table, immediate exact-pair uniqueness, separate
User uniqueness, a non-cascading User foreign key and immutable retained rows.
It is not a new core domain entity or a manual-delta command state machine.
Legacy Users are not backfilled. Privileged raw SQL is not actor-authorized.

Resolution rejects an ambient transaction. An owned bounded worker leases a
connection and starts one physical READ COMMITTED transaction; the existing
proxied UserService joins it to create User/Profile/binding together. There is
no REQUIRES_NEW. Only the exact PostgreSQL pair-constraint conflict permits one
new read transaction after completed rollback; other constraints and failures
are not duplicate success. Acquisition belongs to the overall 15-second budget;
lock and statement budgets are at most 5 and 10 seconds. Per-lease deadlines do
not change shared Hikari policy. Real PostgreSQL tests measured acquisition,
lock, statement and overall deadlines and verified rollback and pool recovery;
the configured numbers alone are not the proof.

A failed response can follow a real commit. No failure claims that the account
was rolled back or absent. Recovery requires a new complete login with the same
external identity, not replaying an authorization code or selecting a different
subject. An absent SELECT cannot prove the outcome of an in-flight transaction.

## Fixed local transport and routes

One backend listens at `127.0.0.1:8080`. The future browser origin is exactly
`http://localhost:3000`; its separately implemented dev proxy must preserve the
browser Host/Origin, route backend namespaces before SPA fallback and pass
Set-Cookie/status/Location unchanged. Backend neither creates that proxy nor
derives trusted destinations from Host/Forwarded headers. No CORS is enabled.

| Route | Response / boundary |
| --- | --- |
| `GET /auth/csrf` | JSON token, `_csrf` parameter name, `X-CSRF-TOKEN` header name; anonymous bootstrap is not login |
| `POST /auth/login` | Exact Origin + form `_csrf`, explicit intent; authenticated caller gets 409; otherwise 303 to fixed OAuth entry |
| `GET /oauth2/authorization/auth0` | One live session intent required before the standard OIDC redirect |
| `GET /login/oauth2/code/auth0` | Protocol-gated callback; no local Origin required on this GET; busy/already-authenticated conflict uses fixed failure 303 |
| `GET /api/me` | Current session/admission/ACTIVE; only own `id/status`, no identity-provider claims or tokens |
| `POST /auth/logout` | Exact Origin + CSRF; local session/client-state removal and cookie expiration; 204 on success |
| `GET /actuator/health` | Minimal health; other management and unknown routes denied |

Security boundaries and OAuth/MVC routing use Spring's segment-aware
`PathPatternRequestMatcher` semantics, including allowed percent-encoded letters
inside a segment. Private API, Host/Origin, intent and callback-method gates do
not depend on raw-URI prefix comparisons. The URI and query are not rewritten;
OIDC `code` and `state` remain intact. Existing firewall, CSRF, OIDC validation
and default deny stay enabled. This is the current root-context/default-servlet
contract, not proof of arbitrary custom servlet, parser or proxy configurations.

The request boundary runs after SecurityContext loading, before CSRF, logout
and OAuth processing. OAuth entry still returns opaque 409 when already
authenticated. An otherwise valid callback rejected because the session is
already authenticated or another callback owns admission instead receives the
fixed failure 303, before token exchange or binding access. This also applies
to a second flow saved before the first login completed and rotated the session
ID. The original session/principal/client is retained; changing identity requires
logout and a new full login. Host/Origin, method and firewall failures retain
their early non-navigation boundaries and do not discard a valid saved flow.
Private-API current-user validation runs before final authorization:
confirmed inactivity, lost admission and absolute expiry still invalidate the
session, including equivalent encoded paths. An unavailable DB still fails closed
without claiming invalidation.

### Callback ownership and cancellation

In this single-instance contract the first callback to acquire admission owns
the logical session attempt; network arrival order does not select the winner.
Admission and capture/consumption of its genuine Spring authorization request
are one short atomic operation. Framework processing receives that saved request
once; a newer entry cannot overwrite it, and old cleanup cannot remove a newer
flow. Conflict cleanup is conditional on the exact flow, never whole-session
invalidation. Coordination belongs to the session object, not the rotating ID.

No mutex is held during provider or database work, and independent sessions do
not share a lock. After that work, ownership is rechecked before Spring's first
authorized-client save, not merely in the configured success handler. A short
local publication section covers client save, the unchanged Spring session-ID/CSRF
rotation, SecurityContext save and callback outcome selection, coordinated with
cooperative logout. Under the owner's 2026-09-14 local-pilot decision, publication
does not mean physical HTTP commit or delivery to the browser. One second bounds
coordinator-lock acquisition for admission/flow, publication and logout, not the
full duration of Servlet operations or HTTP I/O. Inability to acquire the local
section fails closed rather than bypassing ownership or claiming logout succeeded.
`finally` releases only its own attempt. No internal container monitor is held
through HTTP commit/flush, and the standard session strategy is not replaced.

Logout revokes the old attempt before ordinary session invalidation and can
complete while that callback is waiting on OIDC or the database. Session unbinding
also revokes ownership. The callback is pinned to its original attempt and session
and cannot create a replacement. Cancellation before publication prevents the
client/principal save from starting. When cancellation is detected during
publication and the callback is rejected, its still-uncommitted fixed failure 303
must contain no setting or deletion session cookie belonging to that attempt.
Cleanup addresses the real pending container headers, including a cookie added
by session-ID rotation, and identifies ownership plus the exact cookie name,
Path and Domain. It preserves unrelated cookies, including the same name at a
different scope, security headers and any newer session/flow/client/principal.
It sends no compensating deletion cookie, resets no whole response, invalidates
no session and forces no flush. Already-committed headers cannot be corrected;
transport failure must not be disguised as successful cleanup.

Cancellation is not a rollback of already running identity resolution. A
User/Profile/binding physically committed before logout remains durable, and
an already admitted operation may finish its commit after logout. This bounded
cleanup removes only the rejected attempt's unfinished session result; it is not
a rollback of durable identity state. Recovery uses a new complete login with the
same external identity; no compensating account deletion, blind authorization-code
replay or change to commit-unknown recovery is introduced.

### Open cookie follow-up

`AUTH-COOKIE-FOLLOWUP-001`: **OPEN — TEMPORARILY ACCEPTED FOR LOCAL NONPUBLIC PILOT ONLY**.
The owner accepted this limited boundary on 2026-09-14, not universal atomicity
between arbitrary session invalidation and physical HTTP commit. After the last
successful check or local success selection, logout/invalidation can still occur
before HTTP commit, including cooperative logout. A late success session cookie
can then disturb a newer login. This residual risk is not fixed: already-committed
responses are not recalled, and network delivery order is not guaranteed. Real
browser behavior and frequency have not been measured; account takeover has not
been demonstrated. Successful responses are not rewritten to imply a stronger
transport guarantee.

Return to this open item at both required gates:

1. React/dev-proxy/browser authentication verification must assess real overlapping
   responses, cookie behavior and recovery. Hiding an error in Redux is not a
   server-side fix or evidence that the protection is sufficient.
2. Before any public access, external-user invitations or rollout, require a
   separate owner/security decision with appropriate evidence and independent
   assessment. Local-only acceptance does not extend to that operating mode.

A merge, test count or the narrow late-failure fix does not close this follow-up.
No future mechanism is selected or implemented here. R1-REV-001 still requires
the actual narrow fix, executable verification and subsequent independent review;
changing this contract alone is not remediation proof.

### Callback navigation and opaque errors

Own navigation redirects are literal absolute 303 Locations: login initiation
to `http://localhost:3000/oauth2/authorization/auth0`, callback success to
`http://localhost:3000/account`, failure to
`http://localhost:3000/login?auth=failed`. No user redirect parameter is accepted;
session IDs are never URL-encoded. `/login` and `/account` HTML belong to future
React, not backend. Login is a top-level form POST, not fetch-follow to Auth0.

API errors are opaque JSON: 401 for absent/expired session, 403 for confirmed
inactive/denied access or invalid security input, 503 for unavailable current
state. Callback failure reveals no account/binding/SQL/token detail and makes
no rollback claim. Auth/API responses are not cached. DB failure denies access
but is not proof that logout or session invalidation succeeded.

## Session, configuration and deferred work

Tokens and authorized-client state exist only on the backend in the owning
session. Successful login rotates session ID and CSRF. Sessions have 30-minute
servlet idle expiry and 8-hour absolute expiry; confirmed inactivity or lost
admission invalidates session/client state. Returning a User to ACTIVE does not
revive an old session. CSRF/bootstrap and logout cleanup remain available.

Cookie defaults: host-only, HttpOnly, SameSite=Lax, Path `/`, Secure=true,
cookie-only tracking. Only explicit `auth-local` permits Secure=false on the
fixed loopback HTTP setup. This is not a public deployment configuration.
The profile requires exact standard EU Auth0 HTTPS issuer and external client
credentials. Admission subjects are immutable for the process lifetime; empty
admission denies all. Changes use controlled restart. Auth-disabled/non-web
contexts require no provider credentials or discovery and offer no generated
password, form login or basic-auth alternative.

Local logout does not log out Auth0 globally. Password reset/provider block does
not guarantee immediate revocation of an existing local session. Already sent
responses cannot be recalled. The future React must handle stale responses,
clear cached identity on errors/logout and verify a fresh `/api/me`; a public
page shell or Redux state is never authorization.

## Verification gates

B requires actual signed test-IdP HTTP code/token/JWKS/UserInfo flows and new
owned PostgreSQL Testcontainers: fresh V1–V11, populated V10–V11, physical
atomicity/rollback, concurrent exact-pair resolution, real commit-ack loss and
measured deadlines. Focused tests, the complete suite and package must pass;
426 tests describe the historical main baseline, not a fresh result here.
Pre-remediation author verification on 2026-09-11 passed PostgreSQL authentication 20/20,
HTTP/OIDC 76/76 and the complete 611/611 suite (including policy 89/89), with
zero failures/errors/skipped. Tests and package completed with BUILD SUCCESS.
PostgreSQL 18.4 and Flyway V1 → V11 were executed; populated V10 → V11 and the
historical pinned V8/V9/V10 regression proofs also passed. Real full OIDC flows
cover commit acknowledgement loss and a still-running prior transaction that
later commits or rolls back. Cookie deletion is asserted by expiry semantics,
server-session removal and rejection of the old cookie. Log checks use actual
issued test tokens, CSRF and session IDs without printing their values.
Idle tests assert the production 1800-second setting, then shorten only one
test-owned session to exercise real servlet expiry; they do not wait 30 minutes.

The previous AUTH-001 remediation on 2026-09-11 first reproduced the path-boundary failures
with permanent safe-behavior tests against the original filters: 28 cases,
20 assertion failures, no errors/skips. The identical test source then passed
28/28 after the matcher/filter-order fix. Fresh authentication tests passed
212/212 (policy 89, PostgreSQL 20, HTTP/OIDC 103); one complete clean test run
passed 638/638, preserving all earlier 611 cases plus 27 new path regressions.
All GREEN runs had zero failures/errors/skips; package passed. New cases cover
multiple encoded segment positions, current-user/expiry invalidation, intent,
Host/Origin, GET-only callback, a still-saved second flow after first-login
completion, and positive code/state/login/logout controls. They are not an
exhaustive URL enumeration or an independent security approval.

The historical AUTH-001-R1 author remediation on 2026-09-11 reproduced the
callback admission race in both identity orders: two behavioral assertion
failures, zero errors/skips on the original implementation. The final focused
set passed three consecutive 16/16 runs (14 HTTP cases and two narrow policy
cases). That authentication verification passed 224/224: policy 91,
PostgreSQL 20 and HTTP/OIDC 113. One complete `clean test` passed 650/650,
preserving all 638 preceding testcase identities and adding 12 cases; package
passed. All GREEN runs had zero failures/errors/skips and exit code 0.
The four existing callback replay/pending-flow cases keep their identities and
principal-preservation checks, with the approved failure expectation changed
from 409 to fixed 303. Its PostgreSQL 18.4 and Flyway V1 → V11 evidence includes
the unchanged pinned V8/V9/V10 regression coverage. These are author results,
not fresh AUTH-001-R2 results, independent approval or a claim that the complete
security surface was audited. The earlier native IDE policy 91/91 and HTTP 113/113
results likewise belong to the preceding gate, not this remediation.

Fresh AUTH-001-R2 pilot author verification on 2026-09-14 reproduced the
late-failure cookie defect against the original R1 production code: two cases,
two behavioral assertion failures and zero errors. The same HTTP test source
remained unchanged through RED and GREEN. After the narrow fix, the final focused
set passed three consecutive 27/27 runs (19 HTTP and eight policy cases), all with
exit code 0 and no failures/errors/skipped. One complete `clean test` passed
658/658, preserving all 650 preceding testcase identities and adding eight;
its authentication suites were policy 97, PostgreSQL authentication 20 and
HTTP/OIDC 115. Tests and package completed with BUILD SUCCESS and exit code 0.
Fresh logs show PostgreSQL 18.4 (`postgres:18.4-alpine`), Flyway 12.4.0 with
V1 → V11 and Tomcat 11.0.25. These local synthetic-IdP/Testcontainers results
support the narrow late-failure author fix, not the superseded universal
invalidation/HTTP-commit contract, independent approval or a new native IDE gate.
`AUTH-COOKIE-FOLLOWUP-001` remains OPEN under its limited local-pilot acceptance.

F separately requires a real React/dev-proxy/browser test, including actual
cookie behavior and cache clearing. A Java HTTP cookie jar cannot prove browser
SameSite/HttpOnly or proxy behavior. P separately requires authorized real Auth0
Free EU setup, hosted DB signup, verification/reset delivery and relogin. No
real Auth0/Google/Apple interaction, tenant/client setup, paid resource, frontend,
rollout, Workspace/RMP API, receipt protocol or commerce work is included here.

Remediation IDE verification, independent re-review and publication are separate future gates.
