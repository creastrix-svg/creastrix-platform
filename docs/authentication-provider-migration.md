# Controlled authentication-provider migration

This is the bounded owner-only runbook for the approved Cognito Frankfurt
slice, integrated by [PR #37](https://github.com/creastrix-svg/creastrix-platform/pull/37) as
`e5b58d43a9dc07c76efc905bc63d8269feaa40fe`; the separately authorized local owner
transfer completed on 2026-10-13, as recorded below. This document
does not authorize AWS/SES changes, production deployment or database execution.
The [pilot contract](authentication-pilot.md) and [backend boundary](../backend/README.md#controlled-provider-migration-candidate-author-wip)
preserve the existing OIDC/session/Q/CSRF and deadline rules.

### 2026-10-14 Auth0 retirement boundary

The owner authorized a new source-retirement candidate: Cognito Frankfurt is
the only supported runtime adapter, with COGNITO/existing-only defaults. Legacy
AUTH0 selection fails closed; its entry/callback paths are unsupported. Strict
signed-ID-token validation (`token_use=id`, raw Boolean verified email, numeric
auth_time), no UserInfo, exact admission and common OIDC/session/Q checks remain.
Candidate review, verification and integration are separate gates; the historical
PR #37 and browser results below do not complete them.

Separately, on 2026-10-14 the owner explicitly approved removal of the external
Auth0 application **Creastrix Local Pilot**. The dashboard's refreshed application
list confirmed its deletion; the tenant, Default App, provider users and private
configuration were retained. Fresh Cognito login before deletion and an account
recheck afterward showed the same ACTIVE internal User. These are observations
on the previous live backend, not proof of the new source candidate. Exact
operator evidence is retained outside Git in
`auth0-retirement-coordination-20261014-aVOF3v`; no private identity values or
credentials are copied into this runbook.

Source retirement does not need another account transfer: the owner already uses
the current Cognito pair installed on 2026-10-13. Keep that User UUID, Profile,
status, generation, rights, selector, both immutable history pairs and receipt.
Future provider support requires a separately reviewed adapter and an explicitly
authorized verified exact-identity operation. V13 provides that bounded offline
mechanism, not automatic email linking, bulk migration or password export.

## Contract and prerequisites

- Preserve the internal User UUID, exactly one unchanged Profile, ACTIVE status,
  eligibility generation, business references and rights. No new Workspace,
  grant, email match, additional User, simultaneously active alias or password
  export is part of this operation.
- Keep the current Cognito configuration and exact owner admission. The retirement
  candidate supports one Cognito registration, default COGNITO/existing-only;
  no historical Auth0 binding is an active alias. External resource disposition
  is recorded above; do not infer permission to delete remaining resources.
- Obtain independent code review and the owner's separate exact-operation
  authorization for any future identity switch. Prove control of the exact old
  and new `(issuer, subject)` pairs through fresh validated provider evidence,
  not matching email. The completed Auth0-to-Cognito operation below required
  those exact provider pairs; retirement itself performs no new switch.
  Do not log/export passwords, tokens, secrets, full claims or identity values.
- Preflight the actual pool/client, original Frankfurt issuer, callback
  `http://localhost:3000/login/oauth2/code/cognito`, allowed scopes and current
  verified provider identity. Updated-issuer aliases are not silently normalized.
  Subject is opaque, not necessarily a UUID. The same email may belong to
  unrelated provider identities and is never account-transfer authority.
- Provider self-registration is allowed, but Creastrix admission remains the
  owner's exact pair. Other provider profiles must not create internal accounts.
  Own SES à la carte is separately selected; verified sender/region/billing and
  required permissions remain a separate setup. Temporary `COGNITO_DEFAULT`
  sending requires explicit agreement and cannot be reported as own SES.
- Before writing, stop only the explicitly owned single application with no
  active requests. Preserve other tasks/containers/volumes. Obtain a verified
  backup using a separately approved method; do not copy a running database
  blindly or overwrite live data. This runbook grants no execution authority;
  the prior authorized local operation is recorded separately below.

## Forward schema and private preflight

The 2026-10-13 owner database already has V13; source retirement adds no migration.
For a separately authorized database upgrade, apply reviewed V13 with the existing
Flyway settings only to that owned database. V1–V12 must remain unchanged. Existing bindings backfill one
current selector; a legacy User without a binding gets no invented identity.
V11 immutable history and global exact-pair ownership remain. New schema is
technical persistence, not a new core entity or an actor-authorized SQL model.

Read current owner UUID/status, exactly one Profile, eligibility generation,
current pair and retained history on the private operator channel. They must
equal the freshly authorized expectation. Verify target pair is either absent
or retained for that same User; another User's pair is a hard refusal.
No credentials or owner-specific values are hardcoded in migration/operation SQL.

Choose and durably retain one operation UUID plus its exact payload privately
before submission. Payload is expected User/generation, old issuer/subject and
new issuer/subject. This is one controlled operation, not the manual-delta
REGISTERED/APPLIED/REJECTED machine or a general command/IAM framework.

## Bounded offline operation

Use the parameterized [psql script](../backend/src/main/resources/db/operations/migrate_owner_identity.sql)
on a fresh direct connection, with `psql -X` and `ON_ERROR_STOP`. Supply
`operation_id`, `expected_user_id`, `expected_generation`, `old_issuer`,
`old_subject`, `new_issuer` and `new_subject` through protected operator input.
Use private connection configuration, not credentials in command history or
evidence. Bound connection acquisition too; database transaction limits do not
prove a bound on client/network delivery.

The script starts one READ COMMITTED transaction with lock/statement/transaction
limits 5/10/15 seconds. The invoker function serializes on the User, rechecks
ACTIVE/Profile/generation/current pair, and atomically retains target history,
receipt and selector. It never creates or changes User/Profile/status/generation
or rights. The receipt carries a generated physical xid8 witness; a selector switch
requires a receipt from that same transaction. An older receipt cannot be adopted
again after a controlled return. The receipt and current selector are coupled at commit; no partial
result is valid. The function's `APPLIED` result precedes COMMIT and is not an
acknowledged commit. Require successful COMMIT acknowledgement and independent
private read-back before considering the new selector installed.

Exact operation replay returns `REPLAY` without mutating state. Changed payload,
wrong old pair/account, inactive owner or a target owned elsewhere refuses.
Receipt replay proves that operation only: a later authorized return may have
changed the current selector. Always verify actual current state before restart.

## Unknown commit and controlled return

On interruption/lost acknowledgement, stop the switch workflow. Do not infer
rollback from a failed response or an absent SELECT while the earlier transaction
may still run. Establish completion/termination of that exact submitted database
transaction using bounded operator observation; read the same receipt/full
payload and current selector on a new connection. If outcome remains unresolved,
keep the application stopped and seek a decision. Do not submit a new operation
identity, delete bindings, change payload or restore a dump over live data.

After a resolved commit, exact replay may reconcile the same operation. Validate
receipt payload, same User/Profile/generation and current pair. A conflicting
receipt or unexpected later switch is not repaired automatically.

V13 still supports a controlled future switch without rewriting identity history.
It does not make any provider immediately usable: review and verify its adapter,
client configuration and exact identity-control evidence first. In particular,
the retired Auth0 adapter and deleted client cannot be restored by changing an
environment variable or restarting. A return to a retained historical pair
would need new explicit authorization and an operation UUID with the expected
current pair and verified target. Reuse that User's historical row; never rewrite
it. Acknowledge COMMIT/read-back, then restart the separately approved runtime
and perform a fresh full login. Old cookies/sessions are not migrated or revived;
dump restore is not the ordinary return mechanism.

## Restart and separate walkthrough

Only after approved commit/read-back select `CREASTRIX_AUTH_PROVIDER=COGNITO`
and `CREASTRIX_AUTH_EXISTING_ONLY=true`, the exact issuer/client and owner subject
in a separately authorized backend-only configuration. Keep client secret out
of React, Git, logs and evidence. Retained obsolete private configuration must not
be loaded as an automatic fallback or deleted without its own authorization. Restart
the owned application only under its separate authorization. Process-local
sessions/Q contexts are discarded; bootstrap/echo and fresh full login are required.

Real Cognito walkthrough remains separate from synthetic author tests and IDE
verification. Prove unverified/admitted refusal, unknown/unadmitted refusal without
internal creation, verified owner `/api/me` with the unchanged internal UUID,
same Profile/history/generation, local logout/private refusal, repeat login and
password recovery/relogin. Check actual signed claim types, auth_time, state/nonce
and code/client/issuer freshness; ID-token-only mode must not contact UserInfo.
No email linking or superseded-pair authentication is allowed. A successful
provider screen alone is not private Creastrix access or commit proof.

The historical empty-signup observation of a Confirm password field was not
signup, email-delivery, case-sensitivity or login proof. The bounded owner result
below does not establish every provider/signup behavior. Google/Apple and optional
TOTP enrollment remain separately authorized future work. Local logout is not
provider/global/all-device logout. Existing Q G1/G2 and cross-Q L1 limits, late
failure correction and immutable committed bindings remain; the migration does
not resolve `AUTH-COOKIE-FOLLOWUP-001`, authorize public access or perform rollout.

## Local owner result and remaining gates

The separately authorized 2026-10-13 local operator evidence records:

| Boundary | Observed result |
| --- | --- |
| Backup and restore proof | Private V12 backup verified in a separate database; live data was not overwritten by restore. |
| Live V13 and switch | Reviewed V13 applied to the authorized local database; one acknowledged COMMIT operation, full receipt and current selector independently read back. |
| Identity/data preservation | Same User UUID, Profile, ACTIVE status, eligibility generation, rights and business rows; both historical pairs retained, one migration receipt. |
| Selected runtime | Cognito existing-only, one exact owner identity; Auth0 tenant/client/account/private configuration retained but not a current alias. |
| Owner walkthrough | Callback 303 and private API 200 with the same ACTIVE User; local logout 204 followed by 401; repeat login and login after the owner-reported password change returned private API 200. |

These are external operator/browser results, not new execution by this alignment,
source integration themselves or a public deployment. Later PR #37 integration
is separate. Private identity payload, dump,
credentials and password remain outside Git/public evidence. Callback correlation
uses request IDs; private API observations use ordered bounded phases and fresh
account UI, not a logged session identifier. The owner performed and reported the
password change; its private contents were not inspected by the operator.

**Real unverified/admitted refusal remains NOT RUN.** An unrelated self-signup
identity is outside exact-owner admission, and hosted Cognito may stop an
UNCONFIRMED account before issuing code/token. Such provider-level refusal does
not exercise backend rejection of a signed raw `email_verified=false` claim
for an admitted identity. Synthetic false/missing/string-claim tests and the
verified-owner positive control are separate evidence, not reproduction of that
real signed-false case. The explicit limited owner disposition below does not
change the raw claim policy or the owner's account.

### Limited EMAIL-gate disposition

On 2026-10-13 the owner answered "Да, принять для локального пилота" for the
review's alternative 2 only: verified real owner login; real UNCONFIRMED/unadmitted
hosted refusal with the same cookie jar, private 401 and no internal creation;
synthetic signed admitted false/missing/string email-verification controls plus
their valid owner control. This is accepted as limited sufficient EMAIL evidence
for source-publication readiness of the owner-only nonproduction pilot, not as
fresh approval of the recovery implementation or an independent review result.

The actual admitted signed-false backend scenario remains **NOT RUN**. An
UNCONFIRMED provider profile is not a confirmed profile with unverified email,
and hosted refusal before code/token is not backend raw-Boolean rejection.
No actual identity status/verification, admission or raw-claim rule was weakened.
This disposition does not authorize public signup/admission expansion, rollout,
SES setup, provider/browser recovery or close native/cookie/CI/publication gates.
It is not automatically transferable to a future broader pilot or production.

### Confirmation after closing the signup page

The integrated recovery slice adds `/confirm-email`, linked from `/login` and
available as a direct empty page. Email/code are transient local form/request
fields only; no password, URL/query/history payload or persisted account data is
introduced. Each explicit confirm/resend action uses fresh standard session CSRF
and an anonymous-only exact Host/Origin backend boundary. Provider-only recovery
does not require live Q/echo/canStart and does not automatically logout or reset Q.
Already authenticated requests refuse without identity mutation; the retirement
candidate supports no alternative provider mode for these routes.

The existing confidential backend client submits only ConfirmSignUp or
ResendConfirmationCode to the fixed Frankfurt API, preserving exact entered
identifier case and using SecretHash; ForceAliasCreation=false forbids stealing
an alias. Session/metadata/auto-login, signup/admin actions and caller-supplied
pool/client/endpoint are excluded. Code confirmation is not exact-pair Creastrix
admission and creates no User/Profile/binding/selector/receipt/principal/Workspace.
Returning to `/login` starts an explicit fresh ordinary OIDC flow, not reuse of
the confirmation response as authentication.

Known provider confirmation alone selects confirmed 200. Resend's generic 202 is
conditional, not existence/delivery proof. Wrong/expired codes and recipient-state
refusals remain opaque; transport/deadline uncertainty does not prove rollback or
that Cognito did not act. Input/response bytes, in-flight work and retained hashed
target/global quotas are bounded and no automatic provider retry occurs. Stale
frontend completions are discarded. Synthetic author tests and this owner EMAIL
disposition do not prove real recovery/delivery or reload/browser behavior; the
separately authorized bounded owner walkthrough is recorded below.

### Fresh recovery source verification

The separate isolated author run passed focused 528/528, diagnostic catalog
163/163, final full 1826/1826 in 33 suites and package BUILD SUCCESS, with zero
failures/errors/skipped and actual PostgreSQL 18.4 / Flyway V1 → V13. Frontend
234/234, typecheck/lint/build passed. All original backend 1690/frontend 140
testcase identities remain. First failed attempts are retained, not relabelled.
Only disposable copies, fake provider/test OIDC and owned loopback PostgreSQL
were executed. The live database/application/target and Auth0 were untouched.
Separate scoped native, focused independent source/JAR review and owner recovery
results are recorded below; source tests are not those proofs or rollout authority.

### 2026-10-14 scoped recovery evidence

These separately authorized external results used the exact unchanged reviewed
`retry-03` source/JAR. Those operator/native observations did not themselves
integrate source or authorize publication/public operation; the later reviewed
PR #37 merge below is separate and does not expand one-owner existing-only admission.

| Evidence boundary | Bounded observed result |
| --- | --- |
| Focused independent review | Exact frozen source and packaged JAR accepted for bounded local verification; no confirmed findings. No new test/provider execution by that reviewer. |
| Native IntelliJ | Recovery 91/91 and HTTP 195/195, exit 0; six pre-alignment document source/render views completed and Project Errors empty at the scoped checkpoint. Five annotation/unused/style hints and grammar suggestions were classified, not fixed. |
| Explicit recovery | One resend selected conditional 202, not a delivery receipt. After the form/window was reopened, the owner entered the code; confirmation selected 200 and the UI reported provider email confirmed. The reopened cookie jar then received private 401: provider confirmation did not sign the owner in. |
| Fresh ordinary OIDC | A separate hosted owner login selected local success and callback 303, then private 200. The account UI showed Current account verified / ACTIVE and the same expected internal User UUID. |
| Data preservation | Separate read-only server/database closeouts found no internal creation at confirmation and unchanged original DB rows after fresh login: same User/Profile/generation/rights, retained bindings/current selector/receipt; changed tables empty. UI alone is not this proof. |

Primary records are retained externally in
`cognito-recovery-delivery-20261014-qB1HGs`: resend-result.json,
confirmation-result.json, owner-confirmation-ui.json, fresh-owner-login-result.json,
fresh-owner-login-ui.json and native-final-result.json. The separate focused
source/JAR review is `cognito-confirm-email-independent-review-20261014-h9oS4M`.
The code/password and private identity payloads are not copied into these docs.
No generic delivery rate, arbitrary browser lifecycle/order or public-signup
behavior is inferred from this one-owner recovery. Overall IDE background/schema
remains UNKNOWN/INCOMPLETE, not a runtime failure or blanket native PASS.

The exact reviewed source was merged through [PR #37](https://github.com/creastrix-svg/creastrix-platform/pull/37) on 2026-10-14 as
`e5b58d43a9dc07c76efc905bc63d8269feaa40fe` (tree `7f2eec7bf492c5d32cffa5d89a161d776df421d7`).
Fresh push/main [Backend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930693)
passed 1826/1826, zero failures/errors/skipped and tests/package BUILD SUCCESS;
[Frontend CI](https://github.com/creastrix-svg/creastrix-platform/actions/runs/38534930663)
passed 234/234, locked install, typecheck, lint and build. These are separate
post-merge checks, not new native/browser runs. Integration evidence is retained in
`cognito-merge-20261014-islXPJ/REPORT.md`.

That PR #37 factual alignment ran no new tests/provider/browser/database actions
and retained Auth0/defaults at that time. The later retirement boundary is recorded
above rather than retroactively changing those results. Actual admitted signed-false remains NOT RUN under
the limited EMAIL alternative-2 disposition above. The [local cookie disposition and retained public gate](authentication-pilot.md#open-cookie-follow-up)
records accepted local-only closure. The historical npm HIGH warning is retained;
the [lockfile follow-up and installed-environment limits](../frontend/README.md#dependency-follow-up)
record the accompanying `source-map-js` 1.2.2 update and isolated verification. Own SES, public access/invitations and rollout remain separate and unauthorized.

Historical independent backend focused 417/full 1690/package and frontend 140
results retain their exact scopes. Native compilation/417 tests and scoped file
views are preserved; overall IDE schema/background completion remains
UNKNOWN/INCOMPLETE. Earlier stopped/incomplete reports are not rewritten as PASS.
The historical reports keep their original verdicts. PR #37 source publication/
integration is complete. Documentation review and publication are separate
from the historical runtime checks. Own SES, social/TOTP,
public access and rollout remain separate under the OPEN public-readiness gate.
