# Backend authentication pilot contract

AUTH-FIRST-LOGIN-001 is integrated in main through
[PR #28](https://github.com/creastrix-svg/creastrix-platform/pull/28). This document
records its owner-authorized, bounded technical contract for a single-instance
local nonpublic pilot, not an APPROVED domain specification or public release.
Backend result B passed author, native IDE, independent R2 re-review and CI gates;
the distinct evidence is recorded below under verification gates.
React/browser result F and actual Auth0 walkthrough P
are not done; backend tests do not prove working user-facing login.
S003 is integrated through PR #34 as recorded below. The current feature branch
adds a proposed React client, not independent approval or completed F evidence.
Historical server integration and current frontend unit/build checks do not close
the follow-up, establish a new APPROVED domain specification or authorize public access.

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

One backend listens at `127.0.0.1:8080`. The connected browser origin is exactly
`http://localhost:3000`; the proposed separately built dev proxy must preserve the
browser Host/Origin, route backend namespaces before SPA fallback and pass
Set-Cookie/status/Location unchanged. Backend neither creates that proxy nor
derives trusted destinations from Host/Forwarded headers. No CORS is enabled.

| Route | Response / boundary |
| --- | --- |
| `GET /auth/csrf` | JSON token, `_csrf` parameter name, `X-CSRF-TOKEN` header name; eligible anonymous bootstrap issues Q once, later requests echo it; this is not login |
| `POST /auth/login` | Exact Origin + form `_csrf`, explicit intent and admissible Q/session stamp; authenticated or inadmissible pair gets 409; valid initiation gets fixed 303 |
| `GET /oauth2/authorization/auth0` | One live session intent required before the standard OIDC redirect |
| `GET /login/oauth2/code/auth0` | Protocol-gated callback; no local Origin required on this GET; busy/already-authenticated conflict uses fixed failure 303 |
| `GET /api/me` | Current session/admission/ACTIVE plus final captured-stamp admission after DB lookup; only own `id/status`, no identity-provider claims or tokens |
| `POST /auth/logout` | Exact Origin + CSRF; revoke presented Q generation and clean caller SID; 204 for Q revocation, cleanup-only 409 for unknown/expired Q |
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

No mutex is held during provider or database work. Session publication has its
own coordinator; S003 adds short shared memory coordination for Q ownership,
without enclosing provider/DB or Servlet calls. After that work, ownership is
rechecked before Spring's first authorized-client save, not merely in the configured success handler. A short
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

### S003 cookie-pairing contract

S003 adds a process-local `CREASTRIX_Q` context and an immutable session stamp
`(Q, generation, logical session token)`. Q is a random technical cookie, not a
durable browser identity, User identity or permission. It is host-only, HttpOnly,
SameSite=Lax, Path `/`, with the existing Secure policy. Anonymous bootstrap
may issue Q once; an echo round trip is required before login. Callback success
never sets Q. A tagged/authenticated session is not adopted into a new Q, and a
lost initial issuance is not repaired by reissuing its Q.

The guarantees and exception are distinct:

1. **G1, same Q:** private access captures its session stamp before current-User
   lookup. After the actual DB lookup, final admission checks that same immutable
   capture against the live generation and accepted member. Revocation or
   acceptance of a newer generation cannot authorize old A; the request cannot
   borrow a newer stamp after lookup. Standard current-User/admission checks remain.
2. **G2, current cookie jar:** fresh requests must present a valid Q/SID pair.
   Ordinary late response delivery must not silently restore the old authorized
   Q_A/SID_A pair after B. A stale A SID with current Q_B, or a retired same-Q
   stamp, receives 401 and requires explicit recovery. Preserving B seamlessly
   under every delivery order is not promised; fail-closed recovery is acceptable.
3. **L1, older cross-Q request:** two initial bootstraps can create different Qs.
   An A request under still-live Q_A can be delayed after User lookup but before
   final admission; B is accepted under Q_B; A then resumes, first gains final
   admission and returns A. It was not already admitted before B. This is an
   explicit limitation, not a defect-fixed GREEN result or only a UI issue. It
   does not permit a fresh current-jar B-to-A result. Already-admitted responses
   may also finish after revocation. Future frontend code must discard stale
   account responses; future business writes are not proved safe by this exception.

Logout passes standard Origin/CSRF checks before revoking the presented Q
generation, including its pending owner, rather than selecting Q from a stale
SID. Exact old session references are cleaned separately, outside registry locks.
204 means a known live Q was revoked and permits `LOCAL_LOGOUT_COMPLETED`;
unknown/expired Q produces cleanup-only 409, expires the caller SID and does not
claim successful Q revocation. This is not global provider or all-device logout.

Recovery for unknown/expired Q or actual process restart first obtains CSRF,
performs caller-SID cleanup, then bootstraps without an incoming or resolved SID.
Only a proven unknown/expired Q permits a new random Q; any still-unexpired record,
including pending/retired ones, prevents replacement issuance. A live Q uses
ordinary anonymous attachment/echo instead, never adoption of old authentication.
Recovery completes a new full OIDC login with the same external identity and
unchanged committed User/Profile/binding. It neither deletes/duplicates bindings
nor blindly replays an authorization code or guesses a commit-unknown outcome.

The registry is single-instance memory with an eight-hour hard Q lifetime,
64 context records, 128 references, eight members per Q and eight cleanup
claims/workers without a task queue. Five minutes bounds pending pairing/owners.
Retired records remain charged until exact acknowledged cleanup; no capacity
repair evicts them. Memory-lock acquisition and cleanup caller waiting each have
a 100 ms bound, not a full Servlet-operation deadline. Cleanup is explicitly
requested and invalidates exact owned references outside coordination locks;
stalled/failed cleanup remains charged. Capacity/coordination refusal fails closed
with opaque 503 outside callbacks or fixed failure 303 on callbacks; an already
committed response is not rewritten. No container monitor encloses commit/flush.

Standard OIDC/PKCE, CSRF, fixation rotation, callback ownership, the integrated
late-failure correction and immutable committed bindings are preserved. The
stamp gate is a server admission decision, not Redux error hiding or reliance
on a fresh `/api/me` alone. Other still-live Qs are not globally linked/revoked.
Separate PA observer/HOLD and combined foundation/HTTP/CI candidates are not
composed into this exact S003 transfer.

### Open cookie follow-up

`AUTH-COOKIE-FOLLOWUP-001` remains **OPEN pending full F and explicit disposition**.
The owner's temporary local nonpublic acceptance on 2026-09-14 did not accept
universal invalidation/HTTP-commit atomicity or automatically authorize the later
observed B-to-A consequence. The S003 candidate and scoped evidence do not close
the follow-up, authorize public access or promise revocation of delivered responses.
Natural frequency, account takeover and the distinct post-commit network-delay
scenario are not demonstrated. Earlier incomplete browser attempts remain historical.

Required gates remain:

1. Full React/dev-proxy/browser authentication verification must assess overlapping
   responses, current cookie pairing, explicit recovery and stale-response UI
   handling. The scoped browser proof below is not that complete F gate.
2. Before public access, invitations or rollout, require separate owner/security
   disposition and appropriate independent evidence. Local acceptance is not
   public-operation authorization; a merge or test count alone is not closure.

### Callback navigation and opaque errors

Own navigation redirects are literal absolute 303 Locations: login initiation
to `http://localhost:3000/oauth2/authorization/auth0`, callback success to
`http://localhost:3000/account`, failure to
`http://localhost:3000/login?auth=failed`. No user redirect parameter is accepted;
session IDs are never URL-encoded. `/login` and `/account` HTML belong to the
React client, not backend. Login is a top-level form POST, not fetch-follow to Auth0.

API errors are opaque JSON: 401 for absent/expired session, 403 for confirmed
inactive/denied access or invalid security input, 503 for unavailable current
state. Callback failure reveals no account/binding/SQL/token detail and makes
no rollback claim. Auth/API responses are not cached. DB failure denies access
but is not proof that logout or session invalidation succeeded.

### React client candidate and F boundary

The current `solar_wind/react-authentication-f` change is author-side WIP, not an
integrated or independently approved client. It replaces fictional account data
with owned `/api/me` `id/status` rendering and explicit hosted login, local logout,
switch/recovery. The fixed loopback development proxy routes backend namespaces
before SPA fallback, including accepted percent-encoded letters, without URI/query,
Host/Origin, Location or cookie rewriting. Preview is disconnected. Actual proxy
and browser behavior remain separate F gates; config-unit checks are not proof.

One controller outside StrictMode owns each document operation. Checks surround
fetch/body parsing, failure paths and later network/submission steps; reducer
admission also rejects stale owners. Success, denial, transport/parse error, CSRF
and cleanup completion from old A cannot overwrite/erase newer owned B. Private
identity is memory-only. The non-secret `creastrix.auth.boundary` marker contains
only a replaceable nonce and intent phase: writes occur only at document startup
or explicit intent, never on completion, peer events or lifecycle revalidation.
Phase is not an outcome/CAS. It is not a cookie identity, permission, stable
browser identifier, cross-tab lock or global login ordering. Invalid/unavailable
storage fails closed. DE/EN language persistence stays separate.

Peer invalidation and hide/pagehide synchronously close a DOM curtain, clear private
state/CSRF and cancel owned work. Visible/focus/pageshow requires a fresh owned
validation before reopening after the current DOM commit. Paused tabs, delivered
pixels and bfcache require browser verification. G1/G2 remain server guarantees;
L1 and future business-write limits are not closed by frontend stale-result rejection.

Explicit login uses two sequential CSRF reads for bootstrap/echo and a native
top-level form POST, never fetch-follow to an IdP. Only logout 204 confirms
presented-Q revocation; 409 is cleanup-only. Recovery permits bootstrap/echo only
after those outcomes, then requires a new explicit full login. Unknown/503/denied
results never imply logout success, binding rollback or automatic retry. Reads
have a 10-second deadline and each client preparation/cleanup action a 30-second
deadline, not a provider/navigation/delivery bound after native submission. Early native
login 403/409/503 can display opaque backend JSON outside React, an explicit bounded
UX limitation without proxy/backend response rewriting.

Vitest/DOM/static/build evidence is author verification, not browser F or native
IDE execution. The future F gate must use actual React, Vite and exact integrated
backend, synthetic OIDC and a supported fresh isolated cookie context with bounded
two-tab/lifecycle/recovery ordering and loopback/cleanup ownership. No real Auth0 P,
Workspace/RMP API/forms, linking/social login, global logout, public operation or
rollout is proved. See the [frontend guide](../frontend/README.md).

### Diagnostic observation boundary

BACKEND-OBSERVABILITY-001 provides bounded application diagnostics in this checkout.
This section describes behavior, not verification or integration status. The
diagnostics do not change the integrated pilot's authorization, OIDC validation,
callback ownership, logout or opaque HTTP contracts.
The [backend diagnostic vocabulary](../backend/README.md#bounded-application-diagnostics)
defines fixed event/reason codes and safe fields; the historical verification
results below are not fresh evidence for this change.

Each request uses a server-generated correlation ID, with explicit ID-only
transfer to the identity-resolution virtual worker and scoped cleanup. No
credentials, tokens, cookies/session IDs, OAuth/CSRF values, provider claims,
identity pairs, personal data, raw URLs, request content, SQL parameters or
exception messages/causes are output. Internal exception causes may remain
available to code; they are not rendered to logs or clients. Authentication
markers stage bounded codes without output under session/publication locks and
are emitted after the security chain unwinds. Expected denial/conflict remains
distinct from technical unavailability and unexpected failure, without duplicate
generic reports of the same marked event. Later technical failures take precedence
over earlier expected outcomes. A subsequent throw is recorded as a failure with
bounded prior event/reason codes, not hidden by an earlier local success selection.

`LOCAL_SUCCESS_SELECTED` observes local callback outcome selection, not response
commit/delivery, browser cookie order or final identity. `LOCAL_LOGOUT_COMPLETED`
is local only. Absent session state is not proof of idle expiry; confirmed
absolute expiry has its own event. A lost commit acknowledgement remains UNKNOWN
even if Spring subsequently reports rollback; neither outcome is guessed from
the exception alone. A transaction UNKNOWN is not rollback, and a
committed account transaction is not a successful browser login. This is neither
a durable audit trail nor an authentication-cookie correction.
`AUTH-COOKIE-FOLLOWUP-001` remains OPEN; React/browser F, real Auth0 P and public
rollout are not performed or authorized by this diagnostic change.

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
responses cannot be recalled. The future React must handle stale responses
without updating a newer account, clear cached identity on errors/logout and
verify a fresh `/api/me` after explicit recovery. A fresh read alone is not a
stale-cookie fix: the pre-S003 valid-A scenario already returned 200 A. A public
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

Historical AUTH-001-R2 pilot author verification on 2026-09-14 reproduced the
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

### Completed verification and integration

These are distinct completed checks, not new executions by this documentation change:

- Author-side native IntelliJ verification on 2026-09-14: policy 97/97 and
  HTTP/OIDC 115/115, failures/errors/skipped 0/0/0, both exit 0; editor/Markdown
  and diagnostics checks completed. Earlier incomplete passes remain historical.
- Independent narrow R2 re-review completed on 2026-09-25 without
  BLOCKER/IMPORTANT/MINOR findings in its agreed scope: three focused 27/27 runs,
  full 658/658, failures/errors/skipped 0/0/0 and package BUILD SUCCESS. It confirmed
  the rejected/uncommitted late-failure correction, not universal race freedom.
- [PR Backend CI 36113025923](https://github.com/creastrix-svg/creastrix-platform/actions/runs/36113025923):
  `pull_request`, feature SHA `ae8f2b96ed1d13513a07f309e45237357616919a`, SUCCESS;
  658 tests, failures/errors/skipped 0/0/0, tests/package BUILD SUCCESS.
- [PR #28](https://github.com/creastrix-svg/creastrix-platform/pull/28) merged on
  2026-09-25 as `a868b517bf2b78d77bedba3dd1374241c3def6f4`, with the reviewed
  feature tree unchanged. Repository integration is not deployment.
- [Post-merge Backend CI 36115810224](https://github.com/creastrix-svg/creastrix-platform/actions/runs/36115810224):
  `push`, branch `main`, exact head `a868b517bf2b78d77bedba3dd1374241c3def6f4`,
  SUCCESS; 658 tests, failures/errors/skipped 0/0/0, tests/package BUILD SUCCESS,
  actual PostgreSQL 18.4 (`postgres:18.4-alpine`) and successful Flyway V1 → V11.

Backend B is verified within this local nonpublic contract; F and P remain
unverified. AUTH-COOKIE-FOLLOWUP-001 remains OPEN under the required browser and
pre-public-access gates above. No next implementation slice or rollout is selected.

### S003 evidence and open gates

These are distinct dated external results, not fresh executions, native IDE
verification, PR CI or post-merge CI for this transfer:

- The 2026-10-07 independent forward-port source review found no findings in its
  narrow scope: seven Java paths, six modified plus one added, +3062/−25. The
  reviewed patch and all frozen source bytes/modes match this Java transfer.
  Static review alone did not prove runtime behavior.
- `ROOT-COOKIE-NINE-DISPOSITION-20261007.json` accepted the retained nine-case
  server evidence: three repetitions each of same-Q final admission, L1 cross-Q
  first admission after B, and already-admitted response completion. Actual
  compilation/test/cleanup exits were 0, with no failures/errors/skips. The
  original parent report stays exit 1/evidence rejected because of its separate
  classpath check; the disposition is scoped, not a rewritten clean orchestration
  run. L1 is characterization, not a defect-fixed GREEN claim.
- `ROOT-COOKIE-BROWSER-DISPOSITION-20261007.json` accepted seven scoped browser
  scenarios and causal witnesses, with bounded offline ledger validation. Late
  success yielded fresh 401 rather than A and explicit recovery returned B;
  late failure retained B; dual-bootstrap late anonymous-pair delivery yielded
  401 and recovery B. The original R6 parent INCOMPLETE/exit 2 report remains
  unchanged. This acceptance does not turn earlier setup failures into RED
  reproduction or complete the real React/dev-proxy authentication F gate.
- `s003-full-package-binding-20261007-b6Wh7H/RESULT.json` binds the exact frozen
  candidate to one isolated full run: 1618 tests in 32 suites, failures/errors/
  skipped 0/0/0, no flaky/rerun nodes, exact testcase inventory and BUILD SUCCESS.
  The following skip-tests package passed with exit 0, preserved all 64 original
  XML/TXT report payloads and completed owned cleanup. Logs show Java 25.0.4.1,
  Maven 3.9.16, PostgreSQL 18.4 and Flyway V1 → V12. Its external loopback helper
  was test-classpath-only and is not transferred; packaged-start smoke was NOT RUN.

S003 was subsequently integrated through [PR #34](https://github.com/creastrix-svg/creastrix-platform/pull/34)
as merge `594b20134ae3653d1b6d1a315b44c0e6e1282f56`. Its separate
[post-merge Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/37917170767)
on 2026-10-09 passed 1618 tests, failures/errors/skipped 0/0/0 and tests/package
BUILD SUCCESS with PostgreSQL 18.4 / Flyway V1 → V12. These are historical server
integration results, not a new frontend author run or complete F/P proof. PA observer/HOLD and separate
combined foundation/HTTP/CI changes remain separate. Full React/dev-proxy F, real Auth0 P, public application
access, rollout and global revocation are not verified. Source transfer is not
follow-up closure: `AUTH-COOKIE-FOLLOWUP-001` remains OPEN pending full F and
explicit disposition, with the browser and pre-public-access gates retained.
