# Payment list pagination

`GET /api/v1/payments` is an offset page. It filters `payments.user_id` and orders by `created_at DESC, id DESC`, or by `created_at ASC, id ASC` when `sort=createdAt,asc`. Migration `V6__payments_list_index.sql` adds:

```sql
CREATE INDEX idx_payments_user_created_at_id
    ON payments (user_id, created_at DESC, id DESC);
```

`V6` is the next free version after `V5` on main `745f822`. The statement depends only on `payments` from `V3`. Renumber the file before merge if another branch already claimed `V6`.

Early pages use that index. A deep offset that reads most of one user's rows can still pick `idx_payments_user_status` and sort, which is what PostgreSQL 16 chose for `OFFSET 2000` below. The plans are the real `EXPLAIN (ANALYZE, BUFFERS)` output from this VM.

## Stack

From the repository root, with Docker running:

```bash
docker compose up -d --build
```

That starts PostgreSQL 16, Redis 7, RabbitMQ 3.13, and the application on `127.0.0.1:8080`. The Compose file defaults `SPRING_PROFILES_ACTIVE` to `local`, so a blank JWT key pair is generated in the container. No production secrets are required. Wait until readiness is up:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

## Seed

`perf/seed-payments.sh` registers a user, logs in, and inserts `ROWS` payments (default 10000) for that user. Pairs of rows share `created_at`, so the `id` tie-break is part of the order. The access token is written to `/tmp/payments-k6-token` and is not committed.

```bash
ROWS=10000 ./perf/seed-payments.sh
```

## k6

`perf/list-payments.js` walks pages 0, 1, and 2 with `size=20` and `sort=createdAt,desc`, then requests page 0 with `sort=createdAt,asc`. Five virtual users run for 30 seconds. Thresholds: `http_req_failed` rate under 1%, and `http_req_duration` p95 under 500 ms.

```bash
./perf/run-k6.sh
```

`run-k6.sh` uses `docker run --rm --network host` and `grafana/k6:0.57.0`. Host networking is required because Compose publishes the API on `127.0.0.1:8080`.

## EXPLAIN

Captured on this VM (`Linux 6.12.113+`) against `postgres:16-alpine` (PostgreSQL 16.15). The table had 200000 rows: 2500 for the listed user and the rest spread across other users, with `created_at` scattered. `ANALYZE` ran before each plan. The statement selects the payment columns with `LIMIT 20`.

Before `idx_payments_user_created_at_id` (only `payments_pkey`, `idx_payments_user_status`, and `idx_payments_created_at`):

```text
-- page 2 (LIMIT 20 OFFSET 40), ORDER BY created_at DESC, id DESC
 Limit  (cost=304.11..452.26 rows=20 width=81) (actual time=1.249..1.252 rows=20 loops=1)
   Buffers: shared hit=4913
   ->  Incremental Sort  (cost=7.80..18727.15 rows=2527 width=81) (actual time=0.777..1.247 rows=60 loops=1)
         Sort Key: created_at DESC, id DESC
         Presorted Key: created_at
         Full-sort Groups: 2  Sort Method: quicksort  Average Memory: 29kB  Peak Memory: 29kB
         Buffers: shared hit=4913
         ->  Index Scan using idx_payments_created_at on payments  (cost=0.42..18613.44 rows=2527 width=81) (actual time=0.138..1.174 rows=61 loops=1)
               Filter: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
               Rows Removed by Filter: 4819
               Buffers: shared hit=4904
 Planning Time: 0.848 ms
 Execution Time: 1.313 ms

-- deeper page (LIMIT 20 OFFSET 2000), ORDER BY created_at DESC, id DESC
 Limit  (cost=3108.56..3108.61 rows=20 width=81) (actual time=5.095..5.098 rows=20 loops=1)
   Buffers: shared hit=2509
   ->  Sort  (cost=3103.56..3109.87 rows=2527 width=81) (actual time=4.952..5.036 rows=2020 loops=1)
         Sort Key: created_at DESC, id DESC
         Sort Method: quicksort  Memory: 429kB
         Buffers: shared hit=2509
         ->  Bitmap Heap Scan on payments  (cost=35.88..2960.74 rows=2527 width=81) (actual time=0.522..4.317 rows=2500 loops=1)
               Recheck Cond: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
               Heap Blocks: exact=2500
               Buffers: shared hit=2503
               ->  Bitmap Index Scan on idx_payments_user_status  (cost=0.00..35.25 rows=2527 width=0) (actual time=0.293..0.293 rows=2500 loops=1)
                     Index Cond: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
                     Buffers: shared hit=3
 Planning Time: 0.605 ms
 Execution Time: 5.202 ms
```

After `CREATE INDEX idx_payments_user_created_at_id` and `ANALYZE`:

```text
-- page 2 (LIMIT 20 OFFSET 40), ORDER BY created_at DESC, id DESC
 Limit  (cost=116.93..175.19 rows=20 width=81) (actual time=0.161..0.195 rows=20 loops=1)
   Buffers: shared hit=60 read=4
   ->  Index Scan using idx_payments_user_created_at_id on payments  (cost=0.42..6603.88 rows=2267 width=81) (actual time=0.036..0.190 rows=60 loops=1)
         Index Cond: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
         Buffers: shared hit=60 read=4
 Planning Time: 0.936 ms
 Execution Time: 0.247 ms

-- deeper page (LIMIT 20 OFFSET 2000), ORDER BY created_at DESC, id DESC
 Limit  (cost=3016.99..3017.04 rows=20 width=81) (actual time=4.863..4.866 rows=20 loops=1)
   Buffers: shared hit=2509
   ->  Sort  (cost=3011.99..3017.65 rows=2267 width=81) (actual time=4.711..4.804 rows=2020 loops=1)
         Sort Key: created_at DESC, id DESC
         Sort Method: quicksort  Memory: 429kB
         Buffers: shared hit=2509
         ->  Bitmap Heap Scan on payments  (cost=33.86..2885.64 rows=2267 width=81) (actual time=0.547..4.052 rows=2500 loops=1)
               Recheck Cond: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
               Heap Blocks: exact=2500
               Buffers: shared hit=2503
               ->  Bitmap Index Scan on idx_payments_user_status  (cost=0.00..33.30 rows=2267 width=0) (actual time=0.319..0.319 rows=2500 loops=1)
                     Index Cond: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
                     Buffers: shared hit=3
 Planning Time: 0.706 ms
 Execution Time: 4.963 ms

-- page 2 (LIMIT 20 OFFSET 40), ORDER BY created_at ASC, id ASC
 Limit  (cost=116.93..175.19 rows=20 width=81) (actual time=0.163..0.185 rows=20 loops=1)
   Buffers: shared hit=62 read=1
   ->  Index Scan Backward using idx_payments_user_created_at_id on payments  (cost=0.42..6603.88 rows=2267 width=81) (actual time=0.037..0.180 rows=60 loops=1)
         Index Cond: (user_id = '11111111-1111-1111-1111-111111111111'::uuid)
         Buffers: shared hit=62 read=1
 Planning Time: 0.827 ms
 Execution Time: 0.236 ms
```

Full text, including buffer and planning lines: `perf/explain-before.txt` and `perf/explain-after.txt`.

Page 2 drops from an incremental sort over `idx_payments_created_at` (4819 rows removed by the user filter, 1.313 ms) to an index scan on `idx_payments_user_created_at_id` with no sort node (0.247 ms). Ascending order is the same index scanned backward. `OFFSET 2000` still sorts after `idx_payments_user_status`, because that page reads most of the user's 2500 rows.

## Measured k6 run

Ran on this VM after `docker compose up -d --build`. Postgres, Redis, RabbitMQ, and the app were the Compose services. Inter-container traffic on this Docker 29 bridge needed `net.bridge.bridge-nf-call-iptables=0` before the app container could open Postgres; the published `127.0.0.1` ports were already reachable from the host. Seed was `ROWS=10000 ./perf/seed-payments.sh` (10000 payments for one user). k6 was `grafana/k6:0.57.0` via `./perf/run-k6.sh` (`docker run --network host`).

Environment: Linux 6.12.113+, Docker 29.1.3, Compose 2.40.3, app container Eclipse Temurin 21.0.13, PostgreSQL 16.15, 4 CPUs, about 16 GiB RAM. Thresholds in the script: `http_req_failed` rate < 1% and `http_req_duration` p95 < 500 ms. Both passed.

```text
     checks.........................: 100.00% 9254 out of 9254
     data_received..................: 15 MB   480 kB/s
     data_sent......................: 2.2 MB  74 kB/s
     http_req_blocked...............: avg=9.57µs   min=1.03µs   med=3.17µs   max=2.73ms   p(90)=5.41µs   p(95)=7.14µs
     http_req_connecting............: avg=3.47µs   min=0s       med=0s       max=2.69ms   p(90)=0s       p(95)=0s
   ✓ http_req_duration..............: avg=6.47ms   min=2.49ms   med=5.19ms   max=39.67ms  p(90)=10.79ms  p(95)=14.72ms
       { expected_response:true }...: avg=6.47ms   min=2.49ms   med=5.19ms   max=39.67ms  p(90)=10.79ms  p(95)=14.72ms
   ✓ http_req_failed................: 0.00%   0 out of 2645
     http_req_receiving.............: avg=380.86µs min=16.97µs  med=229.02µs max=19.75ms  p(90)=790.36µs p(95)=1.16ms
     http_req_sending...............: avg=16.29µs  min=3.35µs   med=9.35µs   max=2.84ms   p(90)=18µs     p(95)=23.74µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s       p(95)=0s
     http_req_waiting...............: avg=6.07ms   min=2.36ms   med=4.89ms   max=39.12ms  p(90)=10.03ms  p(95)=14ms
     http_reqs......................: 2645    87.776625/s
     iteration_duration.............: avg=227.67ms min=212.13ms med=224.56ms max=304.69ms p(90)=244.57ms p(95)=256.08ms
     iterations.....................: 661     21.93586/s
     vus............................: 5       min=5            max=5
     vus_max........................: 5       min=5            max=5

running (0m30.1s), 0/5 VUs, 661 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  30s
```

Measured `http_req_duration` p95 is **14.72 ms**. Error rate is **0%** (0 failed of 2645 requests).
