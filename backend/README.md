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
  write-skew race.

Intentionally not implemented yet:

- authentication and login (no credentials, no OAuth, no MFA);
- concrete User Profile personal fields, which remain unimplemented until the
  approved specification defines them;
- any HTTP API for User or Organization;
- general Organization authorization and delegation;
- Workspace and Workspace Membership;
- Organization recovery;
- Organization invitations;
- additional Organization roles or Membership statuses.

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
