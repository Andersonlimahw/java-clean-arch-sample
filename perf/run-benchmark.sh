#!/usr/bin/env bash
# Runs the four k6 scenarios one after another against the LOCAL Docker Compose stack.
# Refuses any BASE_URL that is not 127.0.0.1 or localhost. Results go to
# perf/results/<UTC stamp>/ (git-ignored, never committed). Credentials are read from
# ENV_FILE and are never printed. Exit 0 only if every scenario exits 0 (all thresholds pass).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
case "$BASE_URL" in
  http://127.0.0.1|http://127.0.0.1:[0-9]*|http://localhost|http://localhost:[0-9]*) ;;
  *) echo "BASE_URL must be a local http://127.0.0.1 or localhost target; refusing." >&2; exit 2 ;;
esac

ENV_FILE="${ENV_FILE:-/tmp/payments-k6.env}"
OVERRIDE="perf/compose.benchmark.yml"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.57.0}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS_DIR="${RESULTS_DIR:-$ROOT/perf/results/$STAMP}"

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose -f docker-compose.yml -f "$OVERRIDE")
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose -f docker-compose.yml -f "$OVERRIDE")
else
  echo "Docker Compose not found (docker compose or docker-compose)." >&2
  exit 1
fi

mkdir -p "$RESULTS_DIR"
SUMMARY="$RESULTS_DIR/SUMMARY.txt"
: >"$SUMMARY"

echo "==> 0. Environment (recorded in $RESULTS_DIR)"
{
  echo "stamp_utc=$STAMP"
  echo "base_url=$BASE_URL"
  echo "host=$(uname -srm)"
  echo "cpus=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo unknown)"
  echo "memory_bytes=$(sysctl -n hw.memsize 2>/dev/null || echo unknown)"
  echo "docker=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)"
  echo "k6=$(k6 version 2>/dev/null | head -1 || echo "docker $K6_IMAGE")"
} >"$RESULTS_DIR/environment.txt"
cat "$RESULTS_DIR/environment.txt"

echo "==> 1. Compose stack (up with the benchmark override)"
"${COMPOSE[@]}" up -d
"${COMPOSE[@]}" ps >"$RESULTS_DIR/compose-ps.txt"
if ! "${COMPOSE[@]}" ps --services --status running | grep -qx app; then
  echo "payments-app is not running after compose up; see $RESULTS_DIR/compose-ps.txt" >&2
  exit 1
fi

READY=0
for _ in $(seq 1 60); do
  if curl -fsS "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; then READY=1; break; fi
  sleep 5
done
if [[ "$READY" != 1 ]]; then
  echo "app readiness did not answer at $BASE_URL within 5 minutes" >&2
  exit 1
fi

echo "==> 2. Flyway state and write-limit override"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d payments -tAc \
  "SELECT 'flyway_max_version=' || max(version) || ' failed=' || count(*) FILTER (WHERE NOT success) FROM flyway_schema_history" \
  | tee "$RESULTS_DIR/flyway.txt"
docker exec payments-app sh -c 'echo "payment_write_limit_per_min=$PAYMENT_WRITE_RATE_LIMIT_PER_MINUTE"' \
  | tee -a "$RESULTS_DIR/flyway.txt"

echo "==> 3. Seed (perf/seed-payments.sh)"
ENV_FILE="$ENV_FILE" BASE_URL="$BASE_URL" ROWS="${ROWS:-10000}" OTHER_ROWS="${OTHER_ROWS:-20000}" \
  ./perf/seed-payments.sh >"$RESULTS_DIR/seed.log" 2>&1
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if command -v k6 >/dev/null 2>&1; then
  K6_MODE=local
else
  K6_MODE=docker
  K6_NETWORK="$(docker inspect payments-app --format '{{range $name, $net := .NetworkSettings.Networks}}{{$name}}{{end}}')"
fi

# Every 5 s while a scenario runs: connection pool, fraud bulkhead, JVM threads and container CPU/memory.
# Output is perf/results/<stamp>/<name>.samples; it shows where a slow scenario spent its time.
sample_loop() {
  local out="$1"
  while true; do
    {
      echo "--- $(date -u +%H:%M:%S)"
      curl -fsS "$BASE_URL/actuator/prometheus" 2>/dev/null \
        | grep -E '^(hikaricp_connections_(active|pending|max)\{|resilience4j_bulkhead_available_concurrent_calls\{|jvm_threads_live_threads)' || true
      docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' 2>/dev/null || true
    } >>"$out"
    sleep 5
  done
}

run_scenario() {
  local name="$1" script="$2" status=0 sampler
  echo "==> $name"
  sample_loop "$RESULTS_DIR/$name.samples" &
  sampler=$!
  if [[ "$K6_MODE" == local ]]; then
    BASE_URL="$BASE_URL" RESULTS_DIR="$RESULTS_DIR" k6 run "perf/k6/scenarios/$script" \
      >"$RESULTS_DIR/$name.log" 2>&1 || status=$?
  else
    docker run --rm --network "$K6_NETWORK" \
      -e BASE_URL=http://app:8080 -e RESULTS_DIR=/results \
      -e PAYMENTS_EMAIL -e PAYMENTS_PASSWORD \
      -v "$ROOT/perf:/scripts:ro" -v "$RESULTS_DIR:/results" \
      "$K6_IMAGE" run "/scripts/k6/scenarios/$script" >"$RESULTS_DIR/$name.log" 2>&1 || status=$?
  fi
  kill "$sampler" 2>/dev/null || true
  wait "$sampler" 2>/dev/null || true
  grep -E '^(THRESHOLDS|== )' "$RESULTS_DIR/$name.log" || true
  echo "$name exit=$status" | tee -a "$SUMMARY"
}

echo "==> 4. Scenarios, sequential"
run_scenario 01 01-concurrent-payments-stress.js
run_scenario 02 02-idempotency-race-condition.js
# Scenario 03 needs an empty per-IP auth window; scenarios 01-02 logged in within the last minute.
sleep 61
run_scenario 03 03-rate-limiting-auth-stress.js
# Scenario 03 exhausted the per-IP auth rate limit (21 attempts); wait for the window to reset before 04 logs in.
sleep 61
run_scenario 04 04-outbox-soak.js

echo "==> 5. Broker state after the soak (ready and dead-letter depth)"
"${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl list_queues name messages \
  | tee "$RESULTS_DIR/rabbitmq-queues.txt" || true

if grep -q 'exit=0' "$SUMMARY" && [[ "$(grep -c 'exit=0' "$SUMMARY")" -eq 4 ]]; then
  echo "ALL 4 SCENARIOS PASSED (results: $RESULTS_DIR)"
  exit 0
fi
echo "SCENARIO FAILURE (see $SUMMARY and $RESULTS_DIR/*.log)" >&2
exit 1
