#!/usr/bin/env bash
# Resets the LOCAL development database and replays every Flyway migration from zero.
#
# Destructive. Drops the PostgreSQL schema `public`, then runs the application in
# migration-only mode (APP_MIGRATION_ONLY=true, the same Spring Boot Flyway path the
# Kubernetes migration Job uses), then prints flyway_schema_history. By default it also
# flushes the local Redis database and purges the notification queues, because cached
# payments, rate-limit buckets and queued events would point at rows that no longer exist.
#
# Usage: scripts/db-reset.sh [--yes] [--db-only] [--allow-non-local]
#   --yes              do not prompt (required when stdin is not a terminal)
#   --db-only          reset PostgreSQL only; leave Redis and RabbitMQ untouched
#   --allow-non-local  allow a DB_URL host other than localhost/127.0.0.1/::1/postgres
#
# Connection settings come from the environment, then from the repository .env
# (variables already set win). Compose service names follow docker-compose.yml; set
# COMPOSE_PROJECT_NAME to target another Compose project. Credentials are never printed.
# Stop the application (Compose `app` service or a host JVM) before running.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ASSUME_YES=0
DB_ONLY=0
ALLOW_NON_LOCAL=0
for arg in "$@"; do
  case "$arg" in
    --yes|-y) ASSUME_YES=1 ;;
    --db-only) DB_ONLY=1 ;;
    --allow-non-local) ALLOW_NON_LOCAL=1 ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "FAIL unknown argument: $arg" >&2; exit 2 ;;
  esac
done

fail() { echo "FAIL $*" >&2; exit 1; }
info() { echo "==> $*"; }

# Load .env without overriding variables that are already set, and without echoing values.
if [[ -f .env ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
    if [[ -z "${!key+x}" ]]; then
      export "$key=$value"
    fi
  done < .env
fi

DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/${DB_NAME:-payments}}"
DB_USER="${DB_USER:-postgres}"
: "${DB_PASSWORD:?DB_PASSWORD is not set (environment or .env)}"
export DB_URL DB_USER DB_PASSWORD

if [[ ! "$DB_URL" =~ ^jdbc:postgresql://(\[[^]]+\]|[^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
  fail "DB_URL is not a jdbc:postgresql://host[:port]/database URL"
fi
DB_HOST="${BASH_REMATCH[1]}"
DB_PORT="${BASH_REMATCH[3]:-5432}"
DB_DATABASE="${BASH_REMATCH[4]}"

case "$DB_HOST" in
  localhost|127.0.0.1|::1|\[::1\]|postgres|payments-postgres) ;;
  *)
    if [[ "$ALLOW_NON_LOCAL" -ne 1 ]]; then
      fail "refusing to reset non-local database host '$DB_HOST' (pass --allow-non-local to override)"
    fi
    ;;
esac

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  COMPOSE=()
fi

service_running() {
  [[ ${#COMPOSE[@]} -gt 0 ]] || return 1
  "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx "$1"
}

if service_running app; then
  fail "the Compose 'app' service is running; stop it first (docker compose stop app)"
fi

if service_running postgres; then
  PSQL_MODE=compose
elif command -v psql >/dev/null 2>&1; then
  PSQL_MODE=host
else
  fail "no running Compose 'postgres' service and no psql on PATH"
fi

run_psql() {
  if [[ "$PSQL_MODE" == compose ]]; then
    PGPASSWORD="$DB_PASSWORD" "${COMPOSE[@]}" exec -T -e PGPASSWORD postgres \
      psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_DATABASE" "$@"
  else
    PGPASSWORD="$DB_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 \
      -h "${DB_HOST//[\[\]]/}" -p "$DB_PORT" -U "$DB_USER" -d "$DB_DATABASE" "$@"
  fi
}

echo "Target: database '$DB_DATABASE' on $DB_HOST:$DB_PORT via $PSQL_MODE psql"
if [[ "$DB_ONLY" -eq 1 ]]; then
  echo "Scope:  PostgreSQL schema 'public' only"
else
  echo "Scope:  PostgreSQL schema 'public', local Redis database, notification queues"
fi

if [[ "$ASSUME_YES" -ne 1 ]]; then
  [[ -t 0 ]] || fail "not a terminal; pass --yes to confirm the destructive reset"
  read -r -p "This deletes all local data. Type RESET to continue: " answer
  [[ "$answer" == "RESET" ]] || fail "aborted"
fi

command -v mvn >/dev/null 2>&1 || fail "mvn is required to replay migrations"

info "1. Dropping schema public"
run_psql -c "SET client_min_messages = warning; DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;"
echo "    ✓ schema public recreated"

info "2. Replaying Flyway migrations (APP_MIGRATION_ONLY=true mvn spring-boot:run)"
LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/payments-db-reset.XXXXXX")"
trap 'rm -f "$LOG_FILE"' EXIT
if ! APP_MIGRATION_ONLY=true mvn -B -q spring-boot:run >"$LOG_FILE" 2>&1; then
  tail -n 40 "$LOG_FILE" >&2
  fail "migration-only run failed"
fi
echo "    ✓ migration-only run exited 0"

info "3. Flyway state (flyway_schema_history)"
run_psql -c "SELECT installed_rank AS rank, version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;"
expected="$(find src/main/resources/db/migration -maxdepth 1 -name 'V*__*.sql' | wc -l | tr -d ' ')"
applied="$(run_psql -At -c "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL;")"
failed="$(run_psql -At -c "SELECT count(*) FROM flyway_schema_history WHERE NOT success;")"
[[ "$failed" == "0" ]] || fail "$failed migration(s) recorded as failed"
[[ "$applied" == "$expected" ]] || fail "applied $applied migration(s), expected $expected"
echo "    ✓ $applied of $expected migrations applied, none failed"

if [[ "$DB_ONLY" -eq 1 ]]; then
  echo "Done (database only)."
  exit 0
fi

info "4. Flushing local Redis database"
if service_running redis; then
  REDISCLI_AUTH="${REDIS_PASSWORD:-}" "${COMPOSE[@]}" exec -T -e REDISCLI_AUTH redis \
    redis-cli --no-auth-warning FLUSHDB | grep -qx OK || fail "redis FLUSHDB failed (check REDIS_PASSWORD)"
  echo "    ✓ Redis FLUSHDB"
else
  echo "    - Compose 'redis' not running; skipped"
fi

info "5. Purging notification queues"
# Queue names: shared.infrastructure.messaging.RabbitMqConfig.
QUEUES=(payments.created.notifications payments.created.notifications.dlq)
if service_running rabbitmq; then
  VHOST="${RABBITMQ_VHOST:-/}"
  existing="$("${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p "$VHOST" name --no-table-headers)"
  for queue in "${QUEUES[@]}"; do
    if grep -qx "$queue" <<<"$existing"; then
      "${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl -q purge_queue -p "$VHOST" "$queue" >/dev/null
      echo "    ✓ purged $queue"
    else
      echo "    - $queue not declared yet; skipped"
    fi
  done
else
  echo "    - Compose 'rabbitmq' not running; skipped"
fi

echo "Done."
