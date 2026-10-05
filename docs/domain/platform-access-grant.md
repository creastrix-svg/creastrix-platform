# Platform Access Grant

## Purpose

A Platform Access Grant is an explicit, bounded assignment of defined platform permissions to one existing User. It is not a User identity, authentication proof, Organization/Workspace membership, or a universal IAM policy.

This APPROVED 1.1 specification extends the accepted Platform Access Grant 1.0 contract with the separately reviewed and accepted I2A-R1 account-generation, owned-transaction, intent, receipt and audit requirements. Specification approval does not implement authority or authorize real privileges; implementation, activation and rollout require their own authorization and verification.

## Responsibilities

- Preserve a stable grant identity, immutable recipient User reference, and assignment provenance.
- Identify an immutable finite catalog/bundle version, explicit resource scope, lifecycle and validity terms, and monotonic authorization revision.
- Keep current assignment separate from historical revoked grants and from effective authorization.
- Define ordinary staff eligibility, allowed delegation, and the facts required by a deterministic pure policy decision.
- Require separate, current service admission, atomic security audit, and uncertain-outcome handling in future live workflows.
- Keep exceptional maintenance authority separate from ordinary grants and preserve unrelated domain boundaries.

The grant does not own User status, provider credentials, session storage, commercial entitlement, or a general command/event system. Security audit is an access-workflow obligation; it does not introduce a broad Audit Log entity.

## Relationships

- Each grant has a stable grant ID and exactly one immutable recipient [User](user.md) UUID. Email, name, provider roles, and claims cannot substitute for that identity or rewrite a committed external binding.
- A User has at most one current non-REVOKED grant. ACTIVE, SUSPENDED, and expired grants all occupy that slot, including grants belonging to an inactive User. There may be many historical REVOKED grants; they do not occupy it. No permission union across grants exists.
- The grant references exactly one known role and exact catalog/bundle version, not an extensible collection of arbitrary permission strings.
- [Organization Membership](organization-membership.md) remains organization-local. Organization OWNER is not platform authority or Workspace access.
- [Workspace Membership](workspace-membership.md) retains its own ACTIVE User, ACTIVE Membership, role, scope, and domain prerequisites. Platform authority never substitutes for it.
- Invitations, bootstrap, recovery, upgrade, and security-audit records belong to bounded workflows, not additional Case, command, event, or recovery core entities. Audit Log remains a PLANNED concept without an active specification in the [domain registry](README.md).

## Business Rules

### 1. Current ordinary authority

An ordinary staff action requires the exact authenticated actor, an ACTIVE User, that User's sole current ACTIVE grant within its validity interval, a recognized permission/version, the exact allowed resource and projection, and valid session assurance. Unknown, missing, inconsistent, unsupported, or ambiguous facts fail closed. A role label, justification code, existing session, or owner title alone is insufficient.

The trusted application adapter must establish actor, current grant, resource identity, and relevant facts. It must not accept a browser-supplied authority/context DTO as evidence. Immutable synthetic policy inputs are not authentication, database freshness, cardinality, audit, or commit proof.

A User may be a staff member and an ordinary buyer using the same identity. Account/login/buyer access does not require a Workspace; Workspace creation remains voluntary. Grants do not create Memberships, seller eligibility, Designer/Manufacturer Profiles, publication rights, or payout entitlement. Staff permissions do not authorize tenant business access, impersonation, identity rebinding, secret disclosure, history rewriting, or domain-invariant bypass.

### 2. Immutable first catalog and resource predicates

Catalog v1 and bundle v1 are immutable. Only the following six permission identifiers exist in the first I1 catalog:

- `STAFF_GRANTS_READ`
- `STAFF_INVITE`
- `STAFF_GRANT_CHANGE`
- `STAFF_GRANT_SUSPEND_REVOKE`
- `USER_SECURITY_READ`
- `SECURITY_AUDIT_READ`

`PLATFORM_OWNER` v1 contains exactly all six. `SUPPORT_READ` v1 contains exactly `USER_SECURITY_READ`. There is no ACCESS_ADMIN role, wildcard permission, role inheritance, permission union, or implicit future capability. Unknown role/catalog/bundle/permission is denied. Deploying code does not change an existing bundle or grant.

OWNER scope is the finite platform-security-metadata predicate defined below, not `*` over tenant/business data. SUPPORT_READ scope is an explicit nonempty set of at most 100 distinct, exact existing target-User UUIDs, used only for the user-security projection. Empty, duplicate-containing, oversized, nonexisting, or unknown target sets are invalid; duplicates are not silently removed. Later creation of a User does not add that User to the set. The 100-target bound is a project decision, not an external standard.

I1 evaluates one concrete target resource at a time. It does not add bulk query, list, search, paging, or export endpoints:

| Permission | Concrete target and maximum permitted projection |
|---|---|
| USER_SECURITY_READ | One exact existing User UUID; only `userId` and `accountStatus`. A target may itself be inactive; actor actionability is separate. SUPPORT_READ additionally requires membership in its exact scope set. |
| STAFF_GRANTS_READ | One exact grant ID and its associated recipient identity; role, catalog/bundle version, revision, lifecycle, validity, scope, and minimal assignment/change/revoke actor/time references. No provider subject, raw claims, credentials, or unrelated User/Membership data. |
| SECURITY_AUDIT_READ | One exact security-audit event identity within the allowed access/security event catalog; only the bounded audit projection in section 8. No financial/customer bodies or arbitrary log payloads. |

These are purpose-limited platform metadata operations across exact account/security targets, not general cross-tenant business reads. Grant and audit metadata reads may include the actor's own records; the self-target prohibition below concerns mutations. Neither support-target membership nor an audit event identity establishes the truth or freshness of the supplied resource snapshot.

### 3. Finite read justification

Only these role/permission/reason/target combinations are valid; every unlisted combination is denied:

| Role | Permission | Allowed reason | Target restriction |
|---|---|---|---|
| PLATFORM_OWNER | USER_SECURITY_READ | USER_REQUESTED_SUPPORT | Exact User and user-security projection |
| PLATFORM_OWNER | USER_SECURITY_READ | SECURITY_REVIEW | Exact User and user-security projection |
| SUPPORT_READ | USER_SECURITY_READ | USER_REQUESTED_SUPPORT | Exact User in the grant's finite set; same projection |
| PLATFORM_OWNER | STAFF_GRANTS_READ | ACCESS_REVIEW | Exact grant/security assignment metadata |
| PLATFORM_OWNER | SECURITY_AUDIT_READ | ACCESS_REVIEW | Exact bounded security-audit event |
| PLATFORM_OWNER | SECURITY_AUDIT_READ | SECURITY_REVIEW | Exact bounded security-audit event |

`SECURITY_REVIEW` covers user-security and security-audit reads, not staff-grant reads. `ACCESS_REVIEW` covers grant/audit reads, not user-security reads. This finite interpretation is part of the candidate submitted for review. Reasons describe an admitted purpose; the code alone does not establish permission or factual authorization. Unknown/missing reasons and arbitrary free-text justification are rejected. These read reasons do not authorize mutations and are not copied from untrusted claims into logs. No Case entity is introduced.

### 4. Ordinary assignment and changes

Only an eligible PLATFORM_OWNER can perform the following ordinary mutations, and only for a different User's SUPPORT_READ assignment. Self-target and OWNER-target mutations are denied, even if the actor holds another relationship to that User. SUPPORT_READ cannot delegate anything. There are no transitive grants.

| Action | Required permission | Exact effect and target conditions |
|---|---|---|
| INVITE | STAFF_INVITE | Propose one new SUPPORT_READ assignment to an existing ACTIVE User with no non-REVOKED grant. Freeze issuer authority, recipient/binding, finite scope, and absolute expiry. No grant is created by sending an invitation. |
| CHANGE | STAFF_GRANT_CHANGE | Change scope and/or expiry of the same nonexpired ACTIVE or SUSPENDED SUPPORT_READ grant. Target User must be ACTIVE. Preserve grant ID, recipient, role/catalog/bundle version, lifecycle state, and original start/issuance time. |
| RESUME | STAFF_GRANT_CHANGE | SUSPENDED → ACTIVE for a nonexpired SUPPORT_READ grant and ACTIVE target User; preserve scope and expiry, but revalidate the unchanged expiry against the current time and acting-owner ceiling. |
| SUSPEND | STAFF_GRANT_SUSPEND_REVOKE | ACTIVE → SUSPENDED for another User's SUPPORT_READ grant. The target User may be inactive. An expired ACTIVE record may be suspended, but stays expired and still occupies the slot. |
| REVOKE | STAFF_GRANT_SUSPEND_REVOKE | ACTIVE or SUSPENDED → REVOKED, including expired grants and grants of inactive target Users. Terminal revocation releases the occupied slot; it grants no rights to that User. |

All five actions are critical and require the fresh step-up in section 7; a small scope reduction is not an exception. CHANGE never doubles as RESUME. A lifecycle action that requests its existing state is denied. CHANGE intentionally keeps state, but a no-op with neither scope nor expiry changed is denied. A scope-only CHANGE keeps the exact prior expiry and revalidates all resulting bounds; an expiry-only CHANGE keeps scope and original start. Invalid proposals are denied, not silently clamped, trimmed, or partially applied.

Each committed change to authorization-relevant fields/state produces a strictly greater revision. The proposed deterministic representation starts at revision 1 and increments by exactly one, with checked arithmetic; no wrap or reuse. An expected-revision mismatch denies mutation, not last-writer-wins. Denied/no-op/rolled-back mutations do not advance revision. Existing elevation bound to the old revision becomes unusable. Idempotent historical outcomes require a future workflow contract, not a second mutation masquerading as a successful repeat.

An invitation does not reserve the grant slot. Acceptance is a separate, single-use future workflow with exact recipient consent, not a new permission or ordinary staff authority exercised by that not-yet-staff recipient. It must recheck the current issuer, target, frozen offer, expiry and free slot at its final gate. The proposed invitation binds the issuing owner User, grant identity and revision; revocation, replacement or revision change of that authority does not revive or silently re-authorize its pending offers. A fresh offer requires a fresh authorized action.

Acceptance cannot change recipient, role, scope, or absolute expiry. It establishes grant start at successful issuance, not at invitation creation, and does not award another 30 days. Concurrent acceptances serialize: at most one creates a non-REVOKED grant; a losing acceptance does not overwrite the winner or choose another target. Pure I1 does not implement acceptance, prove uniqueness, or establish a recipient authentication/assurance protocol; those belong to the separately authorized invitation workflow and must not bypass audit or the frozen delegation limits.

Revoking an issuer after an assignment was already successfully accepted does not implicitly cascade into every issued grant. Each accepted grant has its own state/expiry; any future cascade needs a separately bounded decision. This does not weaken fresh issuer checks while an invitation is pending.

### 5. Lifecycle, slot and time

States are ACTIVE, SUSPENDED and terminal REVOKED. A grant is initially ACTIVE only after an authorized assignment succeeds. Allowed later transitions are ACTIVE → SUSPENDED, SUSPENDED → ACTIVE, ACTIVE → REVOKED and SUSPENDED → REVOKED, subject to the action rules. Revocation never deletes history or changes the User identity; replacement uses a new grant ID.

Expiry is a derived validity condition, not a fourth state. ACTIVE/SUSPENDED grants occupy the slot even when expired or when the User is inactive. Expired grants cannot CHANGE, extend their expiry, or RESUME. Re-enablement requires successful revocation of the old grant followed by a new authorized assignment. Neither startup nor a background job implicitly renews a grant.

OWNER validity is either explicitly UNBOUNDED or explicitly BOUNDED with an exact expiry; absence/ambiguity is not an unbounded fallback. SUPPORT_READ must always have an exact bounded expiry. At assignment proposal, acceptance, CHANGE, and RESUME, using the fresh final-gate time `now`, the resulting support expiry must satisfy `now < expiresAt <= now + 30 days` and, if the acting/issuing owner's current grant is bounded, `expiresAt <= ownerExpiresAt`. The owner itself must be currently valid. Here 30 days is a checked duration of 30 × 24 hours, not a local-calendar/DST calculation; unrepresentable arithmetic is denied.

The invitation stores the proposed absolute expiry. Acceptance compares that same value with its fresh time and current issuer bounds, never computes a fresh end date. If authority/terms no longer match, deny; do not silently shorten the offer. RESUME preserves expiry but fails if that unchanged value exceeds the current owner's ceiling or any other bound. A separate authorized CHANGE may first shorten the term; RESUME does not combine the actions or implicitly clamp it. Neither action can renew an expired grant.

Effectiveness requires `startsAt <= now`, and for a bounded grant `now < expiresAt`. Equality at start is allowed; equality at expiry is denied. Start/issuance is an actual event, not a future-dated activation feature; future or inconsistent event timestamps fail closed. Future expiry is normal and is validated separately. Time is explicit deterministic input to I1, never a hidden wall clock. Future live evaluation refreshes it after waits at final admission; a snapshot does not promise continuous clock checking through physical commit.

User suspension/deactivation does not rewrite the grant, memberships, ownership, binding or history. It denies ordinary/delegated authority. Reactivation can make an otherwise valid ACTIVE grant eligible for a new complete decision; it does not revive expired/REVOKED grants or old elevation. Ordinary v1 cannot mutate an owner grant, including voluntary last-owner demotion; legitimate security disable remains a separate maintenance workflow. Expiry may leave zero usable owners and does not extend itself.

### 6. First subset versus wider design

`OWNER_HANDOVER_PROPOSE`, `USER_SUSPEND`, `USER_REINSTATE`, `USER_DEACTIVATE` and `SECURITY_AUDIT_EXPORT` are wider R1 proposals, absent from the first catalog and bundles. Owner handover execution is deferred. User-status maintenance exceptions below do not add those ordinary permissions. Catalog/order/finance/commerce and exceptional tenant recovery are not pre-grantable placeholders. Listing, Order Item, Shipment and other existing DRAFT specifications are not promoted.

I1 remains a pure, unwired policy model: one immutable selected-grant snapshot plus exact actor/resource/action/time/assurance facts. One snapshot does not prove that a second non-REVOKED grant is absent. I2 must enforce and prove the single-slot invariant, current data, concurrency, and persistence. Pure ALLOW reports only that the supplied facts satisfy model predicates. It is not permission to omit live authentication, final service admission, audit or transaction commit.

### 7. Bound session assurance

Every ordinary staff read or mutation requires trusted verified-MFA assurance bound to the exact actor UUID, current account-eligibility generation, session generation, grant ID and current revision. Equality with all corresponding current trusted facts is mandatory. Account-eligibility, session-generation or grant/revision change requires newly established matching elevation; old evidence is not relabelled. Missing/mismatched/unknown assurance is denied. Provider roles, `prompt=login`, a passkey label, a fresh DTO or frontend `mfa=true` are not verification.

For I1, the account-eligibility generation is an explicit typed, immutable technical model input, not a new User lifecycle or a boolean claiming MFA/audit success. Every account-status security transition must invalidate the previous eligibility generation and its elevation. Thus SUSPENDED → ACTIVE cannot revive old elevation even if actor UUID, session generation and grant revision remain unchanged. User-status changes still do not mutate the grant. I1 compares the supplied current/elevation generation only; the authoritative source, non-reuse, freshness, and atomicity with the existing User-status workflow still require H/I2 implementation proof. Invalidation is mandatory, not optional cache behavior. The following authoritative-counter contract is a 1.1 requirement, not delivered I1 persistence or an implemented generation issuer.

The authoritative account-eligibility generation is a positive per-User signed 64-bit counter. New and migrated existing Users start at 1. Every successfully committed supported account-status transition advances exactly once with checked addition; invalid/same-state attempts and rolled-back transitions do not advance committed state. Explicit counter updates, including same-value assignments and combined status/counter writes, are rejected. Overflow rejects the complete transition. Grant lifecycle/revision and User identity remain unchanged by generation advancement. No new User status is introduced.

I1 retains its UUID-typed generation through version 1 injective encoding: most-significant 64 bits `0x435258454c494731`, least-significant 64 bits the positive counter. No hash, truncation or UUIDv4/global-uniqueness claim is made; the complete stamp includes User UUID. Changing this encoding requires explicit compatibility review. Generation/elevation derived from an uncommitted transition must not be released as durable current authority.

Supported staff non-reuse depends on retained immutable grant identities/recipients, including REVOKED grants, which prevent deletion/recreation of their User through supported runtime operations. This does not introduce universal User deletion policy or a tombstone for every account. Privileged database changes and restoration are outside the guarantee and require separate session/activation controls.

For deterministic I1, the narrow time model uses verified MFA-event time `mfaAt`, trusted elevation-binding time `boundAt`, last admitted privileged-activity time `lastActivityAt`, and one explicit evaluation time `now`. Require `mfaAt <= boundAt <= lastActivityAt <= now`, with the binding established for the full current actor/account-eligibility/session/grant stamp. A new elevation initializes last activity at binding. Absolute assurance age is measured from the actual MFA event, not its later parsing or DTO creation: `0 <= now - mfaAt < 10 minutes`. Idle age must satisfy `0 <= now - lastActivityAt < 5 minutes`. Both limits must pass. Equality at either upper bound is denied. Clock rollback/inconsistent ordering, absent fields or arithmetic overflow are denied.

Each critical INVITE/CHANGE/RESUME/SUSPEND/REVOKE additionally requires an exact trusted verified step-up event for that same full actor/account-eligibility/session/grant stamp and suitable critical-staff purpose, with `mfaAt <= stepUpAt <= now` and `0 <= now - stepUpAt < 5 minutes`. Step-up does not excuse stale absolute/idle assurance, grant expiry, or target denial. It does not automatically restart the absolute clock. A fresh elevation requires a newly established verified context, not reinterpretation of old evidence. No unsupported ordering against local HTTP-session creation is inferred: provider authentication may precede that session's establishment.

The adapter evaluates idle eligibility using the prior admitted activity; the request cannot refresh its own expired idle window before checking. I1 neither updates activity nor verifies claims/factors nor issues assurance. Its typed trust assertions are contract inputs from an eventual trusted adapter, not externally accepted credentials. The event identity/stamp is distinct from any provider secret. The 10/5/5 limits are selected project-model bounds, not proof that a real Auth0 tenant provides them or that Creastrix meets an external assurance certification.

### 8. Current admission and durable security audit

Future ordinary writes require final current User/grant/resource/assurance predicates and relevant conflicting locks, then mutation, required success audit, and applicable one-use consumption in one physical READ COMMITTED transaction. Revoke/suspend-first denies later admission. An operation admitted first under the agreed held locks may commit before a waiting revocation completes; no cancellation of an already committed operation is promised. Use bounded waits and no remote I/O under locks; preserve existing foundation protocols, not a new global lock or global deadlock-freedom claim.

Reads require final recheck after data lookup and before choosing disclosure, pinned to the same actor/grant/session stamp. Mandatory `READ_ADMITTED` must succeed before disclosure. It records admission, not network delivery or human reading. Audit READ/EXPORT itself is recorded without recursive audit-of-writer loops. An admitted response cannot be recalled; stale-account UI responses remain a separate protection requirement, not a replacement for server admission.

The bounded security-event catalog covers access invitations/assignments/changes/suspend/revoke; separate bootstrap/recovery/upgrade or future handover events; independently authorized User-security transitions; sensitive grant/user-security/audit read admission; future audit export; confirmed denial/failure; and linked correction/retention events. A historical event kind does not grant its corresponding operation in I1. Allowed event projection contains event/operation identity, server timestamp, actor UUID or exact nonsecret maintenance-authority reference, exact target/resource scope, action, actual outcome, permission/bundle version, finite reason and correlation, and only necessary before/after assignment/status fields. Missing inapplicable fields are not invented. No raw claims, provider subject, credentials, tokens/cookies, arbitrary justification/body, personal messages, or financial/customer payloads are exposed or logged.

Critical mutation and required successful audit commit atomically; audit failure prevents commit. Technical logs, after-commit callbacks and best-effort sinks cannot replace it. Required read-audit failure prevents disclosure. Denied or confirmed failed mutations do not produce success; bounded denial/failure persistence is a separate path after rollback, not atomic with an absent mutation. Its failure preserves deny/unavailable and bounded operational diagnostics, not permission to act.

Unknown commit remains UNKNOWN until the same stable operation identity is reconciled against durable outcome under current disclosure authorization. Do not infer rollback, blindly resubmit under a new identity, or equate a desired current grant state with that operation's completion. Authorized terminal replay returns only its historical result, never revives authority. Retention of consumed/outcome records must not re-enable an old authorization; replay-safety horizon/tombstones/expiry need later proof.

Security audit is an obligation of access workflows, not a new broad Audit Log entity. Staff and owner cannot edit/delete history; corrections append linked events. Finite retention, holds, archive/access and separately protected purge policy remain explicit operational decisions, not infinite retention or an owner erase button. Runtime append-only behavior is not tamper-proofness against a privileged database operator.

I1 has no storage, audit side effects, or `auditSucceeded` input falsely proving a physical commit. Technical logging belongs later at the responsible application/HTTP boundary, not noisy pure policy code. Code contracts/comments, safe reason codes and exception messages must be English, bounded, meaningful and free of raw payloads; no pointless per-line comments or repeated logs in every layer.

#### 8.1 Bounded owned transaction and release contract

The bounded storage workflow rejects ambient transactions, active synchronizations and a datasource-bound connection before acquiring its own connection. Registration, execution and receipt admission each own an explicit physical READ COMMITTED transaction; no hidden join, suspension or REQUIRES_NEW is used. All required resource locks remain held through transaction outcome. Admission uses current persisted facts and refreshed time after waits, not a cached pre-wait decision.

An ambient rejection starts no SQL, worker, owned/fallback audit phase or foreign-transaction completion observer. It returns its fixed typed failure without sink I/O under potentially foreign locks. This does not claim cleanup of another workflow's transaction; its own responsible boundary may diagnose after its cleanup.

Required read audit and the immutable admission outcome must commit successfully before a new user-security read payload leaves the outer service boundary. Historical receipt disclosure commits its own READ_ADMITTED audit, not a second execution outcome, before that receipt is released. Returning an inner callback is not commit. Audit failure, rollback or uncertain commit prevents disclosure. An admitted response cannot be recalled; this does not promise continuously rechecking authority until network delivery.

#### 8.2 Closed intent registration and execution

A bounded operation is identified by `(initiator User UUID, operation UUID)`. Before explicit execution, an independently committed immutable registration binds that identity to one versioned closed intent: exact read target/reason, or exact revoke target grant/recipient/expected revision. Registration creates no grant or permission and never schedules execution. It requires current target authority and required step-up; execution independently revalidates current authority. Only the original initiator executes the intent. Prior to durable registration, preserving unseen retry payload is a calling-workflow duty, not an invented platform guarantee.

Registration identity derives from the trusted current actor; the caller cannot register under another User. A new intent and its INTENT_RECORDED audit commit atomically. New and full-tuple-matching repeated registrations return REGISTRATION_ACKNOWLEDGED without a created/replayed flag or execution result. Matching retained binding is acknowledged only after current authorization and a fresh READ_ADMITTED receipt audit commit, never a duplicate INTENT_RECORDED event. For REVOKE registration this receipt also requires STAFF_GRANTS_READ/ACCESS_REVIEW without dropping owner/step-up requirements. Registration is not an authorization lease; no registration stamp is reused as fresh execution assurance.

After current caller-supplied-target authorization, the original authenticated initiator may learn only that its own operation ID is unavailable for this registration: ID_UNAVAILABLE when a different committed intent occupies the key. No retained target, fingerprint/payload, registration details or execution outcome of the mismatched intent is disclosed. Binding/key/namespace stay unchanged. This registration-only availability exception is deliberate; no target-specific history-existence or timing noninterference guarantee is asserted for it. Contention/unknown commit is not definite ID_UNAVAILABLE. The result neither proves prior rollback nor permits automatic fresh-ID or changed-intent retry.

Execution has no such availability exception: after current supplied-target authority, absent and nonmatching intents return the same NO_EXECUTABLE_INTENT without stored-history fields. Foreign-initiator execution is prohibited without history lookup. Only an authorized fully matching intent can execute; its terminal outcome never reruns mutation. Only such a match under current execution authority may return TERMINAL_RECEIPT_REQUIRED when a terminal outcome exists; target-policy denial cannot be bypassed to inspect that outcome. A successful revoke, mandatory audit and immutable outcome commit atomically. A read admission, audit and outcome likewise commit together without storing the user-status payload. No generic REGISTERED/APPLIED/REJECTED command state machine is introduced.

A terminal outcome is historical and cannot reapply the operation. An absent outcome, even after acquiring its intent lock, is not terminal rollback: an original attempt may still be pending before that lock. Semantic absence of a disclosable receipt is normalized as specified below; technical lock timeout or uncertain commit remains unavailable/UNKNOWN. A confirmed rollback describes that attempt only; a retained intent may be explicitly retried under fresh authority. No automatic retry under a fresh ID, expiry or identity recycling is permitted by this slice.

#### 8.3 Current authorization for historical receipts

A REVOKE history receipt requires current owner authority, current verified assurance, `STAFF_GRANTS_READ / ACCESS_REVIEW` for the caller-specified exact target grant/recipient, and then an exact match to operation identity and stored intent. A different currently authorized owner may read this minimal history but cannot execute the original initiator's intent. Revoked/expired target metadata or an inactive recipient does not itself forbid an otherwise authorized historical read. No operation existence, mismatch or outcome is disclosed before current target authorization. The minimal receipt contains original actor, operation/target, registered/admitted time, terminal kind and exact before/after state/revision, not session evidence or unrelated metadata.

A USER_SECURITY_READ receipt requires the original initiator and current `USER_SECURITY_READ` authority/reason/scope for the original exact target. It confirms prior admission only and contains neither old nor current accountStatus. Fresh current data requires a new explicitly initiated read and new admission/audit. The current receipt-disclosure purpose is recorded separately without changing the original intent. Each receipt disclosure itself requires durable READ_ADMITTED before release; internal audit writing does not recursively audit itself.

After current supplied-target authorization, absent intent, wrong-target or otherwise nonmatching intent, missing/disallowed historical initiator, absent outcome and every other semantic absence of a disclosable matching receipt return exactly NO_DISCLOSABLE_RECEIPT, one fixed text and no history fields. This does not establish absence, rollback, ID availability or permission to reexecute. READ's other-initiator request is normalized without reading that history. A historical initiator's inactive status alone does not deny a currently authorized owner's exact REVOKE receipt; no additional User-security permission on that initiator is introduced. Technical database/lock/audit failure may remain unavailable/UNKNOWN, never a missing-record proof. Only genuine authorized exact matching history with acknowledged receipt-audit commit is disclosed. Closed internal audit reasons may differ; they are not outward reasons and cannot leak through caller-visible causes or auxiliary fields. No timing noninterference is promised and the own-ID registration exception does not extend to receipts.

#### 8.4 Bounded audit and retention requirements

The unwired slice retains immutable intents, outcomes and access audit without purge/recycling. This is a temporary technical boundary, not infinite operational retention or a legal retention period. Before live activation, retention/holds/purge/access and replay-safe identity retirement must be separately accepted and verified. Later policy changes do not reinterpret historical results or permit stale IDs to execute again. Unsupported intent/outcome versions fail closed.

The bounded workflow's required audit event set is INTENT_RECORDED, READ_ADMITTED, GRANT_REVOKED, ACCESS_DENIED and OPERATION_FAILED with finite versioned fields; this is a required subset of the wider event catalog, not a claim that those workflows are implemented. Mutation success/audit/outcome are atomic. Denial or confirmed attempt failure is recorded through a separate bounded path after rollback; failure of that path preserves deny/unavailable. UNKNOWN is not recorded as a confirmed failed terminal outcome. Owners/staff have no history update/delete operation; future corrections append linked records under separate authorization. Append-only runtime behavior is not tamper-proofness against a database owner.

Audit fields use closed event shapes and bounded identities, revisions, times, enum values and correlation, not arbitrary map/JSON/body/text fields. A requested unverified target UUID is distinguished from verified target references and is not enriched through unauthorized lookup. Successful revoke alone carries before/after state and revision; such fields are not invented for denials. Neither credentials/session evidence nor raw claims, provider subjects, email/names, arbitrary justification, SQL detail or Throwable output belong in audit records. Mandatory audit success is a physical commit requirement, not an externally supplied boolean.

### 9. Finite exceptional maintenance requirements

The following are separate future operation-bound workflows, not I1 permissions, ordinary owner self-service, or a generic `maintenance=true` bypass:

| Workflow | Bounded effect and non-bypass requirements |
|---|---|
| FIRST_OWNER_BOOTSTRAP | Exactly one successful bootstrap for the environment, to a preapproved existing ACTIVE User/exact committed binding and exact bundle. One-use proof, consumption, grant and audit are atomic; no first-registrant/email/provider-role inference or startup replay. Respect the single grant slot. |
| CONTAIN_ADMIN_INGRESS | Close-only containment under a separately authorized runbook. It creates no grant or DB/domain mutation. If DB unavailable, independent operational evidence is not called atomic DB audit; no automatic reopening. |
| RECOVERY_DISABLE_OWNER | Exact grant revoke and/or explicitly authorized ACTIVE → SUSPENDED User action, without target-session consent. No issuance; mandatory DB mutation audit. |
| RECOVERY_REINSTATE_USER | Explicit SUSPENDED → ACTIVE for the same existing identity only after an established recovery basis and confirmation that the cause of suspension has been resolved; not DEACTIVATED → ACTIVE and not revival of revoked grants/elevation. |
| RECOVERY_REPLACE_OWNER_GRANT | New exact assignment to the same prenominated existing ACTIVE User/binding. Any old non-REVOKED grant must be revoked through explicit authorized action before replacement; no slot bypass, historical resurrection, arbitrary new recipient or rebinding. |
| OWNER_BUNDLE_UPGRADE | Same ACTIVE User/binding/grant with exact expected revision and independently reviewed old-to-new catalog/bundle versions and digests. No identity/scope/expiry change, bootstrap reuse, handover or deployment-triggered expansion. |

The maintenance trust anchor is separate from app grants, statuses and browser principal. A human User UUID is provenance, not its authority source: owner-driven suspension/deactivation of that app User or grant revocation cannot disable an otherwise valid exact emergency proof. This does not enable ordinary actions by inactive Users. The anchor must itself be revocable via separately protected procedures; it is not permanently unrevocable.

Every exceptional maintenance workflow requires separately approved, verified assurance no weaker than the assurance accepted for critical admin operations, proof of the required factors, and an exact operation-bound proof. Before live activation, the concrete maintenance verification mechanism and runbook require a separately authorized verification gate; an unselected, unverified or unavailable mechanism cannot activate the workflow. Root or SSH access, an approved flag, or possession of one recovery file is not by itself sufficient authority or MFA proof. This assurance floor does not import ordinary app User ACTIVE status, an ordinary grant, or ordinary session elevation as prerequisites of independently authorized emergency authority.

Each DB-affecting operation requires exact action/operation ID, environment, existing target/binding, action-specific expected prior state and allowed effect, bounded expiry, authorized executor and anchor/version, verified assurance, fresh locally authoritative revocation/version/expiry checks and atomic consume/mutation/audit. Expected prior state means an unconsumed bootstrap and empty grant slot for bootstrap, exact grant/state/revision for grant mutation, or exact User and expected status for a User transition; do not require a fictitious existing grant or invent a User revision. Failure/unavailable proof or audit fails closed. No arbitrary SQL, recipient or capability input is accepted as authority. Lock/wait bounds, exact PID/transaction proofs and replay retention belong to the separate implementation gate, not this prose.

Owner-bundle upgrade additionally requires the exact independent catalog review and explicit old-to-new owner decision; digests cover permissions and constraints. The ordinary browser session cannot issue its own proof. ACTIVE unexpired/unrevoked current target/grant and supported new catalog are mandatory. Serialize upgrade with User suspension, grant/authorization revocation and other upgrades. Rollback changes/consumes nothing successfully; commit-unknown reconciles the same operation; replay after revoke cannot restore authority. Grant identity, binding, scope and expiry stay fixed. Old elevation cannot use the new revision, and deployment does not perform upgrade.

Solo-first permits one person with distinct, proven technical authority, not fictional dual-human approval from two accounts, AI, or two windows. It does not guarantee recovery if that person is unavailable, both channels are lost/compromised, or a malicious owner controls both channels/root. An unavailable safe recovery path may mean closed access/containment. Real anchor, factors, provider flow, enrollment/revoke/rotation, verifier/executor and runbooks are not selected or proven by this document. Handover to another User remains deferred, not hidden in replacement/upgrade.

## Invariants

- Each grant has stable identity and one immutable existing recipient; committed identity bindings are not rewritten.
- At most one non-REVOKED grant occupies a User's slot; expiry, suspension and inactive User status do not release it. No grant union exists.
- Catalog/bundle v1 and role compositions are finite and immutable. Unknown/future capabilities deny; deploy/startup never grants them.
- Ordinary authority requires ACTIVE User, current valid ACTIVE grant, exact scope/projection, finite justification where required and bound assurance. None substitutes for the others.
- Ordinary mutation is owner-to-other-User SUPPORT_READ only. Self/owner targets, transitive delegation and implicit escalation deny.
- Expired grants cannot be renewed/resumed/changed; revocation is terminal and a new assignment has new identity.
- Committed grant authorization changes advance revision monotonically; old elevation is invalid, and denied/no-op/rolled-back operations do not mutate it. Separately, account-status security transitions invalidate eligibility generation/elevation without changing grant state or revision.
- Platform authority does not change Membership, Workspace voluntariness, seller/MoR, commercial entitlement or other accepted domain rules.
- Required success audit is atomic with its critical mutation; required read audit precedes disclosure. UNKNOWN is not fabricated success or rollback.
- Maintenance authority is not ordinary policy and does not confer ordinary rights on inactive Users or bypass immutable identities.
- Specification approval and pure-model ALLOW neither prove runtime enforcement nor activate any real workflow.

## Notes

Repository location: `docs/domain/platform-access-grant.md`; relative links resolve from this location. Status APPROVED 1.1 records separate independent specification review and explicit acceptance of this version, not implementation coverage, real privileges or rollout. APPROVED 1.0 remains its accepted lineage.

Current source coverage in this checkout comprises the unchanged I1 pure core plus bounded I2A storage/generation and constructor-only registration, READ, REVOKE and receipt workflows. I1 retains six domain classes and two pure-policy test classes; its immutable supplied facts and ALLOW still do not prove current authority or commit. See the [backend coverage guide](../../backend/README.md#platform-access-policy-foundation) for the technical boundaries. Source coverage, independent verification, acceptance and integration are separate facts; this description does not establish transfer into main, fresh CI, real authentication or full delivery of this specification.

The accepted contract includes the finite resource/reason matrix, CHANGE versus lifecycle no-op handling, invitation issuer-revision binding, revision arithmetic, 30 × 24-hour duration, and MFA-event-based absolute/ordered time model defined above. Specification approval does not itself constitute runtime uniqueness/transaction proof, provider verification, invitation redemption assurance, retention-policy approval or operational runbook proof. Implemented storage/workflow behavior must be assessed from exact source and separate verification evidence; unimplemented live workflows remain separately gated.

The [authentication pilot](../authentication-pilot.md) remains a separate boundary. `AUTH-COOKIE-FOLLOWUP-001` is OPEN; browser proof remains INCOMPLETE and S003 candidate browser scenarios NOT RUN. Old disposable GO_S is not an integrated fix or authorization to transfer it onto the observability baseline. Real Auth0/MFA, React/browser authentication and operational authority remain unproven. No live activation or rollout is authorized.

APPROVED 1.0 lineage is preserved: the earlier R1 proposal passed narrow independent re-review, PA-REV-001 was resolved in that proposal, and PA-D1-001/PA-D1-002 were remediated in DRAFT 0.3 before independent specification review and explicit acceptance of 1.0. The original FAIL, D1-PREP and D1-CONTRACT independent FAIL remain unchanged historical evidence. The later I2A-R1 independent review resolved PA-I2A-001/002/003 at revised-proposal level only; coordinator acceptance of that technical direction does not automatically approve this 1.1 candidate or establish runtime behavior.

### 1.1 implementation boundaries and technical notes

V12 supplies the bounded grant, exact-target, intent, outcome and access-workflow audit storage, plus the positive per-User eligibility counter and typed-generation adapter. Existing status transitions atomically advance that counter; direct counter UPDATE is rejected, while insertion/backfill starts at 1. Immediate uniqueness retains the non-REVOKED slot despite expiry, suspension or inactive recipient status. These structural guards are not actor authorization for arbitrary SQL, a universal reverse success-audit-to-outcome guard or database-owner tamper protection.

This partial storage slice freezes scope/expiry after initial creation and exposes only REVOKE as an application mutation. Accepted CHANGE/SUSPEND/RESUME rules remain unchanged; their execution is deferred, not forbidden by a new business rule. Synthetic structural transitions are fixtures, not production authority. Future CHANGE requires a separately designed storage protocol and migration.

Named registration independently binds the complete immutable intent without executing it or leasing authority. READ returns only the exact User UUID/status; REVOKE commits the expected-revision mutation, required success audit and outcome together. Exact duplicate registration and separate receipts require fresh current admission and READ_ADMITTED audit. READ receipts stay original-initiator-only without account-status history; exact REVOKE receipts require current owner grant-read authority, not continued activity of the prior actor/recipient. Collision and nondisclosure rules remain those in section 8; there is no automatic new-ID retry.

Owned physical READ COMMITTED transactions reject ambient transaction/synchronization/resource state before foreign work. Ordered current authority locks, fresh trusted facts and a separate database clock sample after completed waits/target lookup, repeated after intent/unique-slot waits, establish the late final gate without refreshing expired idle assurance. Required audit/outcome precedes acknowledged commit and disclosure; unknown commit or unfinished cleanup releases no payload and is not inferred rollback. Primary/fallback budgets and bounded cleanup are technical implementation limits, not hard-real-time or network-delivery guarantees.

The JDBC persisted-time boundary rejects submicrosecond/unrepresentable values and accepts only the finite agreed database/Java interval; it does not round or clamp. I1's exact Instant semantics and synthetic assurance nanoseconds remain unchanged. Raw SQL cannot recover precision discarded during input conversion. Runtime validation evidence belongs to the separately verified implementation, not this specification status.

Durable access-workflow events are INTENT_RECORDED for registration, READ_ADMITTED for user-security reads, duplicate-registration acknowledgement and receipt admission, GRANT_REVOKED for the atomic mutation, and bounded ACCESS_DENIED/OPERATION_FAILED attempts after confirmed owned rollback. No fallback follows ambient rejection or UNKNOWN. Admission is not delivery or human reading; technical logs are not that audit, and this is not full Audit Log delivery.

Diagnostics now include the narrow existing-Reason overload: for an actually attempted owned phase with completed resource/lock cleanup, the boundary attempts one observation with truthful completion and the maximum of legacy completion and classified-reason severity. Unfinished cleanup does not guarantee an observation, and sink delivery is not guaranteed. Internal causes are retained/classified within those attempted phases, not promised for every pre-phase failure. Multiple failures select the highest-severity reason, first on a tie; fallback is a separate phase. Acknowledged commit remains committed despite later cleanup failure; unacknowledged commit remains UNKNOWN. Legacy two-argument formatting, HTTP FIFO/shared cap 16/coalescing and sink-failure safety remain intact. No arbitrary Throwable/message/SQL/entity-ID logging, new Reason/Event catalog or foreign completion observer is added.

No new User version/lifecycle, permission, role, universal owner business override, tenant recovery or operational authority follows from this coverage. Audit Log remains PLANNED. There is no Spring/HTTP staff wiring or live provider/session/MFA/step-up adapter; issuance/invitations, CHANGE/SUSPEND/RESUME execution, maintenance and UI remain deferred. Independent maintenance assurance and cause-resolved same-identity recovery in section 9 are unchanged. Independent workflow review, review of these coverage Notes and separately authorized exact transfer/integration remain gates; source corrections may require a narrow follow-up. No next implementation slice or live activation is selected.

---

Status: APPROVED

Version: 1.1
