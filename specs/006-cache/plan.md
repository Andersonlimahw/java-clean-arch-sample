# Architecture Plan: PR6 — Redis Cache-Aside & Transactional Eviction

## 1. Components & Architecture
- **Cache Service**: `PaymentCacheService`:
  - Uses `RedisTemplate<String, String>` or `StringRedisTemplate`.
  - Serializes `PaymentResponse` to JSON via Jackson.
  - TTL: Configurable via `app.cache.payment-ttl` (default 10 minutes).
  - Methods:
    - `Optional<PaymentResponse> get(UUID paymentId)`
    - `void put(UUID paymentId, PaymentResponse response)`
    - `void evict(UUID paymentId)`
    - `void evictAfterCommit(UUID paymentId)`
- **Cache Stampede Guard**:
  - Key-level lock or concurrent map guard so multiple simultaneous requests for the same missing key serialize the DB fetch.
- **Transactional Synchronization**:
  - `TransactionSynchronizationManager.isSynchronizationActive()`
  - Registers `TransactionSynchronization` with `afterCommit()` calling `evict(paymentId)`.
- **Query Handler Integration**:
  - `GetPaymentByIdQueryHandler` checks `paymentCacheService.get(paymentId)`:
    - If hit: verifies ownership (`response.userId().equals(query.userId())`). If mismatched -> 404. Returns response.
    - If miss: queries DB via `paymentRepository.findById(query.paymentId())`, verifies ownership, saves to cache via `paymentCacheService.put(paymentId, response)`, and returns response.
- **Command Handler Invalidation**:
  - `ConfirmPaymentCommandHandler` & `CancelPaymentCommandHandler` invoke `paymentCacheService.evictAfterCommit(payment.getId())`.

## 2. Metrics & Observability
- MeterRegistry integration:
  - `payments.cache.hits`
  - `payments.cache.misses`
  - `payments.cache.errors`
  - `payments.cache.latency`
