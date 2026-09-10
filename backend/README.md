# Creastrix Backend

Backend of the Creastrix platform. Domain specifications under `docs/domain`
remain the source of truth for business rules.

## Implemented domain foundation

- User stable UUID platform identity;
- User account and access status lifecycle (`ACTIVE`, `SUSPENDED`,
  `DEACTIVATED`), enforced both in the Java domain layer and in PostgreSQL;
- mandatory one-to-one User Profile persistence, created atomically with its
  User;
- Organization stable UUID platform identity;
- Organization Membership between a User and an Organization (role `OWNER`,
  status `ACTIVE`), with the initial `ACTIVE` `OWNER` Membership created
  atomically with its Organization;
- structural last-owner invariant: a committed Organization always has at
  least one `ACTIVE` `OWNER` Organization Membership, enforced in PostgreSQL
  with deferred constraint triggers;
- PostgreSQL concurrency protection for the last-owner invariant: every
  invariant-changing Membership check is serialized through a `FOR UPDATE`
  lock on the parent Organization row, which prevents the two-owner
  write-skew race under the V9 READ COMMITTED write contract below;
- Workspace stable UUID platform identity with exactly one immutable owner
  that is either one User or one Organization;
- User-owned and Organization-owned Workspace creation, each atomic with the
  creator's initial `ACTIVE` `ADMIN` Workspace Membership;
- Workspace Membership between a User and a Workspace (roles `ADMIN`,
  `EDITOR`, `VIEWER`; statuses `INVITED`, `ACTIVE`, `SUSPENDED`) with explicit
  permission-scope grant persistence (`PROJECTS`, `READY_MADE_PRODUCTS`,
  `LISTINGS`);
- Workspace-layer read/write scope capability semantics in the domain model
  (ADMIN covers all current scopes by role; EDITOR/VIEWER only through
  explicit grants; VIEWER never writes; only ACTIVE User + ACTIVE Membership
  produce ordinary access); a positive result does not bypass stricter rules
  of concrete domain operations;
- structural Workspace invariants enforced in PostgreSQL with deferred
  constraint triggers: immutable ownership, no deletion/TRUNCATE, at least one
  `ACTIVE` `ADMIN`, the User owner's permanent `ACTIVE` `ADMIN` Membership,
  and the Organization `OWNER`/Workspace `ADMIN` same-User intersection,
  enforced bidirectionally from both Membership tables;
- PostgreSQL concurrency protection for the Workspace invariants: a scoped
  row-lock protocol (Organization row, then affected Workspace rows in UUID
  order, then User rows) under that READ COMMITTED contract, closing the tested races:
  concurrent removal of the last `ACTIVE` `ADMIN` alternatives, cross-table
  Organization `OWNER` / Workspace `ADMIN` write skew, Workspace creation
  versus removal of the creator's Organization `OWNER` Membership, and
  creation-time `ACTIVE` User validation versus a concurrent User status
  change;
- Ready-Made Product structural foundation (partial coverage of the approved
  Ready-Made Product specification): the stable UUID identity, exactly one
  immutable Workspace, exactly one immutable Created By User, the `ACTIVE` /
  `ARCHIVED` lifecycle state as a stored value, and the non-negative simple
  available quantity (zero allowed);
- safe Ready-Made Product creation in one transaction: the creator must exist
  and be `ACTIVE` (`SUSPENDED` and `DEACTIVATED` are rejected separately), the
  Workspace must exist, and the creator must have effective Workspace-layer
  write authorization for the `READY_MADE_PRODUCTS` scope through the existing
  Workspace Membership semantics (`ADMIN` by role without stored grants,
  `EDITOR` only with an explicit grant, `VIEWER` never, `INVITED` and
  `SUSPENDED` Memberships never);
- the initial `ACTIVE` lifecycle state is established by the application
  service, never by the caller, and creating a Ready-Made Product creates no
  Listing;
- structural Ready-Made Product invariants enforced independently in
  PostgreSQL: `RESTRICT` foreign keys to `workspaces` and `users`, the closed
  `ACTIVE`/`ARCHIVED` check set, the non-negative quantity check, immutable
  identity, Workspace, and Created By, and unconditional rejection of `DELETE`
  and `TRUNCATE` regardless of lifecycle state, stock, or commercial
  references;
- a two-phase PostgreSQL creation gate that also protects a direct SQL `INSERT`
  bypassing the application service: initial state `ACTIVE` only, an existing
  `ACTIVE` Created By User at the linearized creation point, an `ACTIVE`
  Membership, and effective `READY_MADE_PRODUCTS` write authority (other scopes
  never substitute for it), rejected with SQLSTATE `23514`;
- PostgreSQL race enforcement for Ready-Made Product creation, implemented as
  an explicit two-phase lock protocol (see the lock note below), which closes
  the tested races where an authorization change (User `ACTIVE` →
  `SUSPENDED`/`DEACTIVATED`, revocation of the `READY_MADE_PRODUCTS` grant,
  Membership suspension, Membership role downgrade to `VIEWER`, or Membership
  deletion) commits first and the stale creation must fail with SQLSTATE
  `23514` and no surviving product;
- creation authorization is a creation-time gate only: a later creator
  suspension or loss of Membership or scope never deletes, rewrites, or
  invalidates an already created historical Ready-Made Product or its Created
  By;
- bounded Ready-Made Product lifecycle operations for `ACTIVE → ARCHIVED` and
  `ARCHIVED → ACTIVE`, with actor authorization revalidated under row locks,
  Product-row serialization, expected-state updates, typed application
  outcomes, and structural PostgreSQL rejection of same-state or unsupported
  transitions with SQLSTATE `23514`;
- two explicit transactional operations for manual quantity deltas: durable
  registration first, followed by a separately invoked apply/retry/replay;
- Product-local command identity `(Product ID, Command ID)`, immutable non-zero
  signed `BIGINT` delta binding, and permanent `REGISTERED`, `APPLIED`, or
  `REJECTED` records;
- exactly-once available-quantity changes under the Product row lock, checked
  signed 64-bit arithmetic, immutable replay, and the closed terminal rejection
  reasons `UNDERFLOW` and `OVERFLOW`;
- V8 structural command protection through a composite primary key, a
  `RESTRICT` Product foreign key, closed outcome-shape checks, guarded state
  transitions, immutable bindings and terminal rows, and rejected deletion or
  truncation. V8 adds no global Command-ID lookup, timestamp, actor, audit, JSON,
  expiry, cleanup lifecycle, or speculative secondary index.

The Ready-Made Product implementation remains deliberately partial. Structural
creation, lifecycle transitions, and the manual available-quantity command
protocol are present. Order Item allocation facts, release, dispatch, and their
future contribution to accounted physical quantity are not present and remain
listed below as deferred behavior.

### Foundation write isolation contract and V9 upgrade

V9 admits writes to `public.organizations`, `public.organization_memberships`,
`public.workspaces`, and `public.workspace_memberships` only when the actual
transaction isolation is `read committed`. A shared PostgreSQL function and one
non-deferred `BEFORE STATEMENT` trigger per table cover `INSERT`, `UPDATE`,
`DELETE`, and `TRUNCATE`, including zero-row and multi-row statements. Prepared
statements, UPSERT, COPY FROM, and MERGE use the same admission boundary.

READ UNCOMMITTED, REPEATABLE READ, and SERIALIZABLE are rejected with SQLSTATE
`0A000` and message
`CREASTRIX_FOUNDATION_WRITE_ISOLATION_V1: foundation write requires READ COMMITTED`.
The server error carries the exact schema/table and DETAIL fields `operation`
and `actual_isolation`, without row data. PostgreSQL may reject an invalid
statement before any trigger runs; a native error with the same SQLSTATE is not
evidence that this guard executed. READ UNCOMMITTED is deliberately unsupported
even though PostgreSQL gives it READ COMMITTED visibility semantics.

Existing REQUIRED Organization/Workspace service calls join an ambient
transaction: a forbidden ambient mode fails at its first guarded write. Neither
the guard nor the services change isolation, add REQUIRES_NEW, or retry. Direct
unpooled JDBC can recover to a savepoint, but another guarded write in that
transaction is still rejected. The current Hikari policy treats SQLSTATE
`0A000` as a broken connection and closes it. In the verified Spring ambient
transaction path, rollback then raises a different `TransactionSystemException`;
`getApplicationException()` retains the original service exception. A generic
rollback error or `Connection is closed` is not guard evidence. The failed
transaction's writes are rolled back and a subsequent new READ COMMITTED
service transaction can commit through the pool; continuation of the same
pooled transaction via savepoint is not guaranteed. No SQLExceptionOverride or
SQLSTATE change is included. Read-only queries are not prohibited. User, User Profile,
scope-grant, and Ready-Made Product tables receive no new isolation guard, and
their existing protections remain. Structural OWNER/ADMIN qualification still
counts ACTIVE Memberships even when the associated User is SUSPENDED; ordinary
actor actionability remains a separate requirement. This is not protection
against a privileged schema owner disabling or replacing triggers.

V9 first verifies actual READ COMMITTED, then takes four separate top-level
`SHARE ROW EXCLUSIVE` table locks in this order: organizations →
organization_memberships → workspaces → workspace_memberships. On the same
physical Flyway connection and transaction, a separate command after the
complete barrier validates the permanent OWNER, User-owner ADMIN, generic ADMIN,
and Organization OWNER/Workspace ADMIN intersection predicates from a fresh
snapshot. Only then are the new function and triggers created. Validation, DDL,
and successful migration history commit atomically. Invalid data, an incomplete
transaction barrier, unexpected table descendants, or a timeout stops the upgrade without repairing data or
terminating other sessions. V1–V8 and their row-lock protocols are unchanged.

This boundary requires `spring.flyway.postgresql.transactional-lock=false`,
with `spring.flyway.execute-in-transaction=true` and `spring.flyway.group=false`.
Standalone Flyway must use the equivalent PostgreSQL transactional-lock setting.
Session advisory locking remains enabled: concurrent migrators serialize, and
Flyway explicitly releases the lock after success or rollback. The compatibility
proof covers the installed Flyway execution path and direct PostgreSQL sessions;
it does not establish compatibility with transaction-pooling proxies or replace
deployment-specific verification.

The migration tests use only owned disposable PostgreSQL databases. A real
deployment still requires a separately authorized drain and operational lock/
statement deadlines; no user or deployed database rollout is claimed. V9 fixes
the isolation admission gap (PROD-002), not the separate same-owner
Workspace-creation lock problem addressed by the integrated V10 correction below.

### Workspace creation lock compatibility and V10

V10 replaces only `public.workspaces_require_initial_foundation()` in place.
The owner User, owner Organization, and ordered ADMIN User creation locks use
`FOR NO KEY UPDATE` instead of `FOR UPDATE`. The predicates, exception semantics,
UUID ordering, deferred timing, and subsequent structural validation remain
unchanged. Function OID and the existing trigger binding are preserved. V1–V9
migration files, last OWNER/ADMIN preservation locks, Ready-Made Product lock
protocols, transaction propagation, and the V9 READ COMMITTED-only contract are
unchanged.

The regression reproduces the original V9 `40P01` with two coherent public
creation calls awaiting normal commit, then proves two successful V10 commits
without retry for a shared User owner or Organization with distinct OWNER
actors. Controls include distinct owners, distinct Organizations sharing an
actor, mixed User/Organization creation, and exact PID-directed forced-constraint
waiting. A forced-constraint control alone is not the RED discriminator.

Interaction coverage keeps the real public services and checks both lock orders
against User status changes, Organization OWNER removal, and Ready-Made Product
creation, archive, and activation in an existing Workspace. Existing Workspace
Membership mutation has an independent-completion control. Queued status/OWNER
writers must not prevent already-coherent creators from committing; exact
writer-to-creator waits are observed successively because a PostgreSQL MultiXact
wait need not expose all conflicting members at once. Creation-time rejection
and permanent structural preservation remain strict `23514` outcomes, not
generic exceptions, deadlocks, or timeouts.

Manual-delta interaction uses the actual public `REQUIRES_NEW` entry points.
A test-only wrapping spy invokes the real repository method exactly once,
captures the bound inner transaction PID before lock acquisition, and holds
after the real operation but before return and commit. It proves both directed
wait orders, a distinct suspended outer PID, and inner COMMITTED completion.
Registration, application, authorized retry, and terminal APPLIED/REJECTED
replay are checked against committed command rows and exact quantity; replay
fixtures have a different current quantity from their stored historical result.
Null/wrong PID, unrelated blocked backends, early failures, and absent overlap
cannot satisfy the observer. Worker SQL, pool acquisition, gates, polling,
futures, and cleanup are bounded; tests add no production retry or lock changes.

This is an integrated, bounded lock-compatibility correction, not global deadlock freedom,
support for non-READ-COMMITTED foundation writes, or a claim of production
rollout. PROD-001 is fixed in main within the tested Workspace-creation scope;
contention and arbitrary multi-operation transaction ordering still require
separate consideration. MIN-01 remains an accepted nonblocking coverage limitation:
the defensive early-admission test branch did not execute, while required
queued-writer scenarios were verified.

### Ready-Made Product creation lock protocol

Creation authorization is validated in two phases, and the phase order is the
point of the design:

- phase 1 is a plain `BEFORE INSERT` row trigger. It runs before the row is
  inserted, and therefore before PostgreSQL takes the implicit referential
  integrity `KEY SHARE` row locks on the referenced `workspaces` and `users`
  rows. It acquires explicit `FOR UPDATE` locks in a fixed order — the creator's
  Workspace Membership row, then (for `EDITOR`) the exact
  `READY_MADE_PRODUCTS` scope-grant row, then the creator's User row — and
  validates the authorization it just locked;
- phase 2 is a `DEFERRABLE INITIALLY DEFERRED` `AFTER INSERT` constraint
  trigger. It re-validates the final state at commit and deliberately acquires
  no new row lock, because the phase 1 locks are still held; it therefore adds
  no opposing lock order and still catches a later change made inside the same
  transaction.

The full lock set of a creating transaction is therefore not only Membership →
scope grant → User: the `INSERT` additionally takes implicit foreign-key
`KEY SHARE` locks on the referenced Workspace and User rows, after the explicit
phase 1 locks. This ordering matters because the already integrated V4 deferred
Workspace Membership invariant trigger fires on every Membership `UPDATE` or
`DELETE` at commit and requests the parent Workspace row `FOR UPDATE` while
holding the changed Membership row. A commit-time-only creation check would
hold the implicit Workspace `KEY SHARE` lock first and then wait for the
Membership row, which is a real cycle that PostgreSQL resolves with SQLSTATE
`40P01`; acquiring the authorization locks before the insert removes that cycle.
V1–V6 are unchanged by the lifecycle migration.

Both commit sequences are covered by deterministic tests for the creator User
status axis, the permission-scope axis, and the Membership status, role, and
deletion axes: when the authorization mutation commits first, the stale creation
is rejected with SQLSTATE `23514` and no product survives; when the creation
commits first, the mutation really waits, the creation commits, the mutation
then commits, and the historical product with its immutable Workspace and
Created By is preserved.

This foundation does not claim universal deadlock freedom for arbitrary future
transactions that combine Membership mutations, or Ready-Made Product creation,
across multiple domains or resources. A transaction changing both a Workspace
Membership and an Organization Membership may request these row locks in an
order opposite to another concurrent transaction, and PostgreSQL may then abort
one of them with SQLSTATE `40P01`. The structural invariants remain safe,
because an aborted transaction changes nothing; only availability is affected.
Future Membership mutation workflows must therefore define a canonical
ordering of all affected Organizations and Workspaces and must safely retry the
complete transaction after a deadlock. No such general mutation workflow, and
no retry behavior, is implemented in this slice.

### Ready-Made Product lifecycle lock protocol

The supported application path first reads the Product without a lock only to
obtain its immutable Workspace identity. It then acquires row locks in one
canonical order: Workspace Membership → exact `READY_MADE_PRODUCTS` scope grant
for an ACTIVE `EDITOR` → actor User → Ready-Made Product. Under the Product
lock it re-reads persisted status, requires the exact expected state, performs
an expected-state update, and returns the complete updated Product. Missing
Membership or scope rows cannot be locked and therefore fail closed; this does
not claim serialization against concurrent issuance of a previously absent
grant.

Future Membership/scope mutation workflows must preserve Membership → scope.
A workflow that changes scope first and Membership second would introduce the
opposing scope → Membership order and could deadlock. That general mutation
workflow does not exist yet, global deadlock freedom is not claimed, and retry
is not implemented: on SQLSTATE `40P01`, a future retry policy must repeat the
whole transaction. Future quantity operations must likewise acquire
authorization rows before Product, avoiding Product → Membership against the
current Membership → Product order.

PostgreSQL V7 enforces only the structural transition set. The migration owner
and runtime database role currently coincide, so a client with runtime database
credentials can execute a structurally valid raw transition without proving an
actor. The supported Java/JDBC path proves authorization for the represented
actor identity, but authentication and proven external caller identity remain
absent. Database-role separation is deferred to a dedicated security task.

### Ready-Made Product manual quantity-delta protocol

Registration and application are separate public service calls and therefore
separate commit opportunities. Registration checks the represented actor for
the supplied Product and stores a non-zero delta as `REGISTERED`; it never
changes stock. There is no automatic application, scheduler, or background
retry. A later explicit call rechecks current authorization and either attempts
the registered command or returns its stored terminal result. Actor identity is
not command payload, so any currently authorized actor may apply or replay it.

The supported JDBC path may first read the Product only to resolve its immutable
Workspace. It then locks Membership → the exact `READY_MADE_PRODUCTS` grant
when an ACTIVE `EDITOR` requires one → actor User → Product → the exact command
row or insert conflict. Command existence, binding, state, and outcome are not
read before this authorization boundary succeeds. Missing Product, missing or
inactive actor, and ineffective authorization use the same opaque internal
access failure. Authentication, HTTP mapping, and proven caller identity remain
absent, while privileged raw SQL remains outside actor authorization because
the runtime role is not separated from the migration owner.

For a still-`REGISTERED` command, the locked available quantity is changed with
pre-addition boundary checks: there is no wrapping, clamping, partial result, or
unsafe negation of `Long.MIN_VALUE`. A successful Product update and
`REGISTERED → APPLIED` result commit atomically. Underflow or overflow leaves
the Product unchanged and atomically stores `REGISTERED → REJECTED` with the
reason and observed quantity. Infrastructure failure rolls back the transaction
instead of manufacturing a business rejection. Replays never recalculate or
mutate, and the same Command ID can be used independently for another Product.

No Order Item allocation persistence exists in the current integrated schema,
so reachable outstanding allocated quantity is zero and accounted physical
quantity equals locked available quantity. Any later approved confirmation,
release, or dispatch integration must derive outstanding allocations from real
facts and serialize through this same Product quantity boundary. Neither that
commerce work nor a general whole-transaction retry policy for SQLSTATE `40P01`
is claimed here.

Intentionally not implemented yet:

- authentication and login (no credentials, no OAuth, no MFA);
- concrete User Profile personal fields, which remain unimplemented until the
  approved specification defines them;
- any HTTP API for User, Organization, or Workspace;
- general Organization authorization and delegation;
- Organization recovery;
- Organization invitations;
- additional Organization roles or Membership statuses;
- Workspace or Workspace Membership invitations and invitation acceptance;
- general Workspace Membership creation, removal, suspension, restoration,
  role changes, or scope-grant mutation APIs;
- Workspace ownership transfer, deletion, archival, lifecycle states, or
  personal Workspace recovery;
- additional Workspace roles, statuses, or permission scopes;
- Ready-Made Product allocation, confirmation-time decrement, eligible
  pre-dispatch release, dispatch completion, and integration of those facts
  with accounted physical quantity and manual deltas;
- Order, Order Item, Shipment, Listing, Payment, and every other commerce
  integration (Order Item, Shipment, and Listing specifications remain DRAFT);
- Product Variant, and any name, description, SKU, brand, model, media,
  dimension, weight, or shipping fields;
- Manufacturer and Supplier relationships, Inventory, Stock Movement,
  Reservation, and warehouse entities;
- Ready-Made Product list, search, and paging APIs;
- destructive deletion of a Ready-Made Product.

## Technology baseline

- Java 25 (required)
- Spring Boot 4.1.0
- Maven Wrapper (Apache Maven 3.9.16) — no system Maven required
- PostgreSQL (integration tested against `postgres:18.4-alpine`)
- Spring JDBC with explicit SQL (no JPA, no Hibernate, no ORM)
- Flyway (version managed by Spring Boot 4.1.0)
- Testcontainers (version managed by Spring Boot 4.1.0)

A Docker-compatible runtime is required to run the integration tests, which
start a real PostgreSQL container via Testcontainers.

## PostgreSQL JDBC override

The PostgreSQL JDBC driver is intentionally pinned to `42.7.13`, above the
version currently managed by Spring Boot 4.1.0 (`42.7.11`). This is a
deliberate security/maintenance override defined via the `postgresql.version`
property in `pom.xml`. Do not remove it without review.

## Runtime dependency overrides

The integrated SC-01 update defines four additional explicit properties in
[pom.xml](pom.xml), affecting nine runtime artifacts:

- `tomcat.version`: `11.0.25` — `tomcat-embed-core`, `tomcat-embed-el`,
  and `tomcat-embed-websocket`;
- `jackson-bom.version`: `3.1.6` — `tools.jackson.core:jackson-core`
  and `tools.jackson.core:jackson-databind`;
- `log4j2.version`: `2.25.5` — `log4j-api` and `log4j-to-slf4j`;
- `logback.version`: `1.6.3` — `logback-core` and `logback-classic`.

These are project overrides, not versions supplied automatically by the unchanged
Spring Boot 4.1.0 BOM. The independent compatibility comparison confirmed only
these nine runtime-artifact updates: Boot/Spring and the remaining resolved
coordinates and scopes were unchanged, with no additions or removals. The
existing PostgreSQL JDBC override remains unchanged. See the
[integration and post-merge verification summary](../README.md#integrated-runtime-dependency-update)
for the bounded SC-01 result; compatibility verification and integration do not
constitute rollout to external applications or proof of a vulnerability-free graph.

## Running tests

```
./mvnw clean test
```

The integration tests start a real PostgreSQL container. They verify the Spring
context, execute a real `SELECT 1`, confirm the Flyway migrations were applied,
check that the health infrastructure reports `UP`, prove the User schema
invariants and status lifecycle against real PostgreSQL, and prove the
Organization foundation: atomic Organization creation with its initial `ACTIVE`
`OWNER` Membership, the structural last-owner invariant, the TRUNCATE
safeguard, and a real concurrent two-owner deletion race that must leave
exactly one `ACTIVE` `OWNER` Membership.

They also prove the Workspace foundation: atomic User-owned and
Organization-owned Workspace creation with the initial `ACTIVE` `ADMIN`
Membership, owner immutability, the no-deletion and TRUNCATE safeguards, the
structural ADMIN and Organization `OWNER`/Workspace `ADMIN` intersection
invariants against raw SQL, and four real concurrency scenarios (last ACTIVE
ADMIN, cross-table OWNER/ADMIN write skew, creation versus creator OWNER
removal, and User-owned as well as Organization-owned creation versus a
concurrent User `ACTIVE` → non-`ACTIVE` status change), each of which must
leave the Workspace foundation intact.

They also prove the Ready-Made Product structural foundation: the exact V1 → V10
migration history, the exact schema (columns, types, nullability, absence of
defaults, primary key, `RESTRICT` foreign keys, closed lifecycle check set, and
absence of speculative indexes), the Spring wiring down to real PostgreSQL, a
repository round trip, every positive and negative creation authorization case
through the service and again through direct SQL, the identity, Workspace, and
Created By immutability, the `DELETE` and `TRUNCATE` rejections, and the
deterministic creation races described above (creator status change,
`READY_MADE_PRODUCTS` grant revocation, and Membership suspension, role
downgrade, and deletion) in both commit sequences, where a rejected race must
fail with SQLSTATE `23514` and leave no product, never with `40P01`, `55P03`,
or a timeout. Contention itself is proven from PostgreSQL lock metadata
(`pg_blocking_pids`) with bounded polling instead of timing assumptions.
The lifecycle coverage additionally proves both supported application
directions, the authorization failures, raw SQL structural boundaries, and the
five deterministic lifecycle races: User revocation and exact EDITOR-scope
deletion in both commit orders, plus duplicate same-target Product mutation.
The V8 coverage separately proves exact Product-scoped registration, composite
namespace isolation, authorization before command disclosure, checked boundary
arithmetic, immutable terminal replay, rollback atomicity, and structural
PostgreSQL guards. Deterministic concurrency cases cover registration conflicts,
rollback recovery, duplicate application, limited-stock commands, authorization
revocation, and lifecycle interaction. Where a real wait is part of the claim,
the tests identify both transaction backend PIDs and confirm the blocker through
`pg_blocking_pids`, with bounded latches and no sleeps.

Schema metadata assertions are anchored to the exact relation, constraint, and
trigger-function OIDs of `public.ready_made_products`, and an adversarial test
creates a decoy schema with same-named table, constraints, and functions to
prove that name collisions in another schema neither replace nor disturb the
inspected objects.

The V9 tests separately preserve the V8 REPEATABLE READ OWNER/ADMIN write-skew
counterexamples and READ COMMITTED controls, then exercise forbidden-isolation
admission without partial effects and READ COMMITTED OWNER/ADMIN/cross-table
preservation. They verify exact error provenance with negative controls, the
four-table write-event matrix, actual versus default isolation, ambient service
transactions, savepoint recovery, and public relation/function OID isolation
against same-named decoys. Upgrade coverage includes fresh V1 → V9, populated
V8 → V9 with nine domain tables preserved, invalid-preexisting data, non-RC
migration rejection, and a real Flyway upgrade overlapping an old writer. The
overlap proves actual PID-directed waiting on the partial barrier, fresh
validation after the writer commits, and complete migration rollback. Core
concurrency and overlap cases run three times sequentially with bounded waits;
timeouts or unrelated errors are not accepted as invariant evidence.

V10 upgrade coverage adds fresh V1 → V10, populated V9 → V10, and populated
V8 → V9 → V10. It permits exactly the three creation-lock substitutions in one
function while comparing its OID, trigger binding, all other functions,
triggers, constraints, indexes, columns, domain rows, and command outcomes.
The existing V9 atomicity, advisory-locking, admission, and upgrade cases retain
their explicit V9 targets and assertions.

### Identity and authentication boundary

Authentication and external caller identity proof are not implemented. A
creator or lifecycle actor User identity presented to the application is not a
proven identity of an authenticated HTTP session, so neither the application
service nor PostgreSQL proves who the external caller is. The supported path
only proves that the represented identity holds the required authorization.

## Running the application

Production/default datasource values are read from environment variables. There
are no credential defaults; the application fails fast if they are absent.

Required environment variables:

- `CREASTRIX_DB_URL`
- `CREASTRIX_DB_USERNAME`
- `CREASTRIX_DB_PASSWORD`

```
CREASTRIX_DB_URL=jdbc:postgresql://localhost:5432/creastrix \
CREASTRIX_DB_USERNAME=... \
CREASTRIX_DB_PASSWORD=... \
./mvnw spring-boot:run
```
