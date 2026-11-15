---
name: add-integration-event
description: Wire an integration event through transactional outbox, batched relay, RabbitMQ routing and an idempotent consumer with real broker tests.
argument-hint: "<module> <event-name>"
---

# add-integration-event

Read docs/messaging.md and the current OutboxRelayService before changing the module/event from the request.

1. Model the event as a pure domain record; expose only consumer-required fields. Serialize in the application mapper. Append through OutboxPort in the aggregate change transaction (MANDATORY).
2. Keep slow I/O and CPU work outside database transactions. Follow OffloadedPasswordEncoder for CPU offloading; preserve the relay's batched claim and lease semantics.
3. Add explicit resolveRoutingKey mapping and a durable queue binding. A consumer owns its queue; the CRUD audit queue expires messages after 24 hours and is not a durable business consumer.
4. Consumer processes idempotently using the existing processed-event convention; validate retry/DLQ behavior. No direct access to another module's internal repository and no cross-module foreign key.
5. Prove rollback atomicity, routing, broker confirmation, deduplication and failure behavior with real Testcontainers. Run local-gate and docs-sync; do not label a mock publish as broker proof.
