# ADR 0006 — Transactional outbox

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

A payment must not be committed without the fact that it happened, and the fact must not be published if the payment rolls back. Publishing to RabbitMQ inside the database transaction holds the transaction open on the network and can still lose the message if the process dies after commit and before ack.

## Decision

`OutboxPort.append` requires an existing transaction (`Propagation.MANDATORY`). The JPA adapter inserts `outbox_events` in that same transaction as the payment.

A scheduled relay reads unpublished rows with `FOR UPDATE SKIP LOCKED`, publishes, and sets `published_at`. Failures increment `attempts` and set `next_attempt_at` with exponential backoff and jitter, capped at 300 seconds.

The consumer writes `processed_events (consumer, event_id)` in the same transaction as the notification. The primary key makes a second delivery a no-op.

Guarantee: **at-least-once delivery and idempotent side effects**. Not exactly-once. A crash after the broker accepts a message and before `published_at` is saved publishes a duplicate, which the consumer ignores.

## Consequences

- No dual write. Commit either has both rows or neither.
- Publish lag is visible as unpublished rows (`published_at IS NULL`, oldest `occurred_at`).
- Relay instances can run side by side because of `SKIP LOCKED`.
- Operators inspect `last_error` and the DLQ. There is no automatic infinite poison-message loop on the outbox; backoff grows and then sits at five minutes.

## Alternatives

| Option | Why not |
|---|---|
| Publish then commit | A crash drops the event. A rollback after a successful publish emits a ghost event. |
| Transactional inbox only | Dedupes consumers but does not protect the publish. |
| Change data capture | Valid later. More moving parts than a table we already write. |
