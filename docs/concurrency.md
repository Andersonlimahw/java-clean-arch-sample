# Concurrency and consistency

## Payment optimistic locking

`PaymentJpaEntity.version` is annotated with JPA `@Version`. Concurrent transactions that both update the same version cannot silently overwrite one another. Spring's optimistic-lock exception is returned as `409 Conflict` with title `Concurrent Modification` and type `urn:problem-type:concurrent-modification`.

A later request that starts after the first transaction commits may instead fail the aggregate's state-transition rule and return a regular `409` (`urn:problem-type:409`). The API does not expose an `ETag` or require `If-Match`.

## Database deadlocks and serialization failures

PostgreSQL deadlocks (`40P01`) and serialization failures (`40001`) are treated as transient lock failures. The API returns `503 Service Unavailable`, type `urn:problem-type:transient-lock`, and `Retry-After: 1`. This is a client-directed retry of the same request after the indicated delay; there is no automatic application retry around payment transactions. The Resilience4j retry policy applies to fraud screening only.

## Virtual threads

`spring.threads.virtual.enabled=true` enables Spring Boot virtual threads. Fraud-screening HTTP calls also use an explicit virtual-thread-per-task executor. The scheduler uses Boot's `SimpleAsyncTaskScheduler` when virtual threads are enabled. Virtual threads do not increase the configured JDBC pool capacity.

## Redis cache and stampede protection

Payment detail reads use cache-aside keys shaped as `payment:cache:<payment-id>` with a default TTL of 10 minutes. Redis read/write errors are recorded and fail open to PostgreSQL. Confirm and cancel evict the cache after the database transaction commits.

On a cache miss, `PaymentCacheService` uses a `ConcurrentHashMap` of per-payment `ReentrantLock` instances, checks Redis again after acquiring the lock, and runs one database loader for concurrent misses of the same payment within that application process. The lock is JVM-local; it is not a distributed lock stored in Redis. Different payment IDs can load concurrently.

## Database work sharing

The outbox relay selects eligible unpublished rows with `FOR UPDATE SKIP LOCKED`, claims a row by moving `next_attempt_at`, commits that claim, then publishes and awaits the RabbitMQ publisher confirm. A confirmed publication marks `published_at`; a failed publish is rescheduled in the outbox. Idempotency expiry cleanup also uses `FOR UPDATE SKIP LOCKED` and deletes in batches, so concurrent application instances can process separate rows.

## Implementation references

- Versioned entity: `src/main/java/com/muse/payments/payments/infrastructure/PaymentJpaEntity.java`.
- HTTP lock mapping: `src/main/java/com/muse/payments/shared/web/GlobalExceptionHandler.java`.
- Virtual-thread and datasource settings: `src/main/resources/application.yml` and `src/main/java/com/muse/payments/payments/infrastructure/fraud/FraudScreeningConfiguration.java`.
- Cache key, TTL, and local per-key lock: `src/main/java/com/muse/payments/payments/infrastructure/cache/PaymentCacheService.java`.
- Outbox claim/publish lifecycle: `src/main/java/com/muse/payments/shared/infrastructure/OutboxRelayService.java` and `OutboxJpaRepository.java`.
- Idempotency cleanup: `src/main/java/com/muse/payments/shared/infrastructure/idempotency/IdempotencyCleanup.java` and `JdbcIdempotencyRepository.java`.
