#!/usr/bin/env bash
# Register a local user and insert payments for that user so GET /api/v1/payments has pages to walk.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
ROWS="${ROWS:-10000}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-payments}"
PGPASSWORD="${PGPASSWORD:-postgres}"
TOKEN_FILE="${TOKEN_FILE:-/tmp/payments-k6-token}"
export PGPASSWORD

EMAIL="k6-$(date +%s)@example.com"
PASSWORD="Password123!"

curl -fsS -X POST "$BASE_URL/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"displayName\":\"K6 User\"}" \
  >/dev/null

TOKEN="$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"

USER_ID="$(python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p += "=" * ((4 - len(p) % 4) % 4); print(json.loads(base64.urlsafe_b64decode(p))["sub"])' "$TOKEN")"

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 \
  -v user_id="$USER_ID" -v rows="$ROWS" <<'SQL'
INSERT INTO payments (id, user_id, amount, currency, description, status, created_at, updated_at, version)
SELECT
    gen_random_uuid(),
    :'user_id'::uuid,
    10.00,
    'BRL',
    'k6 seed',
    'PENDING',
    TIMESTAMPTZ '2024-01-01 00:00:00+00' + ((g / 2) * INTERVAL '1 second'),
    TIMESTAMPTZ '2024-01-01 00:00:00+00' + ((g / 2) * INTERVAL '1 second'),
    0
FROM generate_series(1, :rows) AS g;
SQL

umask 077
printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
echo "seeded ${ROWS} payments for ${USER_ID}"
echo "token written to ${TOKEN_FILE}"
