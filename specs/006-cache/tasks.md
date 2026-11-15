# Tasks: PR6 — Redis Cache-Aside & Transactional Eviction

- [x] Create specification documents (`spec.md`, `plan.md`, `tasks.md`).
- [x] Implement `PaymentCacheService` with `StringRedisTemplate`, configurable TTL, fallback error handling, and `evictAfterCommit` transactional hook.
- [x] Implement cache stampede prevention (mutex synchronization per key).
- [x] Integrate Micrometer metrics (`payments.cache.hits`, `payments.cache.misses`, `payments.cache.errors`, `payments.cache.latency`).
- [x] Update `GetPaymentByIdQueryHandler` to perform cache-aside read with ownership verification.
- [x] Update `ConfirmPaymentCommandHandler` and `CancelPaymentCommandHandler` to trigger transactional cache eviction.
- [x] Update `TestcontainersBase` to include Redis 7 container (`redis:7-alpine`) alongside PostgreSQL 16 and RabbitMQ 3.13.
- [x] Write integration test `PaymentCacheIT` verifying:
  - Cache miss on initial read, subsequent read hits Redis without DB access
  - Hit & miss metrics increment appropriately
  - Updating payment (`confirm`) evicts cache key after commit
  - Resilient fallback: read succeeds from PostgreSQL even if Redis is stopped or unreachable
- [x] Execute `mvn -B clean verify` with Testcontainers.
- [x] Push to `feat/pr6-cache`, create PR #6, review, merge, and clean worktree (merged as `bc49d73`).
