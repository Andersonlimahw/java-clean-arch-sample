#!/usr/bin/env bash
# HTTP smoke test for the payments API. Requires curl and python3.
# Usage: BASE_URL=http://127.0.0.1:8080 ./scripts/validate-api.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
EMAIL="smoke-$(date +%s)@example.com"
PASSWORD="Password123!"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

request() {
  local name="$1"
  local expected="$2"
  shift 2
  local body="$WORKDIR/${name}.body"
  local status
  status="$(curl -sS -o "$body" -w "%{http_code}" "$@")"
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL $name: expected HTTP $expected, got $status" >&2
    cat "$body" >&2 || true
    exit 1
  fi
  echo "OK   $name HTTP $status"
}

echo "Smoke test against $BASE_URL"

request readiness 200 "$BASE_URL/actuator/health/readiness"

request register 201 \
  -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: smoke-register" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke User\"}"

request login 200 \
  -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"

TOKEN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accessToken"])' "$WORKDIR/login.body")"
if [[ -z "$TOKEN" ]]; then
  echo "FAIL login: accessToken missing" >&2
  exit 1
fi

request missing-token 401 "$BASE_URL/api/v1/payments"

request missing-idempotency-key 400 \
  -X POST "$BASE_URL/api/v1/payments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":"15.50","currency":"BRL","description":"smoke"}'

IDEM="smoke-$(date +%s)"
request create-payment 201 \
  -X POST "$BASE_URL/api/v1/payments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM" \
  -d '{"amount":"15.50","currency":"BRL","description":"smoke"}'

PAYMENT_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$WORKDIR/create-payment.body")"

request get-payment 200 \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/payments/$PAYMENT_ID"

request confirm-payment 200 \
  -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/payments/$PAYMENT_ID/confirm"

request unknown-payment 404 \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/payments/00000000-0000-0000-0000-000000000000"

# Notifications are eventual. Poll until the consumer stores one, or time out.
found=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  code="$(curl -sS -o "$WORKDIR/notifications.body" -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/api/v1/notifications")"
  if [[ "$code" != "200" ]]; then
    echo "FAIL notifications: expected HTTP 200, got $code" >&2
    exit 1
  fi
  if python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); sys.exit(0 if any(n.get("paymentId")==sys.argv[2] for n in data) else 1)' \
      "$WORKDIR/notifications.body" "$PAYMENT_ID"; then
    found=1
    break
  fi
  sleep 1
done
if [[ "$found" != "1" ]]; then
  echo "FAIL notifications: payment $PAYMENT_ID did not appear within 10s" >&2
  cat "$WORKDIR/notifications.body" >&2 || true
  exit 1
fi
echo "OK   notifications HTTP 200 (payment present)"

echo "Smoke test passed for $EMAIL"
