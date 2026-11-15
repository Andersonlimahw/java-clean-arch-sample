#!/usr/bin/env bash
# Register via API, then seed authoritative events and their sync read model in
# one transaction. Rebuild preserves the dataset; no fraud/outbox traffic is seeded.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
ROWS="${ROWS:-10000}"
OTHER_ROWS="${OTHER_ROWS:-20000}"
ENV_FILE="${ENV_FILE:-/tmp/payments-k6.env}"
EMAIL="k6-$(date +%s)@example.com"
PASSWORD="${PAYMENTS_PASSWORD:-Password123!}"
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
else
  COMPOSE=(docker-compose)
fi

curl -fsS -X POST "$BASE_URL/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"displayName\":\"K6 User\"}" \
  >/dev/null

USER_ID="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d payments -tAc \
  "SELECT id FROM users WHERE email = '${EMAIL}'" | tr -d '[:space:]')"
if [[ -z "$USER_ID" ]]; then
  echo "registered user was not found in payments.users" >&2
  exit 1
fi

"${COMPOSE[@]}" exec -T postgres psql -U postgres -d payments -v ON_ERROR_STOP=1 \
  -v user_id="$USER_ID" -v rows="$ROWS" -v other_rows="$OTHER_ROWS" <<'SQL'
BEGIN;
CREATE TEMP TABLE seed_payments ON COMMIT DROP AS
SELECT
    gen_random_uuid() AS id,
    :'user_id'::uuid AS user_id,
    10.00 + (g % 50) AS amount,
    'BRL'::text AS currency,
    'k6 seed'::text AS description,
    CASE WHEN g % 5 = 0 THEN 'COMPLETED' WHEN g % 5 = 1 THEN 'CANCELLED' ELSE 'PENDING' END AS status,
    TIMESTAMPTZ '2024-01-01 00:00:00+00' + ((g / 2) * INTERVAL '1 second') AS created_at
FROM generate_series(1, :rows) AS g
UNION ALL
SELECT
    gen_random_uuid(),
    (
        substr(md5((g % 4000)::text), 1, 8) || '-' ||
        substr(md5((g % 4000)::text), 9, 4) || '-' ||
        substr(md5((g % 4000)::text), 13, 4) || '-' ||
        substr(md5((g % 4000)::text), 17, 4) || '-' ||
        substr(md5((g % 4000)::text), 21, 12)
    )::uuid,
    5.00 + (g % 20),
    'USD',
    'k6 background',
    'PENDING',
    TIMESTAMPTZ '2020-01-01 00:00:00+00' + ((g * 17 % 200000) * INTERVAL '1 minute')
FROM generate_series(1, :other_rows) AS g;

-- Allocate the transaction ID before any event positions, matching JdbcEventStore.
SELECT pg_current_xact_id() IS NOT NULL AS transaction_allocated;
INSERT INTO event_store (stream_type, stream_id, stream_version, event_id, event_type,
                        schema_version, payload, metadata, occurred_at)
SELECT 'Payment', id, 1, event_id, 'payments.payment-initiated', 1,
       jsonb_build_object('eventId', event_id, 'paymentId', id, 'userId', user_id,
                         'amount', amount, 'currency', currency, 'description', description,
                         'occurredAt', to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
       jsonb_build_object('actorId', user_id), created_at
FROM (SELECT *, gen_random_uuid() AS event_id FROM seed_payments) seeded;
INSERT INTO event_store (stream_type, stream_id, stream_version, event_id, event_type,
                        schema_version, payload, metadata, occurred_at)
SELECT 'Payment', id, 2, event_id,
       CASE WHEN status = 'COMPLETED' THEN 'payments.payment-completed' ELSE 'payments.payment-cancelled' END, 1,
       jsonb_build_object('eventId', event_id, 'paymentId', id, 'userId', user_id,
                         'reason', 'k6 seed',
                         'occurredAt', to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
       jsonb_build_object('actorId', user_id), created_at
FROM (SELECT *, gen_random_uuid() AS event_id FROM seed_payments WHERE status <> 'PENDING') seeded;
INSERT INTO payments (id, user_id, amount, currency, description, status, created_at, updated_at, version)
SELECT id, user_id, amount, currency, description, status, created_at, created_at,
       CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END
FROM seed_payments;
COMMIT;
ANALYZE payments;
SQL

umask 077
printf 'PAYMENTS_EMAIL=%q\nPAYMENTS_PASSWORD=%q\n' "$EMAIL" "$PASSWORD" > "$ENV_FILE"
echo "seeded ${ROWS} payments for ${USER_ID} and ${OTHER_ROWS} rows for other users"
echo "credentials written to ${ENV_FILE} (k6 logs in during setup; access tokens last 15 minutes)"
