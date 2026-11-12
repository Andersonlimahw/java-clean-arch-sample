# ADR 0008 — Redis cache-aside and invalidation

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

`GET /api/v1/payments/{id}` is the hot read. The row changes on confirm and cancel. PostgreSQL must stay the source of truth when Redis is down, slow, or stale.

## Decision

Cache-aside, implemented by `PaymentCacheService`:

- Key prefix `payment:cache:{id}`. TTL defaults to 10 minutes (`app.cache.payment-ttl`).
- Miss loads from PostgreSQL and fills the cache.
- A per-key in-process lock collapses a stampede on a single instance.
- Confirm and cancel call `evictAfterCommit`, so a rolled-back transaction does not drop a still-valid entry, and a committed one does not keep serving the previous status.
- Redis errors increment `payments.cache.errors` and the request continues against PostgreSQL.
- Another user's id returns 404 from the ownership check. Cached payloads are still filtered by owner on the way out.

## Consequences

- A second instance can still stampede; the lock is local. That is acceptable until cache latency says otherwise.
- Eviction is delete, not an update. The next read refills. A delete that fails (Redis down) expires by TTL, so the worst case is 10 minutes of stale status, and only if Redis failed during eviction but still serves reads. If Redis is fully down, every read hits PostgreSQL.
- Lists are not cached. They are owner-scoped pages and change often.
- Do not put authorization decisions solely in the cached JSON. The handler checks the owner.

## Alternatives

| Option | Why not |
|---|---|
| Write-through | Couples the command transaction to Redis. A Redis failure would fail the payment write. |
| TTL only, no eviction | Confirm would show PENDING until expiry. |
| Cache the list endpoint | Invalidation becomes "every payment for this user" and is easy to get wrong. |
