# Creastrix Backend

Infrastructure bootstrap for the Creastrix backend. This module contains no
business domain logic yet. Domain specifications under `docs/domain` remain the
source of truth for business rules.

## Technology baseline

- Java 25 (required)
- Spring Boot 4.1.0
- Maven Wrapper (Apache Maven 3.9.16) — no system Maven required
- PostgreSQL (integration tested against `postgres:18.4-alpine`)
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

The integration test starts a real PostgreSQL container, verifies the Spring
context, executes a real `SELECT 1`, confirms the Flyway `V1` bootstrap
migration was applied, and checks that the health infrastructure reports `UP`.

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
