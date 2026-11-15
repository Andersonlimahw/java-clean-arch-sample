# Specification: PR6 — Redis Cache-Aside & Resilient Eviction

**Module**: `com.muse.payments.shared.infrastructure.cache` & `com.muse.payments.payments`  
**Feature Branch**: `feat/pr6-cache`  
**Status**: Delivered — merged in PR #6 (`bc49d73`, 2026-11-11)  

---

## 1. Objectives

Implement a production-grade Cache-Aside architecture using Redis 7 for payment reads with resilient fallback, stampede protection, and transactional eviction:
1. **Cache-Aside Pattern**:
   - `GET /api/v1/payments/{id}` first checks Redis key `payment:cache:{id}`.
   - On **Cache Hit**: Deserializes JSON representation and returns immediately, skipping database querying.
   - On **Cache Miss**: Queries PostgreSQL, populates Redis with a configured TTL (e.g. 10 minutes), and returns the response.
2. **Transactional Eviction (`TransactionSynchronizationManager`)**:
   - Updates (`confirm`, `cancel`) mutate PostgreSQL state in an ACID transaction.
   - Cache eviction occurs strictly **after** transaction commit via `TransactionSynchronization.afterCommit()`. If the transaction rolls back, the cache is never prematurely invalidated.
3. **Resilience & Safe Fallback**:
   - PostgreSQL is always the single source of truth.
   - If Redis is down, times out, or encounters errors, the application safely logs a warning and falls back to PostgreSQL without failing the user's HTTP request (graceful degradation).
4. **Cache Stampede Protection**:
   - Concurrent requests for the same expired or missing key synchronize locally so only one thread executes the database read while others read the cached result.
5. **Observability & Micrometer Metrics**:
   - Counters for `payments.cache.hits`, `payments.cache.misses`, and `payments.cache.errors`.
   - Timer for `payments.cache.latency`.
