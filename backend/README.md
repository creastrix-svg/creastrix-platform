# Creastrix Backend

The Java backend stores users, organizations, workspaces and ready-made stock in
PostgreSQL. It also supports a local, owner-only Cognito sign-in. A separate,
opt-in [local catalog profile](../docs/local-catalog.md) serves products for
the storefront. It uses its own database and does not handle accounts, cart
writes or checkout.

The [local buyer storefront](../docs/engineering/account-cart-local-run.md)
uses that same catalog and a separate account database. Its synthetic test
buyers can import a guest cart, edit quantities and save personal designs.
The normal application and the Cognito pilot do not enable these writes.

## Running tests

Run commands from `backend/`. Use JDK 25 and a Docker-compatible runtime. The
Maven wrapper supplies Maven; the integration tests start their own PostgreSQL
containers (`postgres:18.4-alpine`). A developer database or Cognito account is
not required to run them.

```sh
./mvnw --version
./mvnw clean test
./mvnw -DskipTests package
```

The last command packages the already tested code; it is not a substitute for
the test run. See the [backend CI workflow](../.github/workflows/backend-ci.yml)
and the [test coverage reference](technical-reference.md#running-tests).

## Running the application

For local development, privately set `CREASTRIX_DB_URL`,
`CREASTRIX_DB_USERNAME` and `CREASTRIX_DB_PASSWORD` for a **dedicated development
PostgreSQL database**. Flyway applies pending migrations at startup. Do not point
this command at the owner's pilot or a production database.

```sh
./mvnw -Dspring-boot.run.arguments=--server.address=127.0.0.1 spring-boot:run
```

With default settings authentication is disabled; visit
[`/actuator/health`](http://127.0.0.1:8080/actuator/health) for the health check.
To work on the shopping UI alone, follow the [frontend guide](../frontend/README.md)
instead. No backend is needed for that preview.

### Connected local authentication pilot

This mode is only for the authorized, single-instance, nonpublic owner pilot.
In addition to its approved nonproduction database, it needs the confidential
Frankfurt Cognito client: `CREASTRIX_AUTH_ISSUER`, `CREASTRIX_AUTH_CLIENT_ID`,
`CREASTRIX_AUTH_CLIENT_SECRET` and `CREASTRIX_AUTH_ALLOWED_SUBJECTS`. Keep all
values out of source control, the frontend, logs and reports. Allow only the
authorized ACTIVE owner's existing issuer/subject pair. The existing-only
default must stay enabled; an empty subject list admits nobody. Provider signup
or email confirmation alone does not create a Creastrix account.

Only for a separately authorized start, with the private environment already supplied:

```sh
./mvnw -Dspring-boot.run.profiles=auth-local spring-boot:run
```

In a separate terminal, run `npm ci` then `npm run dev` from `frontend/` with
its pinned Node and npm. Use `http://localhost:3000` as the browser origin.
The frontend dev proxy connects to `127.0.0.1:8080`; a build preview has no
such proxy. Resolve port conflicts explicitly rather than changing the origin
or killing a process. Startup can perform provider discovery and migrate the
database. Restarting the live pilot, changing admission, or switching identities
requires separate authorization; see the [operator runbook](../docs/authentication-provider-migration.md#contract-and-prerequisites)
and [local cookie result and public-readiness gate](../docs/authentication-pilot.md#open-cookie-follow-up).

## Source map

- [Business modules](src/main/java/com/creastrix/platform): `user`, `organization`,
  `workspace` and `readymadeproduct` group domain types, application services
  and JDBC adapters. [Platform access](src/main/java/com/creastrix/platform/platformaccess)
  is a separate policy and persistence foundation, not a live staff API.
- [Authentication](src/main/java/com/creastrix/platform/authentication) contains
  OIDC and own-account HTTP handling. [Observability](src/main/java/com/creastrix/platform/observability)
  contains bounded application diagnostics, not a general staff audit log.
- [Configuration](src/main/resources/application.yml), [Flyway migrations](src/main/resources/db/migration)
  and [offline operations](src/main/resources/db/operations) have different roles:
  Flyway runs migrations; an offline operator procedure is never an ordinary migration.
- [Backend tests](src/test/java/com/creastrix/platform) mirror business modules
  and include cross-module PostgreSQL integration and migration checks.
- [Domain specifications](../docs/domain) record decisions and their approval
  status. The [technical reference](technical-reference.md) describes the
  implemented subset and its verification history.

## Technical reference by topic

The links below preserve previous README bookmarks.

- <a id="implemented-domain-foundation"></a>[Implemented domain foundation](technical-reference.md#implemented-domain-foundation)
- <a id="foundation-write-isolation-contract-and-v9-upgrade"></a>[Write isolation and V9 upgrades](technical-reference.md#foundation-write-isolation-contract-and-v9-upgrade)
- <a id="workspace-creation-lock-compatibility-and-v10"></a>[Workspace creation and V10 lock compatibility](technical-reference.md#workspace-creation-lock-compatibility-and-v10)
- <a id="ready-made-product-creation-lock-protocol"></a>[Ready-made product creation](technical-reference.md#ready-made-product-creation-lock-protocol),
  <a id="ready-made-product-lifecycle-lock-protocol"></a>[lifecycle](technical-reference.md#ready-made-product-lifecycle-lock-protocol) and
  <a id="ready-made-product-manual-quantity-delta-protocol"></a>[manual stock changes](technical-reference.md#ready-made-product-manual-quantity-delta-protocol)
- <a id="platform-access-policy-foundation"></a>[Platform access coverage](technical-reference.md#platform-access-policy-foundation):
  <a id="pure-policy-and-persisted-authority-facts"></a>[authority](technical-reference.md#pure-policy-and-persisted-authority-facts),
  <a id="named-workflows-and-final-admission"></a>[workflows](technical-reference.md#named-workflows-and-final-admission) and
  <a id="durable-access-workflow-audit-and-remaining-gates"></a>[audit](technical-reference.md#durable-access-workflow-audit-and-remaining-gates)
- <a id="technology-baseline"></a>[Technology baseline](technical-reference.md#technology-baseline),
  <a id="postgresql-jdbc-override"></a>[JDBC driver](technical-reference.md#postgresql-jdbc-override) and
  <a id="runtime-dependency-overrides"></a>[dependency overrides](technical-reference.md#runtime-dependency-overrides)
- <a id="bounded-application-diagnostics"></a>[Diagnostic event meanings](technical-reference.md#bounded-application-diagnostics) and
  <a id="organization-creation-admission"></a>[organization creation admission](technical-reference.md#organization-creation-admission)
- <a id="identity-and-authentication-boundary"></a>[Authentication](technical-reference.md#identity-and-authentication-boundary),
  <a id="s003-cookie-pairing-admission-and-recovery"></a>[cookie pairing](technical-reference.md#s003-cookie-pairing-admission-and-recovery),
  <a id="controlled-provider-migration-candidate-author-wip"></a><a id="integrated-controlled-provider-migration"></a>[controlled provider migration](technical-reference.md#integrated-controlled-provider-migration) and
  <a id="cognito-email-confirmation-recovery-author-wip"></a><a id="integrated-cognito-email-confirmation-recovery"></a>[email-confirmation recovery](technical-reference.md#integrated-cognito-email-confirmation-recovery)
