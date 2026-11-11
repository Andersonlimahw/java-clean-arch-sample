# Architecture Plan: PR3 — Payments Module & Idempotency Engine

## 1. Domain Layer (`com.muse.payments.payments.domain`)
- **Aggregates**:
  - `Payment` extends `AggregateRoot<UUID>`:
    - Fields: `UUID id`, `UUID userId`, `Money money`, `String description`, `PaymentStatus status`, `Instant createdAt`, `Instant updatedAt`, `Long version`.
    - Lifecycle methods:
      - `static Payment create(UUID userId, Money money, String description)` -> records `PaymentCreatedEvent`.
      - `void complete()` -> validates `status == PENDING`, updates `status = COMPLETED`, records `PaymentCompletedEvent`. Throws `ConflictException` if invalid.
      - `void cancel(String reason)` -> validates `status == PENDING`, updates `status = CANCELLED`, records `PaymentCancelledEvent`. Throws `ConflictException` if invalid.
- **Value Objects**:
  - `Money(BigDecimal amount, Currency currency)`: Invariants: `amount != null && amount.scale() <= 2 && amount.compareTo(BigDecimal.ZERO) > 0`, `currency != null`.
  - `PaymentStatus`: `PENDING`, `COMPLETED`, `CANCELLED`.
- **Domain Events**:
  - `PaymentCreatedEvent(UUID eventId, UUID paymentId, UUID userId, BigDecimal amount, String currency, Instant occurredAt)`
  - `PaymentCompletedEvent(UUID eventId, UUID paymentId, UUID userId, Instant occurredAt)`
  - `PaymentCancelledEvent(UUID eventId, UUID paymentId, UUID userId, String reason, Instant occurredAt)`
- **Ports**:
  - `PaymentRepository`: `Payment save(Payment payment)`, `Optional<Payment> findById(UUID id)`, `Page<Payment> findByUserId(UUID userId, Pageable pageable)`.

## 2. Idempotency Engine (`com.muse.payments.shared.idempotency` or `payments.infrastructure.idempotency`)
- **Component**: `IdempotencyService`:
  - Table: `idempotency_keys` (`key`, `user_id`, `request_hash`, `status`, `response_code`, `response_body`, `created_at`, `expires_at`).
  - Request hashing: `SHA-256(canonicalJsonPayload)`.
  - Algorithm:
    1. Look up existing `(user_id, key)`:
       - If exists and status == `COMPLETED`:
         - If `request_hash` matches: return `CachedResponse(code, body)`.
         - If `request_hash` differs: throw `ConflictException("Idempotency key payload mismatch")`.
       - If exists and status == `PROCESSING`:
         - Throw `ConflictException("Concurrent request in progress for this idempotency key")`.
    2. Insert record with status `PROCESSING`.
    3. Execute business logic (inside transaction).
    4. Upon successful completion, update record to `COMPLETED` with `response_code = 201` and `response_body`.
- **Aspect / Interceptor / Handler**:
  - Encapsulated at application/web boundary.

## 3. Application Layer (`com.muse.payments.payments.application`)
- **Commands**:
  - `CreatePaymentCommand(UUID userId, BigDecimal amount, String currency, String description, String idempotencyKey)`
  - `ConfirmPaymentCommand(UUID paymentId, UUID userId)`
  - `CancelPaymentCommand(UUID paymentId, UUID userId, String reason)`
- **Queries**:
  - `GetPaymentByIdQuery(UUID paymentId, UUID userId)`
  - `ListPaymentsQuery(UUID userId, int page, int size)`
- **Handlers**:
  - `CreatePaymentCommandHandler`: Invokes domain, persists to repository, publishes domain events to `OutboxPort`, updates idempotency store.
  - `ConfirmPaymentCommandHandler`: Retrieves payment, verifies ownership, calls `complete()`, saves (verifying optimistic lock), publishes outbox event.
  - `CancelPaymentCommandHandler`: Retrieves payment, verifies ownership, calls `cancel()`, saves, publishes outbox event.

## 4. Infrastructure & Database Schema
- Migration: `V3__payments.sql`:
  - `payments` table with `UUID id PRIMARY KEY`, `user_id UUID NOT NULL`, `amount NUMERIC(19, 2) NOT NULL`, `currency VARCHAR(3) NOT NULL`, `description VARCHAR(255)`, `status VARCHAR(30) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`, `version BIGINT NOT NULL DEFAULT 0`.
  - Indexes on `(user_id, status)` and `(created_at)`.
- Adapters:
  - `PaymentJpaEntity`, `PaymentJpaRepository`, `PaymentJpaAdapter`.
  - JPA `@Version` on `Long version`.

## 5. Web Layer
- `PaymentController`:
  - `@RestController @RequestMapping("/api/v1/payments")`.
  - Standard headers: `X-Correlation-Id`, `Idempotent-Replayed` (when replaying).
  - RFC 7807 for any errors.
