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
  write-skew race;
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
  order, then User rows) that closes the specifically tested write-skew races:
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
  By.

The Ready-Made Product implementation is deliberately partial: the approved
Ready-Made Product specification also defines lifecycle transitions,
allocation, pre-dispatch release, and manual quantity adjustment, none of which
is implemented here. Only the structural foundation and safe creation are
delivered; the remaining approved behavior is listed as deferred below.

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
V1–V5 are unchanged.

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
- Ready-Made Product lifecycle transition operations (`ACTIVE` ↔ `ARCHIVED`),
  generic edit/update, and manual quantity deltas with their command identity
  and idempotency persistence;
- Ready-Made Product allocation, confirmation-time decrement, eligible
  pre-dispatch release, and serialization against dispatch;
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

They also prove the Ready-Made Product structural foundation: the exact V1 → V6
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

Schema metadata assertions are anchored to the exact relation, constraint, and
trigger-function OIDs of `public.ready_made_products`, and an adversarial test
creates a decoy schema with same-named table, constraints, and functions to
prove that name collisions in another schema neither replace nor disturb the
inspected objects.

### Identity and authentication boundary

Authentication and external caller identity proof are not implemented. A
creator User identity presented to the application is not a proven identity of
an authenticated HTTP session, so neither the application service nor the
PostgreSQL creation gate proves who the external caller is. Both only prove
that the presented creator identity actually holds the required authorization.

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
