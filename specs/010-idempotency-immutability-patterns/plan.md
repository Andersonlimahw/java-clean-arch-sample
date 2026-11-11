# Architecture Plan: Idempotency, Correlation & Immutability Patterns

## Component Design

### 1. Database Schema (`idempotency_keys`)
```sql
CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(120) NOT NULL,
    user_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    response_code INTEGER NULL,
    response_body JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires
    ON idempotency_keys (expires_at);
```

### 2. Spring Integration Architecture
- **Annotation**: `@Idempotent` placed on controller methods requiring idempotency.
- **Aspect / Filter**: `IdempotencyAspect` or `IdempotencyFilter`:
  1. Inspects incoming `Idempotency-Key` header.
  2. Hashes request body using `MessageDigest.getInstance("SHA-256")`.
  3. Executes atomic insert `IN_PROGRESS` with TTL (e.g. 24 hours).
  4. If duplicate key detected:
     - Check status:
       - `COMPLETED`: compare hashes. If match, return cached response; if mismatch, throw `ConflictException` (HTTP 409).
       - `IN_PROGRESS`: throw `ConflictException` (HTTP 409: Concurrent execution).
  5. If new: proceed with execution, capture response, update status to `COMPLETED` and commit.

### 3. Correlation-ID Interceptors
- **HTTP**: `CorrelationIdFilter` (`OncePerRequestFilter`) binds `X-Correlation-Id` to `MDC("correlationId")`.
- **Outbox**: `OutboxJpaAdapter` automatically injects `correlationId` into event headers JSONB.
- **RabbitMQ**: `RabbitTemplate` message post-processor sets AMQP header `x-correlation-id`.
- **Consumer**: `@RabbitListener` aspect / advice pulls `x-correlation-id` and sets `MDC` before method invocation.
