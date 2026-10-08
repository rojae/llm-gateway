#!/usr/bin/env bash
# Rename the existing local Compose database in place. Run with --check first.
set -euo pipefail

cd "$(dirname "$0")/.."
mode="${1:---check}"
if [[ "$mode" != --check && "$mode" != --apply || $# -gt 1 ]]; then
  echo 'Usage: scripts/migrate-legacy-analytics-db.sh [--check|--apply]' >&2
  exit 2
fi
command -v docker >/dev/null || { echo 'Docker is required.' >&2; exit 1; }

# Keep the original Compose project identity: its named volume holds the data.
containers="$(docker ps -aq \
  --filter label=com.docker.compose.project=jev-analytics \
  --filter label=com.docker.compose.service=postgres)"
if [[ -z "$containers" || "$containers" == *$'\n'* ]]; then
  echo 'Expected exactly one existing jev-analytics PostgreSQL container. No data was changed.' >&2
  exit 1
fi
container="$containers"
if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" != true ]]; then
  echo 'Start the existing jev-analytics PostgreSQL container before migrating. No data was changed.' >&2
  exit 1
fi

sql() {
  local role="$1" database="$2" query="$3"
  docker exec "$container" psql -X -A -t -v ON_ERROR_STOP=1 -U "$role" -d "$database" -c "$query"
}
if docker exec "$container" psql -X -A -t -v ON_ERROR_STOP=1 -U jev -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
  login_role=jev
elif docker exec "$container" psql -X -A -t -v ON_ERROR_STOP=1 -U llm_gateway -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
  login_role=llm_gateway
else
  echo 'Neither expected local database role can connect. No data was changed.' >&2
  exit 1
fi
old_role="$(sql "$login_role" postgres "SELECT count(*) FROM pg_roles WHERE rolname='jev'")"
new_role="$(sql "$login_role" postgres "SELECT count(*) FROM pg_roles WHERE rolname='llm_gateway'")"
if [[ "$old_role$new_role" != 10 && "$old_role$new_role" != 01 ]]; then
  echo 'Database roles are missing or ambiguous. No data was changed.' >&2
  exit 1
fi

old_db="$(sql "$login_role" postgres "SELECT count(*) FROM pg_database WHERE datname='jev'")"
new_db="$(sql "$login_role" postgres "SELECT count(*) FROM pg_database WHERE datname='llm_gateway'")"
if [[ "$old_db$new_db" != 10 && "$old_db$new_db" != 01 ]]; then
  echo 'Database names are missing or ambiguous. No data was changed.' >&2
  exit 1
fi
database=jev
[[ "$old_db" == 0 ]] && database=llm_gateway
old_schema="$(sql "$login_role" "$database" "SELECT count(*) FROM pg_namespace WHERE nspname='jev_analytics'")"
new_schema="$(sql "$login_role" "$database" "SELECT count(*) FROM pg_namespace WHERE nspname='llm_gateway_analytics'")"
if [[ "$old_schema$new_schema" != 10 && "$old_schema$new_schema" != 01 ]]; then
  echo 'Analytics schema is missing or ambiguous. No data was changed.' >&2
  exit 1
fi
schema=jev_analytics
[[ "$new_schema" == 1 ]] && schema=llm_gateway_analytics
history="$(sql "$login_role" "$database" "SELECT to_regclass('$schema.schema_history') IS NOT NULL")"
[[ "$history" == t ]] || { echo 'Unversioned analytics schema: run the application migration first. No data was changed.' >&2; exit 1; }
migrated="$(sql "$login_role" "$database" "SELECT count(*) FROM $schema.schema_history WHERE version IN ('1','2','3','4','5') AND success")"
[[ "$migrated" == 5 ]] || { echo 'Flyway V1-V5 are not all successful. No data was changed.' >&2; exit 1; }
if [[ "$old_db" == 1 ]]; then
  sessions="$(sql "$login_role" postgres "SELECT count(*) FROM pg_stat_activity WHERE datname='jev'")"
  [[ "$sessions" == 0 ]] || { echo 'Stop application connections to the old database before migration. No data was changed.' >&2; exit 1; }
fi
if [[ "$old_role" == 1 ]]; then
  # Renaming an MD5-authenticated role clears its password in PostgreSQL.
  scram="$(sql "$login_role" postgres "SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname='jev'")"
  [[ "$scram" == t ]] || { echo 'Legacy role does not use SCRAM; password-preserving rename is unsafe. No data was changed.' >&2; exit 1; }
fi

echo "Database: $database -> llm_gateway; schema: $([[ "$old_schema" == 1 ]] && echo jev_analytics || echo llm_gateway_analytics) -> llm_gateway_analytics; role: $login_role -> llm_gateway"
if [[ "$mode" == --check ]]; then
  echo 'Ready. Stop the application and run this script with --apply.'
  exit 0
fi

if [[ "$old_schema" == 1 ]]; then
  sql "$login_role" "$database" 'ALTER SCHEMA jev_analytics RENAME TO llm_gateway_analytics' >/dev/null
fi
if [[ "$old_db" == 1 ]]; then
  sql "$login_role" postgres 'ALTER DATABASE jev RENAME TO llm_gateway' >/dev/null
fi
if [[ "$old_role" == 1 ]]; then
  # A role cannot rename itself. The temporary superuser exists only inside
  # this transaction, and the old role keeps its OID, ownership, and SCRAM key.
  sql jev postgres 'BEGIN; CREATE ROLE llm_gateway_rename_helper SUPERUSER NOLOGIN; SET SESSION AUTHORIZATION llm_gateway_rename_helper; ALTER ROLE jev RENAME TO llm_gateway; RESET SESSION AUTHORIZATION; DROP ROLE llm_gateway_rename_helper; COMMIT' >/dev/null
fi

[[ "$(sql llm_gateway postgres "SELECT count(*) FROM pg_database WHERE datname='llm_gateway'")" == 1 ]]
[[ "$(sql llm_gateway llm_gateway "SELECT count(*) FROM pg_namespace WHERE nspname='llm_gateway_analytics'")" == 1 ]]
[[ "$(sql llm_gateway llm_gateway "SELECT count(*) FROM pg_roles WHERE rolname='jev'")" == 0 ]]
echo 'Local database, schema, and role rename verified.'
echo 'Recreate the Compose service with the updated configuration before starting the application.'
