#!/usr/bin/env bash
# HTTP smoke test for the payments API. Requires curl and python3.
# Usage: BASE_URL=http://127.0.0.1:8080 ./scripts/validate-api.sh
# Polls readiness for READY_TIMEOUT_SECONDS (default 90) so a just-started process
# is not reported as an immediate connection failure.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
EMAIL="smoke-$(date +%s%N)@example.com"
PASSWORD="Password123!"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-90}"
NOTIFY_TIMEOUT_SECONDS="${NOTIFY_TIMEOUT_SECONDS:-10}"
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-2}"
CURL_MAX_TIME="${CURL_MAX_TIME:-5}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "FAIL missing dependency: $1" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd python3

# Prints the HTTP status, or 000 when curl cannot complete.
# quiet=1 swallows transport errors (used while polling readiness).
http_code() {
  local quiet="$1"
  local body="$2"
  shift 2
  local status curl_exit
  set +e
  status="$(curl \
    --connect-timeout "$CURL_CONNECT_TIMEOUT" \
    --max-time "$CURL_MAX_TIME" \
    -sS -o "$body" -w "%{http_code}" \
    "$@" 2>"$WORKDIR/curl.err")"
  curl_exit=$?
  set -e
  if [[ "$curl_exit" -ne 0 || -z "$status" ]]; then
    if [[ "$quiet" != "1" && -s "$WORKDIR/curl.err" ]]; then
      cat "$WORKDIR/curl.err" >&2
    fi
    printf '000'
    return 0
  fi
  printf '%s' "$status"
}

request() {
  local name="$1"
  local expected="$2"
  shift 2
  local body="$WORKDIR/${name}.body"
  local status
  status="$(http_code 0 "$body" "$@")"
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL $name: expected HTTP $expected, got $status" >&2
    if [[ -s "$body" ]]; then
      cat "$body" >&2
    fi
    exit 1
  fi
  echo "OK   $name HTTP $status"
}

wait_ready() {
  local deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
  local status="000"
  while (( SECONDS < deadline )); do
    status="$(http_code 1 "$WORKDIR/readiness.body" "$BASE_URL/actuator/health/readiness")"
    if [[ "$status" == "200" ]]; then
      echo "OK   readiness HTTP 200"
      return 0
    fi
    sleep 1
  done
  echo "FAIL readiness: expected HTTP 200 within ${READY_TIMEOUT_SECONDS}s, last status $status" >&2
  if [[ -s "$WORKDIR/curl.err" ]]; then
    cat "$WORKDIR/curl.err" >&2
  fi
  if [[ -s "$WORKDIR/readiness.body" ]]; then
    cat "$WORKDIR/readiness.body" >&2
  fi
  exit 1
}

json_field() {
  local file="$1"
  local field="$2"
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$file" "$field" 2>"$WORKDIR/json.err" || true
}

echo "Smoke test against $BASE_URL"

wait_ready

request register 201 \
  -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: smoke-register" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke User\"}"

request login 200 \
  -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"

TOKEN="$(json_field "$WORKDIR/login.body" accessToken)"
if [[ -z "$TOKEN" ]]; then
  echo "FAIL login: accessToken missing" >&2
  if [[ -s "$WORKDIR/json.err" ]]; then
    cat "$WORKDIR/json.err" >&2
  fi
  exit 1
fi

request missing-token 401 "$BASE_URL/api/v1/payments"

request missing-idempotency-key 400 \
  -X POST "$BASE_URL/api/v1/payments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":"15.50","currency":"BRL","description":"smoke"}'

IDEM="smoke-$(date +%s%N)"
request create-payment 201 \
  -X POST "$BASE_URL/api/v1/payments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM" \
  -d '{"amount":"15.50","currency":"BRL","description":"smoke"}'

PAYMENT_ID="$(json_field "$WORKDIR/create-payment.body" id)"
if [[ -z "$PAYMENT_ID" ]]; then
  echo "FAIL create-payment: id missing" >&2
  if [[ -s "$WORKDIR/json.err" ]]; then
    cat "$WORKDIR/json.err" >&2
  fi
  exit 1
fi

request get-payment 200 \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/payments/$PAYMENT_ID"

request confirm-payment 200 \
  -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/payments/$PAYMENT_ID/confirm"

history_found=0
history_deadline=$((SECONDS + NOTIFY_TIMEOUT_SECONDS))
while (( SECONDS < history_deadline )); do
  code="$(http_code 0 "$WORKDIR/history.body" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/v1/payments/$PAYMENT_ID/history")"
  if [[ "$code" != "200" ]]; then
    echo "FAIL payment-history: expected HTTP 200, got $code" >&2
    if [[ -s "$WORKDIR/history.body" ]]; then
      cat "$WORKDIR/history.body" >&2
    fi
    exit 1
  fi
  if python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); items=data["items"]; sys.exit(0 if [e["eventType"] for e in items]==["payments.payment-initiated","payments.payment-completed"] and [e["status"] for e in items]==["PENDING","COMPLETED"] and data["totalElements"]==2 else 1)' \
      "$WORKDIR/history.body"; then
    history_found=1
    break
  fi
  if (( SECONDS + 1 >= history_deadline )); then
    break
  fi
  sleep 1
done
if [[ "$history_found" != "1" ]]; then
  echo "FAIL payment-history: expected PaymentInitiated and PaymentCompleted within ${NOTIFY_TIMEOUT_SECONDS}s" >&2
  if [[ -s "$WORKDIR/history.body" ]]; then
    cat "$WORKDIR/history.body" >&2
  fi
  exit 1
fi
echo "OK   payment-history HTTP 200 (PaymentInitiated + PaymentCompleted present)"

request unknown-payment 404 \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/payments/00000000-0000-0000-0000-000000000000"

# Notifications follow the outbox relay (default poll 1s) and the consumer.
# The deadline is wall-clock time. Each request is capped by CURL_MAX_TIME.
found=0
notify_deadline=$((SECONDS + NOTIFY_TIMEOUT_SECONDS))
while (( SECONDS < notify_deadline )); do
  code="$(http_code 0 "$WORKDIR/notifications.body" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/v1/notifications")"
  if [[ "$code" != "200" ]]; then
    echo "FAIL notifications: expected HTTP 200, got $code" >&2
    if [[ -s "$WORKDIR/notifications.body" ]]; then
      cat "$WORKDIR/notifications.body" >&2
    fi
    exit 1
  fi
  if python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); sys.exit(0 if any(n.get("paymentId")==sys.argv[2] for n in data) else 1)' \
      "$WORKDIR/notifications.body" "$PAYMENT_ID"; then
    found=1
    break
  fi
  if (( SECONDS + 1 >= notify_deadline )); then
    break
  fi
  sleep 1
done
if [[ "$found" != "1" ]]; then
  echo "FAIL notifications: payment $PAYMENT_ID did not appear within ${NOTIFY_TIMEOUT_SECONDS}s" >&2
  if [[ -s "$WORKDIR/notifications.body" ]]; then
    cat "$WORKDIR/notifications.body" >&2
  fi
  exit 1
fi
echo "OK   notifications HTTP 200 (payment present)"

echo "Smoke test passed for $EMAIL"
