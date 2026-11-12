#!/usr/bin/env bash
# Run the paginated list load test with grafana/k6 on the host network.
# The Compose app publishes 127.0.0.1:8080, so the container must share the host network.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN_FILE="${TOKEN_FILE:-/tmp/payments-k6-token}"
ACCESS_TOKEN="${ACCESS_TOKEN:-$(cat "$TOKEN_FILE")}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.57.0}"

docker run --rm --network host \
  -e BASE_URL="$BASE_URL" \
  -e ACCESS_TOKEN="$ACCESS_TOKEN" \
  -v "$ROOT/perf:/scripts:ro" \
  "$K6_IMAGE" run /scripts/list-payments.js
