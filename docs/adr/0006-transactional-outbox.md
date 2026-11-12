# ADR 0006 — Transactional outbox

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

A payment must not be committed without the fact that it happened, and the fact must not be published if the payment rolls back. Publishing to RabbitMQ inside the database transaction holds the transaction open on the network and can still lose the message if the process dies after commit and before ack.

## Decision

`OutboxJpaAdapter.append` is `@Transactional(propagation = Propagation.MANDATORY)` and inserts `outbox_events` in the caller's transaction. `OutboxPort` is a plain interface with no Spring annotation. Create, confirm, and cancel all append from a `@Transactional` handler.

`OutboxRelayService.relayPendingEvents` runs on a schedule when `app.outbox.scheduling.enabled` is true or unset (`SchedulingConfig`). The schedule is a fixed delay of `app.outbox.poll-interval-ms` (default 1000). Each tick handles up to 50 due rows, one claim at a time. The claim locks one unpublished row whose `next_attempt_at` is due (`SELECT … LIMIT 1 … FOR UPDATE SKIP LOCKED`), moves `next_attempt_at` forward by the confirm timeout (default 5 seconds) plus 10 seconds, and commits. The row lock is not held while waiting for the broker.

Each attempt uses a new confirm correlation id (`publisher-confirm-type=correlated`, `publisher-returns=true`, mandatory template). That id is not the outbox id. The outbox id is the `X-Event-Id` header, so a late ack from a timed-out attempt is not applied to the next attempt. `published_at` is set only after the broker acks and does not return the message. `attempts` stays unchanged on success. A nack, return, confirm timeout, or send exception increments `attempts`, stores `last_error` (at most 500 characters), and sets `next_attempt_at` to now plus `min(300, 2^min(attempts, 8) * 2 + jitter)` seconds. Jitter is `ThreadLocalRandom.nextInt(1, 4)` (1, 2, or 3), implemented by `PublishRetryBackoff`. A row that keeps failing is retried forever; the delay stays at 300 seconds. There is no outbox dead-letter table.

`PaymentNotificationConsumer` writes `processed_events (consumer, event_id)` in the same transaction as the notification. The primary key is `(consumer, event_id)` and the consumer name is `PaymentNotificationConsumer`. A second delivery inserts no second notification. All three routing keys share that consumer ([ADR 0005](0005-rabbitmq-vs-kafka.md)).

Guarantee: **at-least-once delivery and idempotent side effects** for `payment.created`, `payment.completed`, and `payment.cancelled`. A crash after the broker accepts a message and before `published_at` is saved publishes a duplicate once the lease expires, which the consumer ignores. An interrupt stops the rest of the tick and leaves the interrupt flag set.

## Consequences

- No dual write. Commit either has both rows or neither.
- Publish lag is visible as rows with `published_at IS NULL`. The relay orders them by `next_attempt_at`. `occurred_at` is stored and is the timestamp behind `payments.outbox.lag.seconds` and `payments.outbox.publish.lag`.
- Relay instances can run side by side. `SKIP LOCKED` covers the claim, and the `next_attempt_at` lease covers the confirm wait, so a second relay does not publish that row until the lease expires.
- Operators inspect `last_error` and `attempts` on `outbox_events`. Broker DLQ behavior is [ADR 0005](0005-rabbitmq-vs-kafka.md). Outbox retries do not stop at the 300-second cap; that cap is the steady delay. The append also stores `traceparent` so the relay can continue the request trace ([architecture](../architecture.md)).

## Alternatives

| Option | Why not |
|---|---|
| Publish then commit | A crash drops the event. A rollback after a successful publish emits a ghost event. |
| Transactional inbox only | Dedupes consumers but does not protect the publish. |
| Change data capture | Valid later. More moving parts than a table we already write. |
