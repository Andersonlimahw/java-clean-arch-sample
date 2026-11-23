# Spec 015 — k6 load and stress suite for the payments API

- **Status**: Implemented; the local benchmark result of 2026-11-23 is recorded in the PR
- **Updated**: 2026-11-23
- **Related**: [Spec 003](../003-payments/spec.md), [Spec 011](../011-login-auth-performance/spec.md), [perf/README.md](../../perf/README.md)

## Goal

A repeatable k6 suite under `perf/` that exercises four risk areas of the Payments
Platform against the local Docker Compose stack only: the HikariCP pool under virtual
threads, idempotency under contention, the auth rate limits, and the transactional
outbox throughput.

## Scope & acceptance criteria

1. **Four scenarios** in `perf/k6/scenarios/`, each with explicit thresholds for response
   time, error rate and status codes:
   - `01-concurrent-payments-stress.js`: 0→200 VUs in 2 min, hold 3 min, every create 201,
     p95 < 200 ms, zero 5xx.
   - `02-idempotency-race-condition.js`: 50 requests with one `Idempotency-Key`; exactly one
     fresh 201; every other response is 409, a replayed 201, or 503 with `Retry-After: 1`;
     then a sequential replay returns 201 with `Idempotent-Replayed: true`.
   - `03-rate-limiting-auth-stress.js`: login limits as the code enforces them (see below).
   - `04-outbox-soak.js`: 5,000 payments in 1 min, outbox drain polled from
     `/actuator/prometheus`; at the end the outbox, the notifications queue and its DLQ are 0.
2. **Runner**: `perf/run-benchmark.sh` brings the Compose stack up, checks readiness and the
   Flyway state, seeds with `perf/seed-payments.sh`, runs the four scenarios sequentially,
   and writes `.log`, `.json` and `.html` summaries plus `SUMMARY.txt` to
   `perf/results/<UTC stamp>/`. That directory is git-ignored.
3. **Local targets only**: `perf/k6/lib/config.js` and `run-benchmark.sh` refuse any
   `BASE_URL` other than `127.0.0.1`, `localhost`, or the Compose service `app`.
4. **No production code change.** The only runtime change is
   `perf/compose.benchmark.yml`, applied to the local stack by the runner (see Deviations).
5. **Reuse**: the seed and its credential file (`/tmp/payments-k6.env`, mode 600) come from
   `perf/seed-payments.sh`; the Compose-network convention follows `perf/run-k6.sh`.

## Facts checked in the code (2026-11-21)

| Topic | Where | Value the suite asserts |
|---|---|---|
| Auth IP limit | `AuthController.checkIpRateLimit`, `AuthRateLimiter.tryAcquire`, `app.security.auth-ip-max-requests-per-minute` (default 20) | 20/min per remote address, shared by register, login and refresh |
| Auth account limit | `AuthRateLimiter.tryAcquireAccount`, key = SHA-256 of the lowercased email, `auth-account-max-requests-per-minute` (default 15) | 16th login for one email → 429 |
| 429 response | `AuthController` throws `RateLimitExceededException(…, 60)`; `GlobalExceptionHandler` sets `Retry-After` | `Retry-After` present |
| Payment write limit | `PaymentController.enforceWriteRateLimit`, per user, `PAYMENT_WRITE_RATE_LIMIT_PER_MINUTE` (default 120) | raised for the local benchmark only |
| Idempotency, key in flight | `IdempotencyService.decisionForLiveRecord` → `ConflictException` | 409 |
| Idempotency, DB lock contention | `GlobalExceptionHandler.handleTransientLock` | 503 with `Retry-After: 1` (accepted alternative) |
| Replay | `PaymentController.created(…, true)` | 201 with `Idempotent-Replayed: true` |
| Outbox gauge | `OutboxMetrics`: `payments.outbox.pending` | Prometheus `payments_outbox_pending` (confirmed on the running app) |
| Queue gauges | `QueueDepthMetrics`: `payments.queue.messages{queue}` | `payments_queue_messages{queue="…"}` (confirmed) |

## Deviations from the original brief

- **Auth limit**: the brief says 20/min. The code limits an account to 15/min and an IP to
  20/min. Scenario 03 asserts the code's values, not the brief's.
- **Idempotency status**: both 409 and 503 + `Retry-After: 1` are legitimate per the code;
  the suite accepts either and counts them separately.
- **"Exactly one 201"** counts fresh 201s only. A replay is also a 201, so it carries
  `Idempotent-Replayed: true` and is counted apart.
- **Write limit for the local benchmark**: with the default 120 writes/min per user, scenarios
  01 and 04 would mostly get 429, not measure latency. `perf/compose.benchmark.yml` raises
  `PAYMENT_WRITE_RATE_LIMIT_PER_MINUTE` for the local stack only. It is not production config,
  and the auth limits are left as shipped.
- **Scenario 02 concurrency** is best effort: k6 starts VUs with a small spread, not a barrier.
- **Dropped messages**: the check is outbox pending = 0 and ready and DLQ depth = 0 at the
  end. A message that is unacked when sampling ends is not visible to this check.
- **Seed script name**: the brief names `perf/seed-data.sh`; the existing
  `perf/seed-payments.sh` is reused instead.

## Measured result (local, 2026-11-23)

One run of `perf/run-benchmark.sh` against the local Compose stack, 11 vCPU arm64 macOS,
Docker 29.5.2, k6 v1.4.2, Flyway at V9, `PAYMENT_WRITE_RATE_LIMIT_PER_MINUTE=1000000`
(local override only). Results in `perf/results/20261123T212956Z/` (never committed).

| Scenario | Exit | Measured |
|---|---|---|
| 01 concurrent payments | 99 | p95 969 ms (threshold 200 ms), avg 384 ms, max 4.4 s; 125,085 requests at 415.6/s; 0% failed; 125,084/125,084 created 201; zero 5xx |
| 02 idempotency race | 0 | 50 concurrent, 1 fresh 201, 46 in-flight 409, 3 replayed 201, 0 unexpected status, 0 missing `Retry-After` |
| 03 auth rate limit | 0 | 41 attempts, 41/41 matched the expected status, 6× 429, zero unexpected status, p95 219 ms |
| 04 outbox soak | 0 | 5,001 created 201, zero 5xx, zero failed scrapes; outbox pending 0, queue 0, DLQ 0 at the end |

### Findings (not fixed here — production code is out of scope)

1. **Scenario 01 misses the 200 ms p95.** The HikariCP pool (20 connections) saturates from
   the first seconds: `hikaricp_connections_active` stays at 20 with
   `hikaricp_connections_pending` between 44 and 182 for the whole run. Every request waits
   for a connection, so the p95 is set by pool wait time, not by the payment write path.
   Zero requests failed and no 5xx appeared, so this is saturation, not a defect in the
   create path. The threshold stays at 200 ms and the scenario keeps failing until the pool
   or the workload changes.
2. **The outbox relay and the notification consumer are not balanced.** Under the 01 load
   the outbox gauge climbed to 84k pending rows while the queue grew to 26.7k ready
   messages, then drained afterwards at ~271 messages/s, well under the arrival rate. The
   relay publishes one event per aggregate per batch (`OutboxRelayService`, batch 50, 20
   batches per poll) and the listener is single-threaded, so a burst of creates outruns the
   consumer for minutes. Nothing is lost (DLQ stayed at 0 and the queue reached 0), so the
   suite reports it as a throughput observation.
3. **Run-to-run variance is large.** Two earlier runs of scenario 01 on the same machine
   gave 31 req/s (p95 12.4 s) and 191 req/s (p95 4.3 s), against 415 req/s (p95 969 ms) in
   the recorded run. The difference is the state the stack was left in by the previous run
   (outbox backlog and queue depth carried over). Single-run numbers should be read with
   that in mind.

## Out of scope

- Flyway migrations (the next one, V10, belongs to the LGPD work).
- Runs against production, staging, or any non-local host.
- HikariCP pool metrics assertions (the 5xx and 503 signals stand in for pool exhaustion).
- Keyset pagination (see `perf/README.md`).

## How to run

```bash
docker-compose -f docker-compose.yml -f perf/compose.benchmark.yml up -d
perf/run-benchmark.sh          # exit 0 only when all four scenarios pass their thresholds
```

Results land in `perf/results/<UTC stamp>/`, which is never committed.
