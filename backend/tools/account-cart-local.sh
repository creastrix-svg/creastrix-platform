#!/usr/bin/env bash
# Dedicated synthetic account-cart candidate. The explicit shared mode also
# reads one fixed, managed local catalog. No automatic database discovery or cloud access.
set -euo pipefail
umask 077
backend=$(cd "$(dirname "$0")/.." && pwd -P)
state="${XDG_DATA_HOME:-$HOME/.local/share}/creastrix/account-cart-local"
port=55489
database=creastrix_account_cart_local
role=account_cart_local_app
admin=account_cart_local_admin
fail() { printf '%s\n' "$*" >&2; exit 1; }
case "${1:-}" in
  start-db|status|stop-db|compile|server|seed|snapshot|snapshot-json|snapshot-additions|fault-once|fault-twice|server-two-buyers|seed-two-buyers|next-buyer-a|next-buyer-b|snapshot-json-b|setup-shared-catalog|server-shared|server-shared-two-buyers) action=$1 ;;
  *) fail "Usage: bash backend/tools/account-cart-local.sh {start-db|status|stop-db|compile|server|seed|snapshot|snapshot-json|snapshot-additions|fault-once|fault-twice|server-two-buyers|seed-two-buyers|next-buyer-a|next-buyer-b|snapshot-json-b|setup-shared-catalog|server-shared|server-shared-two-buyers}" ;;
esac
[[ $# == 1 ]] || fail 'Unexpected arguments'
if [[ "$action" == compile ]]; then
  cd "$backend"
  exec ./mvnw -B -ntp test-compile org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile=target/local-account-cart.classpath
fi
if [[ -n "${CREASTRIX_PG_BIN:-}" ]]; then
  bin=$CREASTRIX_PG_BIN
elif [[ -x /Applications/Postgres.app/Contents/Versions/latest/bin/postgres ]]; then
  bin=/Applications/Postgres.app/Contents/Versions/latest/bin
elif command -v postgres >/dev/null; then
  bin=$(dirname "$(command -v postgres)")
else
  fail 'Set CREASTRIX_PG_BIN to a local PostgreSQL server bin directory.'
fi
for cmd in postgres initdb pg_ctl psql createdb; do [[ -x "$bin/$cmd" ]] || fail "Missing $cmd"; done
[[ "$state" == /* && "$state" != *$'\n'* ]] || fail 'Invalid state path'
# A custom XDG directory must not make private media reachable through the repo's
# development server or its build outputs. Check before creating even the first folder.
case "/$state/" in */../*|*/./*) fail 'Use an absolute state path without traversal.' ;; esac
ancestor="$state"
while [[ ! -d "$ancestor" ]]; do
  [[ ! -L "$ancestor" && ! -e "$ancestor" ]] || fail 'Invalid state path component.'
  ancestor=$(dirname "$ancestor")
done
ancestor=$(cd "$ancestor" && pwd -P)
project_root=$(cd "$backend/.." && pwd -P)
while :; do
  # Compare device/inode, not path spelling (case-insensitive APFS, aliases).
  [[ ! "$ancestor" -ef "$project_root" ]] || fail 'Private state must be outside the project and its web roots.'
  [[ "$ancestor" != / ]] || break
  ancestor=$(dirname "$ancestor")
done
if [[ "$action" == start-db && ! -e "$state" && ! -L "$state" ]]; then
  mkdir -p "$(dirname "$state")"
  mkdir -m 700 "$state"
  mkdir -m 700 "$state/assets"
  openssl rand -hex 32 > "$state/admin.password"
  openssl rand -hex 32 > "$state/app.password"
  "$bin/initdb" -D "$state/pgdata" -U "$admin" --pwfile="$state/admin.password" \
    --encoding=UTF8 --no-locale --auth=scram-sha-256
  printf '%s\n' 'creastrix-account-cart-local-v1' > "$state/cluster-kind"
fi
[[ -d "$state" && -O "$state" && ! -L "$state" && -f "$state/cluster-kind" &&
   "$(<"$state/cluster-kind")" == creastrix-account-cart-local-v1 ]] || fail "No recognised dedicated cluster: $state"
state=$(cd "$state" && pwd -P)
cluster="$state/pgdata"
[[ -f "$cluster/PG_VERSION" && ! -L "$cluster" && ! -L "$state/assets" &&
   -s "$state/admin.password" && -s "$state/app.password" ]] || fail 'Incomplete state; will not repair or replace it.'
unset PGHOST PGHOSTADDR PGPORT PGDATABASE PGUSER PGOPTIONS PGSERVICE PGSERVICEFILE PGPASSWORD PGPASSFILE
export PGCONNECT_TIMEOUT=3
if [[ "$action" == setup-shared-catalog || "$action" == server-shared || "$action" == server-shared-two-buyers ]]; then
  catalog="$(dirname "$state")/catalog-local"
  [[ -d "$catalog" && ! -L "$catalog" && -O "$catalog" && -n "$(find "$catalog" -prune -type d -perm 0700 -print)" &&
     -f "$catalog/cluster-kind" && "$(<"$catalog/cluster-kind")" == creastrix-local-catalog-v1 ]] || fail 'Missing dedicated private storefront catalog.'
  secret="$state/catalog-lookup.password"
  reader=local_catalog_cart_reader
  check=(-X -Atq -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55483 -d creastrix_local_catalog)
  if [[ "$action" == setup-shared-catalog ]]; then
    for file in "$catalog/admin.password" "$catalog/app.password"; do
      [[ -s "$file" && -f "$file" && ! -L "$file" && -O "$file" &&
         -n "$(find "$file" -prune -type f -perm 0600 -print)" ]] || fail 'Expected owner-only managed catalog setup credentials.'
    done
    admin_q=("$bin/psql" "${check[@]}" -U local_catalog_admin)
    export PGPASSWORD="$(<"$catalog/admin.password")"
    [[ "$("${admin_q[@]}" -c "SELECT current_database()='creastrix_local_catalog' AND current_user='local_catalog_admin' AND to_regprocedure('local_catalog.cart_variants(text[])') IS NOT NULL AND to_regclass('local_catalog.variant_identity') IS NOT NULL")" == t ]] || fail 'Migrate the local storefront catalog through V13 before setup.'
    if [[ ! -e "$secret" && ! -L "$secret" ]]; then
      [[ "$("${admin_q[@]}" -c "SELECT count(*) FROM pg_roles WHERE rolname='local_catalog_cart_reader'")" == 0 ]] || fail 'Pre-existing lookup role has no managed credential; refusing to replace it.'
      (set -C; openssl rand -hex 32 > "$secret")
    fi
  fi
  [[ -f "$secret" && ! -L "$secret" && -O "$secret" && -s "$secret" &&
     -n "$(find "$secret" -prune -type f -perm 0600 -print)" ]] || fail 'Run setup-shared-catalog with a private managed credential first.'
  export CART_CATALOG_LOOKUP_PASSWORD="$(<"$secret")"
  if [[ "$action" == setup-shared-catalog ]]; then
    if [[ "$("${admin_q[@]}" -c "SELECT count(*) FROM pg_roles WHERE rolname='local_catalog_cart_reader'")" == 0 ]]; then
      "${admin_q[@]}" <<'SQL'
\getenv reader_password CART_CATALOG_LOOKUP_PASSWORD
CREATE ROLE local_catalog_cart_reader LOGIN PASSWORD :'reader_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
SQL
    fi
    [[ "$("${admin_q[@]}" -c "SELECT rolcanlogin AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreaterole OR rolcreatedb OR rolreplication) AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid) FROM pg_roles r WHERE rolname='local_catalog_cart_reader'")" == t ]] || fail 'Unexpected role attributes or memberships; refusing to grant lookup.'
    # Confirm the saved credential belongs to the specified identity before issuing grants.
    [[ "$(PGPASSWORD="$CART_CATALOG_LOOKUP_PASSWORD" "$bin/psql" "${check[@]}" -U "$reader" -c "SELECT current_user")" == "$reader" ]] || fail 'Lookup login did not match.'
    PGPASSWORD="$(<"$catalog/app.password")" "$bin/psql" "${check[@]}" -U local_catalog_app <<'SQL'
BEGIN;
GRANT USAGE ON SCHEMA local_catalog TO local_catalog_cart_reader;
GRANT EXECUTE ON FUNCTION local_catalog.cart_variants(text[]) TO local_catalog_cart_reader;
COMMIT;
SQL
    printf 'Local storefront lookup provisioned; no buyer permissions or catalog data changed.\n'
    exit
  fi
else
  unset CART_CATALOG_LOOKUP_PASSWORD
fi
conn=(-h 127.0.0.1 -p "$port")
running() { "$bin/pg_ctl" -D "$cluster" status >/dev/null 2>&1; }
case "$action" in
  status) exec "$bin/pg_ctl" -D "$cluster" status ;;
  stop-db) if running; then "$bin/pg_ctl" -D "$cluster" stop -m fast -w; fi; exit ;;
  start-db)
    if ! running; then
      "$bin/pg_ctl" -D "$cluster" -l "$state/postgres.log" -o "-h 127.0.0.1 -p $port -c unix_socket_directories=''" start -w
    fi
    export PGPASSWORD="$(<"$state/admin.password")"
    root_psql=("$bin/psql" -X -v ON_ERROR_STOP=1 "${conn[@]}" -U "$admin" -d postgres)
    if [[ "$("${root_psql[@]}" -Atc "SELECT count(*) FROM pg_roles WHERE rolname='account_cart_local_app'")" == 0 ]]; then
      CART_LOCAL_DB_PASSWORD="$(<"$state/app.password")" "${root_psql[@]}" <<'SQL'
\getenv secret CART_LOCAL_DB_PASSWORD
CREATE ROLE account_cart_local_app LOGIN PASSWORD :'secret' NOSUPERUSER NOCREATEDB NOCREATEROLE;
SQL
    fi
    if [[ "$("${root_psql[@]}" -Atc "SELECT count(*) FROM pg_database WHERE datname='creastrix_account_cart_local'")" == 0 ]]; then
      "$bin/createdb" "${conn[@]}" -U "$admin" -O "$role" "$database"
    fi
    printf 'Dedicated database: 127.0.0.1:%s/%s. Retained directory: %s\n' "$port" "$database" "$state"; exit ;;
esac
running || fail 'Dedicated PostgreSQL is stopped.'
cpfile="$backend/target/local-account-cart.classpath"
[[ -s "$cpfile" ]] || fail 'Run compile first.'
export CART_LOCAL_DB_PASSWORD="$(<"$state/app.password")"
export CART_LOCAL_ASSET_ROOT="$state/assets"
classpath="$backend/target/test-classes:$backend/target/classes:$(<"$cpfile")"
if [[ "$action" == server || "$action" == server-two-buyers || "$action" == server-shared || "$action" == server-shared-two-buyers ]]; then
  # The JDK font parser can spool input bytes. Keep its temporary files in this
  # operator's private state, separate from durable objects and the static web root.
  scratch="$state/parser-tmp"
  if [[ ! -e "$scratch" && ! -L "$scratch" ]]; then mkdir -m 700 "$scratch"; fi
  [[ -d "$scratch" && -O "$scratch" && ! -L "$scratch" &&
     -n "$(find "$scratch" -prune -type d -perm 0700 -print)" ]] || fail 'Expected private parser scratch (0700).'
  set --
  if [[ "$action" == server-shared* ]]; then set -- "$@" --shared-catalog; fi
  if [[ "$action" == *two-buyers ]]; then set -- "$@" --two-buyers; fi
  exec java "-Djava.io.tmpdir=$scratch" -cp "$classpath" com.creastrix.platform.authentication.LocalAccountCartLauncher "$@"
fi
export PGPASSWORD="$CART_LOCAL_DB_PASSWORD"
local_psql=("$bin/psql" -X -v ON_ERROR_STOP=1 "${conn[@]}" -U "$role" -d "$database")
case "$action" in
  snapshot-additions)
    exec "${local_psql[@]}" -Atq <<'SQL'
SELECT coalesce(json_agg(json_build_object('id',intent_id,'sku',sku,'quantity',quantity::text,
     'state',state,'version',applied_version::text) ORDER BY applied_version,intent_id),'[]'::json)
  FROM account_cart.direct_add_receipt WHERE owner_id='10000000-0000-4000-8000-000000000001';
SQL
    ;;
  seed-two-buyers)
    exec "${local_psql[@]}" -f "$backend/src/test/resources/account-cart-local/two-buyers-fixture.sql" ;;
  next-buyer-a|next-buyer-b)
    selected=false
    if [[ "$action" == next-buyer-b ]]; then selected=true; fi
    result=$("${local_psql[@]}" -Atq -c "UPDATE account_cart_fixture.two_buyers SET next_is_b=$selected WHERE singleton RETURNING next_is_b")
    [[ -n "$result" ]] || fail 'No two-buyer fixture; no selection armed.'
    printf 'Selected %s for the next authorization in two-buyer mode.\n' "${action#next-}"; exit ;;
  snapshot-json|snapshot-json-b)
    buyer_id=10000000-0000-4000-8000-000000000001
    if [[ "$action" == snapshot-json-b ]]; then buyer_id=10000000-0000-4000-8000-000000000003; fi
    exec "${local_psql[@]}" -v buyer_id="$buyer_id" -Atq <<'SQL'
WITH buyer AS (SELECT :'buyer_id'::uuid id)
SELECT json_build_object('version',(SELECT version::text FROM account_cart.cart,buyer WHERE owner_id=buyer.id),
  'lines',(SELECT coalesce(json_agg(json_build_object('id',l.id,'sku',v.sku,'design',l.design_id,
                    'revision',l.design_revision::text,'quantity',l.quantity::text) ORDER BY l.id),'[]'::json)
             FROM account_cart.cart_line l JOIN account_cart.variant v ON (l.offer_id,l.variant_id)=(v.offer_id,v.id),buyer
            WHERE l.owner_id=buyer.id),
  'receipts',(SELECT coalesce(json_agg(json_build_object('id',r.batch_id,'state',r.state,'version',r.applied_version::text)
                   ORDER BY r.batch_id),'[]'::json) FROM account_cart.import_receipt r,buyer WHERE r.owner_id=buyer.id));
SQL
    ;;
  snapshot)
    "${local_psql[@]}" -P pager=off <<'SQL'
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT c.version,l.id,l.offer_id,l.variant_id,l.quantity,l.design_id,l.design_revision
  FROM account_cart.cart c JOIN account_cart.cart_line l USING(owner_id)
 WHERE c.owner_id='10000000-0000-4000-8000-000000000001' ORDER BY l.created_at,l.id;
SELECT state,count(*) FROM account_cart.import_receipt
 WHERE owner_id='10000000-0000-4000-8000-000000000001' GROUP BY state ORDER BY state;
COMMIT;
SQL
    exit ;;
  fault-once) exec "${local_psql[@]}" -c 'SELECT account_cart_fixture.fail_next_bindings(1)' ;;
  fault-twice) exec "${local_psql[@]}" -c 'SELECT account_cart_fixture.fail_next_bindings(2)' ;;
esac
"${local_psql[@]}" -qAt -c 'SELECT 1 FROM account_cart.lane_claim LIMIT 0' >/dev/null
java -cp "$classpath" com.creastrix.platform.accountcart.assets.LocalCartFixtureAsset
"${local_psql[@]}" -f "$backend/src/test/resources/account-cart-local/fixture.sql"
"${local_psql[@]}" -f "$backend/src/test/resources/account-cart-local/lane-fixture.sql"
"${local_psql[@]}" -f "$backend/src/test/resources/account-cart-local/loop-studio-fixture.sql"
exec "${local_psql[@]}" -f "$backend/src/test/resources/account-cart-local/tide-add-fixture.sql"
