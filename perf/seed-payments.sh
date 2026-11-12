#!/usr/bin/env bash
# Register one user through the API, then insert list-test rows with psql inside
# the Compose Postgres container. The HTTP create path is skipped on purpose:
# this seeds a read-only list. Host psql and host python are not required.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
ROWS="${ROWS:-10000}"
OTHER_ROWS="${OTHER_ROWS:-20000}"
ENV_FILE="${ENV_FILE:-/tmp/payments-k6.env}"
EMAIL="k6-$(date +%s)@example.com"
PASSWORD="${PAYMENTS_PASSWORD:-Password123!}"

curl -fsS -X POST "$BASE_URL/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"displayName\":\"K6 User\"}" \
  >/dev/null

USER_ID="$(docker compose exec -T postgres psql -U postgres -d payments -tAc \
  "SELECT id FROM users WHERE email = '${EMAIL}'" | tr -d '[:space:]')"
if [[ -z "$USER_ID" ]]; then
  echo "registered user was not found in payments.users" >&2
  exit 1
fi

docker compose exec -T postgres psql -U postgres -d payments -v ON_ERROR_STOP=1 \
  -v user_id="$USER_ID" -v rows="$ROWS" -v other_rows="$OTHER_ROWS" <<'SQL'
INSERT INTO payments (id, user_id, amount, currency, description, status, created_at, updated_at, version)
SELECT
    gen_random_uuid(),
    :'user_id'::uuid,
    10.00 + (g % 50),
    'BRL',
    'k6 seed',
    CASE WHEN g % 5 = 0 THEN 'COMPLETED' WHEN g % 5 = 1 THEN 'CANCELLED' ELSE 'PENDING' END,
    TIMESTAMPTZ '2024-01-01 00:00:00+00' + ((g / 2) * INTERVAL '1 second'),
    TIMESTAMPTZ '2024-01-01 00:00:00+00' + ((g / 2) * INTERVAL '1 second'),
    0
FROM generate_series(1, :rows) AS g;

INSERT INTO payments (id, user_id, amount, currency, description, status, created_at, updated_at, version)
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
    TIMESTAMPTZ '2020-01-01 00:00:00+00' + ((g * 17 % 200000) * INTERVAL '1 minute'),
    TIMESTAMPTZ '2020-01-01 00:00:00+00' + ((g * 17 % 200000) * INTERVAL '1 minute'),
    0
FROM generate_series(1, :other_rows) AS g;

ANALYZE payments;
SQL

umask 077
cat > "$ENV_FILE" <<EOF
PAYMENTS_EMAIL=${EMAIL}
PAYMENTS_PASSWORD=${PASSWORD}
EOF
echo "seeded ${ROWS} payments for ${USER_ID} and ${OTHER_ROWS} rows for other users"
echo "credentials written to ${ENV_FILE} (k6 logs in during setup; access tokens last 15 minutes)"
