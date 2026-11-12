#!/usr/bin/env bash
# Private cluster for the local connected storefront. Never connect to the
# machine's default PostgreSQL socket and never remove existing data.
set -euo pipefail
umask 077

state="${XDG_DATA_HOME:-$HOME/.local/share}/creastrix/catalog-local"
cluster="$state/pgdata"
port=55483
database=creastrix_local_catalog
admin=local_catalog_admin
app=local_catalog_app
die() { printf '%s\n' "$*" >&2; exit 1; }

case "${1:-}" in
    start|stop|status|run|psql) action=$1; shift ;;
    *) die "Usage: bash backend/tools/local-catalog-db.sh {start|stop|status|run COMMAND...|psql [ARGS...]}" ;;
esac

if [[ -n "${CREASTRIX_PG_BIN:-}" ]]; then
    bin=$CREASTRIX_PG_BIN
elif command -v postgres >/dev/null; then
    bin=$(dirname "$(command -v postgres)")
elif [[ -x /Applications/Postgres.app/Contents/Versions/latest/bin/postgres ]]; then
    bin=/Applications/Postgres.app/Contents/Versions/latest/bin
elif command -v pg_config >/dev/null; then
    bin=$(pg_config --bindir)
else
    die "Install PostgreSQL server tools or set CREASTRIX_PG_BIN."
fi
for program in postgres initdb pg_ctl psql createdb; do
    [[ -x "$bin/$program" ]] || die "Missing $program in $bin; set CREASTRIX_PG_BIN to the PostgreSQL server bin directory."
done

[[ "$state" == /* && "$state" != *$'\n'* ]] || die "Expected an absolute private catalog state directory."
case "/$state/" in */../*|*/./*) die "Use a catalog state path without traversal." ;; esac
ancestor="$state"
while [[ ! -d "$ancestor" ]]; do
    [[ ! -L "$ancestor" && ! -e "$ancestor" ]] || die "Invalid catalog state path."
    ancestor=$(dirname "$ancestor")
done
ancestor=$(cd "$ancestor" && pwd -P)
checkout=$(cd "$(dirname "$0")/../.." && pwd -P)
while :; do
    [[ ! "$ancestor" -ef "$checkout" ]] || die "Private media must be outside the checkout and all web roots."
    [[ "$ancestor" != / ]] || break
    ancestor=$(dirname "$ancestor")
done
if [[ "$action" == start && ! -e "$state" ]]; then
    mkdir -p "$(dirname "$state")"
    mkdir -m 700 "$state"
    printf '%s\n' 'creastrix-local-catalog-v1' > "$state/cluster-kind"
    openssl rand -hex 32 > "$state/admin.password"
    openssl rand -hex 32 > "$state/app.password"
    "$bin/initdb" --pgdata="$cluster" --username="$admin" --pwfile="$state/admin.password" \
        --auth=scram-sha-256 --encoding=UTF8 --no-locale
fi
[[ -d "$state" && ! -L "$state" && -O "$state" && -f "$state/cluster-kind" ]] ||
    die "No owned Creastrix local catalog cluster at $state. Use start to initialise a new one."
[[ "$(<"$state/cluster-kind")" == creastrix-local-catalog-v1 && -f "$cluster/PG_VERSION" &&
    -s "$state/admin.password" && -s "$state/app.password" ]] ||
    die "Incomplete or unrecognised cluster at $state; inspect it manually. No data will be replaced."
private_dir() {
    if [[ ! -e "$1" && ! -L "$1" ]]; then mkdir -m 700 "$1"; fi
    [[ -d "$1" && ! -L "$1" && -O "$1" && -n "$(find "$1" -prune -type d -perm 0700 -print)" ]] ||
        die "Expected an owner-only private catalog directory."
}

# Command-line options select this cluster explicitly. Ignore inherited libpq
# settings such as a service name or hostaddr that could redirect local calls.
unset PGSERVICE PGSERVICEFILE PGHOST PGHOSTADDR PGPORT PGDATABASE PGUSER PGPASSWORD
export PGCONNECT_TIMEOUT=3
connection=(-h 127.0.0.1 -p "$port")
is_running() { "$bin/pg_ctl" -D "$cluster" status >/dev/null 2>&1; }

case "$action" in
    status) "$bin/pg_ctl" -D "$cluster" status ;;
    stop)
        if is_running; then "$bin/pg_ctl" -D "$cluster" stop -m fast -w; fi ;;
    start)
        if ! is_running; then
            "$bin/pg_ctl" -D "$cluster" -l "$state/postgres.log" \
                -o "-h 127.0.0.1 -p $port -c unix_socket_directories=''" start -w
        fi
        export PGPASSWORD="$(<"$state/admin.password")"
        admin_psql=("$bin/psql" -X -v ON_ERROR_STOP=1 "${connection[@]}" -U "$admin" -d postgres)
        role_exists=$("${admin_psql[@]}" -Atc "SELECT count(*) FROM pg_roles WHERE rolname='local_catalog_app'")
        if [[ "$role_exists" == 0 ]]; then
            CREASTRIX_NEW_PASSWORD="$(<"$state/app.password")" "${admin_psql[@]}" <<'SQL'
\getenv app_password CREASTRIX_NEW_PASSWORD
CREATE ROLE local_catalog_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
SQL
        fi
        database_exists=$("${admin_psql[@]}" -Atc "SELECT count(*) FROM pg_database WHERE datname='creastrix_local_catalog'")
        if [[ "$database_exists" == 0 ]]; then
            "$bin/createdb" "${connection[@]}" --username="$admin" --owner="$app" "$database"
        fi
        PGPASSWORD="$(<"$state/app.password")" "$bin/psql" -X -v ON_ERROR_STOP=1 "${connection[@]}" \
            -U "$app" -d "$database" -Atqc 'SELECT 1' >/dev/null
        printf 'Catalog database ready at 127.0.0.1:%s/%s; data retained in %s\n' "$port" "$database" "$state"
        ;;
    run)
        is_running || die "The dedicated catalog database is stopped. Use start."
        [[ $# -gt 0 ]] || die "run requires a command"
        media="$(cd "$state" && pwd -P)/product-media"
        private_dir "$state"
        private_dir "$media"
        private_dir "$media/derivatives"
        export CREASTRIX_CATALOG_MEDIA_DERIVATIVES="$media/derivatives"
        # Only the local operator publishes. The storefront does not need an originals root.
        unset CREASTRIX_CATALOG_MEDIA_ORIGINALS
        if [[ "$1" == "creastrix-local-photo-writer" ]]; then
            shift
            [[ $# -gt 0 ]] || die "Expected a local operator command."
            private_dir "$media/originals"
            export CREASTRIX_CATALOG_MEDIA_ORIGINALS="$media/originals"
        fi
        export CREASTRIX_CATALOG_DB_URL="jdbc:postgresql://127.0.0.1:$port/$database"
        export CREASTRIX_CATALOG_DB_USERNAME="$app"
        export CREASTRIX_CATALOG_DB_PASSWORD="$(<"$state/app.password")"
        exec "$@"
        ;;
    psql)
        is_running || die "The dedicated catalog database is stopped. Use start."
        export PGPASSWORD="$(<"$state/app.password")"
        exec "$bin/psql" -X -v ON_ERROR_STOP=1 "${connection[@]}" -U "$app" -d "$database" "$@"
        ;;
esac
