# Payment list pagination

`GET /api/v1/payments` filters `payments.user_id` and orders by `created_at DESC, id DESC` (or `created_at ASC, id ASC`). The consolidated `V3__payments.sql` migration creates `idx_payments_user_created_at_id` on `(user_id, created_at DESC, id DESC)` for a clean database.

## Version

The Event Sourcing rollout rewrites the former V1–V7 history into V1–V4 because the development database is treated as new and no backfill is required. The index is created as part of `V3__payments.sql`; it is not a separate concurrent index migration.

The earlier k6 and EXPLAIN measurements used the same index definition from the former V6 migration. On a clean schema, `CREATE INDEX` runs in the normal Flyway transaction. `countByUserId` and the page query both use the leading `user_id` of the index.

## Stack

From the repository root, with Docker running:

```bash
docker compose up -d --build
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

Compose starts PostgreSQL 16, Redis 7, RabbitMQ 3.13, and the app. `SPRING_PROFILES_ACTIVE` defaults to `local`, so a blank JWT key pair is generated in the container.

## Seed

`perf/seed-payments.sh` needs `curl` and Compose (`docker compose` or `docker-compose`). Register goes through the API. One SQL transaction inserts authoritative initiated/completed/cancelled facts and the matching synchronous payment projection, bypassing fraud screening and outbox traffic, then runs `ANALYZE payments`. Rebuild preserves the dataset. Default is 10000 rows for the registered user (mixed status, paired `created_at`) and 20000 rows for other users. `COMPOSE_FILE` selects an isolated stack; use `BASE_URL` and `K6_NETWORK` for its ports/network.

```bash
ROWS=10000 OTHER_ROWS=20000 ./perf/seed-payments.sh
```

Credentials go to `/tmp/payments-k6.env` (mode 600). They are not committed. Each k6 run logs in from `setup()`, so a token older than 15 minutes (`app.jwt.access-token-ttl`) is not reused.

## k6

Two scenarios run together for 30 seconds with 5 VUs each:

- `early`: pages 0, 1, and 2 at `size=20`, plus page 0 ascending.
- `deep`: a random page in the upper half of the caller's pages, still inside the offset cap (`page * size <= 10000`).

Thresholds: `http_req_failed` rate under 1%, `checks` rate above 0.99, and `http_req_duration` p95 under 50 ms for both `early_pages` and `deep_pages`.

```bash
./perf/run-k6.sh
```

The default attaches k6 to the Compose network of `payments-app` and calls `http://app:8080`. That works on Docker Desktop. `K6_NETWORK_MODE=host` is optional and calls `http://127.0.0.1:8080`. `run-k6.sh` passes `PAYMENTS_EMAIL` and `PAYMENTS_PASSWORD` through the environment (`-e NAME` with no value on the command line).

## EXPLAIN

`./perf/explain.sh` starts a throwaway `postgres:16-alpine`, loads 200000 rows (2500 for user `11111111-1111-1111-1111-111111111111`, `g % 80 = 0`, scattered `created_at`), runs `ANALYZE`, and writes `perf/explain-before.txt` and `perf/explain-after.txt`. The statements are the Hibernate 6 text (`pje1_0`, `OFFSET ... FETCH FIRST`) for the page query and `select count(pje1_0.id) ... where user_id = ?`.

Captured on this VM (`Linux 6.12.113+`, PostgreSQL 16.15) by that script:

Before the new index, page 2 (`OFFSET 40`) is an incremental sort over `idx_payments_created_at` (4819 rows removed by the user filter), **1.362 ms**. `OFFSET 2000` is a bitmap on `idx_payments_user_status` plus a sort of 2500 rows, **7.336 ms**. The count is the same bitmap, **5.953 ms**.

After `idx_payments_user_created_at_id` and the two drops, page 2 is an index scan on that index with no sort node, **0.330 ms**. Ascending page 2 is an index scan backward, **0.275 ms**. `OFFSET 2000` is still a bitmap plus sort, now on `idx_payments_user_created_at_id`, **6.820 ms**, because that page reads most of the user's 2500 rows. The count uses the same bitmap, **4.778 ms**. Every list request runs that count; it is O(rows for the user). The handler does not skip it: `totalElements` is part of the contract on every page, and a short page is not proof that no later row exists.

## Limits and follow-up

`page * size` above 10000 is HTTP 400, so a caller cannot ask for an unbounded deep offset. Within the cap, offset pages are stable only for a static dataset. A concurrent insert at the head can duplicate a row on the next page, and the count can disagree with `items`. A keyset cursor on `(created_at, id)` is the follow-up when a client needs to walk past the cap, or walk a list that is being written, without those shifts. It is not in this change.

## Measured k6 run

Second run on this VM after the per-scenario thresholds were set to 50 ms. `docker compose up -d --build` on a fresh volume, `ROWS=10000 OTHER_ROWS=20000 ./perf/seed-payments.sh`, then `./perf/run-k6.sh` (`grafana/k6:0.57.0` on the Compose network, `BASE_URL=http://app:8080`). Environment: Linux 6.12.113+, Docker 29.1.3, Compose 2.40.3, app Temurin 21.0.13, PostgreSQL 16.15, 4 CPUs, about 16 GiB RAM. Both scenario thresholds passed.

```text
   ✓ checks.........................: 100.00% 12658 out of 12658
     http_req_duration..............: avg=4.23ms   min=1.87ms   med=3.49ms   max=321.29ms p(90)=6.57ms   p(95)=8.16ms
     ✓ { scenario:deep_pages }......: avg=6.22ms   min=2.77ms   med=5.54ms   max=27.16ms  p(90)=8.88ms   p(95)=11.33ms
     ✓ { scenario:early_pages }.....: avg=3.6ms    min=1.87ms   med=3.1ms    max=21.79ms  p(90)=5.27ms   p(95)=6.61ms
   ✓ http_req_failed................: 0.00%   0 out of 3516
     http_reqs......................: 3516    115.273711/s
     iterations.....................: 1422    46.620938/s

running (0m30.5s), 00/10 VUs, 1422 complete and 0 interrupted iterations
deep_pages  ✓ [ 100% ] 5 VUs  30s
early_pages ✓ [ 100% ] 5 VUs  30s
```

**early_pages p95: 6.61 ms. deep_pages p95: 11.33 ms.** Error rate 0% (0 of 3516). Checks 12658/12658. The 321.29 ms max is outside the scenario sub-metrics (setup login and the first list).

## Troubleshooting

These are not required steps.

- Compose containers cannot reach each other, while `127.0.0.1` published ports work: on this measuring VM, Docker 29's bridge netfilter dropped that traffic until `sudo sysctl -w net.bridge.bridge-nf-call-iptables=0` and the IPv6 equivalent. Recheck with `docker compose exec postgres nc -zvw2 redis 6379`.
- k6 returns 401: `setup()` logs in on every run. If register was rate-limited (20 auth calls per minute per IP), wait and rerun `perf/seed-payments.sh`.
- Existing local databases have the former Flyway history and must be reset with `scripts/db-reset.sh` before using the consolidated migration history; use the script only against local development Compose.
- The Compose project name is not `java-clean-arch-sample`: `run-k6.sh` reads the network from the `payments-app` container. Set `K6_NETWORK` if that container is not running.

## Load and stress suite (k6)

Spec: [specs/015-k6-load-stress-suite/spec.md](../specs/015-k6-load-stress-suite/spec.md).

The suite under `perf/k6/scenarios/` covers four risk areas against the local Docker Compose stack:

1. `01-concurrent-payments-stress.js`: 0→200 VUs ramp (2 min) and hold (3 min). Asserts all 201, zero 5xx, and verifies HikariCP pool resilience under virtual threads.
2. `02-idempotency-race-condition.js`: 50 concurrent requests with the same `Idempotency-Key` and user. Asserts exactly one fresh 201, with remaining in-flight requests resolving to 409 or 503 (`Retry-After: 1`), followed by a replay returning 201 with `Idempotent-Replayed: true`.
3. `03-rate-limiting-auth-stress.js`: validates `POST /api/v1/auth/login` limits per account (15/min, SHA-256 email) and per IP (20/min), verifying 429 with `Retry-After`.
4. `04-outbox-soak.js`: transactional outbox soak (5,000 payments in 1 min, constant arrival rate) with Prometheus scraping (`payments_outbox_pending`, `payments_queue_messages` for ready and DLQ) verifying full drain to zero.

### Running the benchmark

```bash
docker compose -f docker-compose.yml -f perf/compose.benchmark.yml up -d
perf/run-benchmark.sh
```

Summaries (`.log`, `.json`, `.html`, `SUMMARY.txt`) are generated in `perf/results/<timestamp>/` (git-ignored).

`run-benchmark.sh` refuses any `BASE_URL` that is not `127.0.0.1` or `localhost`, and it
records the machine, the Docker and k6 versions, the Flyway version and the container
state next to the results. `ENV_FILE` (default `/tmp/payments-k6.env`, mode 600) holds the
seeded credentials and is never committed.

### Measured run (2026-11-23, local)

`perf/results/20261123T212956Z/`. 11 vCPU arm64 macOS, Docker 29.5.2, k6 v1.4.2, Flyway
V9, write limit raised for the local stack only.

| Scenario | Exit | Result |
|---|---|---|
| 01 concurrent payments | 99 | p95 969 ms (threshold 200 ms), 415.6 req/s, 0% failed, 125,084/125,084 created 201 |
| 02 idempotency race | 0 | 1 fresh 201, 46 in-flight 409, 3 replayed 201, 0 unexpected status |
| 03 auth rate limit | 0 | 41/41 as expected, 6× 429, 0 unexpected status |
| 04 outbox soak | 0 | 5,001 created 201, 0 five-x, outbox 0, queue 0, DLQ 0 at the end |

Scenario 01 fails only on the p95 threshold. The HikariCP pool (20 connections) is
saturated for the whole run — `hikaricp_connections_pending` stays between 44 and 182 —
so the p95 is pool wait time, not the write path. Zero requests failed. The suite does not
relax that threshold to pass; the numbers go to the PR as a finding.

Under the same load the outbox gauge reached 84k pending rows and the notifications queue
26.7k ready messages, then drained at ~271 msg/s. Nothing was lost: the DLQ stayed at 0
and the queue reached 0. The relay publishes one event per aggregate per batch
(`OutboxRelayService`: batch 50, 20 batches per poll) and the notification listener is
single-threaded, so a burst outruns the consumer for a few minutes.

