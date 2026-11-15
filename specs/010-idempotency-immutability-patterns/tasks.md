# Tasks: Idempotency, Correlation & Immutability Patterns

- [x] Create Flyway migration including `idempotency_keys` table in `V1__shared_outbox_inbox.sql`.
- [x] Implement `CorrelationIdFilter` with validation and MDC binding.
- [x] Implement outbox header enrichment with `correlationId` in `OutboxJpaAdapter`.
- [x] Ensure domain events and value objects are immutable Java records.
- [x] Implement idempotency state storage behind a port (PR3 - Payments). Delivered as `shared.application.IdempotencyPort`, implemented by `IdempotencyService` over `JdbcIdempotencyRepository` (JDBC, not JPA).
- [x] Verify the request hash and replay stored responses (PR3 - Payments). Delivered as explicit `IdempotencyPort` calls from the create-payment use case; no `@Idempotent` aspect/interceptor was added.
- [x] Implement AMQP message header propagation for the correlation id (PR5 - Messaging). `OutboxRelayService` sets `X-Correlation-Id` on published messages; `PaymentNotificationConsumer` reads it into `CorrelationContext`.
- [x] Implement inbox atomic deduplication in RabbitMQ consumer using `processed_events` (PR5 - Messaging). `PaymentNotificationConsumer` uses `ProcessedEventStore` (`JdbcProcessedEventStore`).
- [x] Add integration tests for in-flight key conflicts and payload mismatch (PR3 - Payments). `IdempotencyAndAccessIT` (in-flight `PROCESSING` key returns 409, lease ownership) and `PaymentsIT` (same key, different body returns 409).
