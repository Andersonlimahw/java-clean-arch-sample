# Tasks: Idempotency, Correlation & Immutability Patterns

- [x] Create Flyway migration including `idempotency_keys` table in `V1__shared_outbox_inbox.sql`.
- [x] Implement `CorrelationIdFilter` with validation and MDC binding.
- [x] Implement outbox header enrichment with `correlationId` in `OutboxJpaAdapter`.
- [x] Ensure domain events and value objects are immutable Java records.
- [ ] Implement `IdempotencyKeyPort` and JPA adapter for idempotency state storage (PR3 - Payments).
- [ ] Implement `@Idempotent` aspect / interceptor caching responses and verifying request hash (PR3 - Payments).
- [ ] Implement AMQP message header propagation for `x-correlation-id` (PR5 - Messaging).
- [ ] Implement inbox atomic deduplication in RabbitMQ consumer using `processed_events` (PR5 - Messaging).
- [ ] Add integration tests verifying concurrent idempotency key lock and mismatch payload detection (PR3 - Payments).
