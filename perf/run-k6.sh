#!/usr/bin/env bash
# Run perf/list-payments.js on the Compose network. The default base URL is the
# service DNS name `app`, so Docker Desktop does not need host networking.
# K6_NETWORK_MODE=host uses 127.0.0.1:8080 instead.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${ENV_FILE:-/tmp/payments-k6.env}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.57.0}"
MODE="${K6_NETWORK_MODE:-compose}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "missing ${ENV_FILE}; run perf/seed-payments.sh first" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [[ "$MODE" == "host" ]]; then
  NETWORK_ARGS=(--network host)
  BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
else
  if [[ -z "${K6_NETWORK:-}" ]]; then
    K6_NETWORK="$(docker inspect payments-app --format '{{range $name, $net := .NetworkSettings.Networks}}{{$name}}{{end}}')"
  fi
  NETWORK_ARGS=(--network "$K6_NETWORK")
  BASE_URL="${BASE_URL:-http://app:8080}"
fi

docker run --rm "${NETWORK_ARGS[@]}" \
  -e BASE_URL \
  -e PAYMENTS_EMAIL \
  -e PAYMENTS_PASSWORD \
  -v "$ROOT/perf:/scripts:ro" \
  "$K6_IMAGE" run /scripts/list-payments.js
