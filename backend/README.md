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
  change.

This foundation does not claim universal deadlock freedom for arbitrary future
transactions that combine Membership mutations across multiple domains or
resources. A transaction changing both a Workspace Membership and an
Organization Membership may request these row locks in an order opposite to
another concurrent transaction, and PostgreSQL may then abort one of them with
SQLSTATE `40P01`. The structural invariants remain safe, because an aborted
transaction changes nothing; only availability is affected. Future Membership
mutation workflows must therefore define a canonical ordering of all affected
Organizations and Workspaces and must safely retry the complete transaction
after a deadlock. No such general mutation workflow, and no retry behavior, is
implemented in this slice.

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
- additional Workspace roles, statuses, or permission scopes.

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
