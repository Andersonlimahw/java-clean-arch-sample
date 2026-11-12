# ADR 0006 — Transactional outbox

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

A payment must not be committed without the fact that it happened, and the fact must not be published if the payment rolls back. Publishing to RabbitMQ inside the database transaction holds the transaction open on the network and can still lose the message if the process dies after commit and before ack.

## Decision

`OutboxJpaAdapter.append` is `@Transactional(propagation = Propagation.MANDATORY)` and inserts `outbox_events` in the caller's transaction. `OutboxPort` is a plain interface with no Spring annotation. Create, confirm, and cancel all append from a `@Transactional` handler.

`OutboxRelayService.relayPendingEvents` runs on a schedule when `app.outbox.scheduling.enabled` is true or unset (`SchedulingConfig`). The delay is `app.outbox.poll-interval-ms` (default 1000). Each tick locks up to 50 unpublished rows whose `next_attempt_at` is due, using `FOR UPDATE SKIP LOCKED`, publishes, and sets `published_at` when `RabbitTemplate.convertAndSend` returns. The template does not wait for the correlated publisher confirm.

When send throws, `attempts` increases by one, `last_error` stores the message, and `next_attempt_at` becomes now plus `min(300, 2^attempts * 2 + jitter)` seconds. Jitter is `ThreadLocalRandom.nextInt(1, 4)` (1, 2, or 3). A row that keeps failing is retried forever; the delay stays at 300 seconds. There is no outbox dead-letter table.

`PaymentNotificationConsumer` writes `processed_events (consumer, event_id)` in the same transaction as the notification. The primary key is `(consumer, event_id)` and the consumer name is `PaymentNotificationConsumer`. A second delivery inserts no second notification. Only `payment.created` has a consumer ([ADR 0005](0005-rabbitmq-vs-kafka.md)).

Guarantee: **at-least-once delivery and idempotent side effects** for `payment.created`. A crash after the broker accepts a message and before `published_at` is saved publishes a duplicate, which the consumer ignores. A confirm or cancel event can be marked published and still have no queue.

## Consequences

- No dual write. Commit either has both rows or neither.
- Publish lag is visible as rows with `published_at IS NULL`, ordered by `next_attempt_at` (the column the relay queries). `occurred_at` is stored and is not the poll order.
- Relay instances can run side by side because of `SKIP LOCKED`.
- Operators inspect `last_error` and `attempts` on `outbox_events`. Broker DLQ behavior is [ADR 0005](0005-rabbitmq-vs-kafka.md). Outbox retries do not stop at the 300-second cap; that cap is the steady delay.

## Alternatives

| Option | Why not |
|---|---|
| Publish then commit | A crash drops the event. A rollback after a successful publish emits a ghost event. |
| Transactional inbox only | Dedupes consumers but does not protect the publish. |
| Change data capture | Valid later. More moving parts than a table we already write. |
