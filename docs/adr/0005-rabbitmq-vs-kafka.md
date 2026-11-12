# ADR 0005 — RabbitMQ instead of Kafka

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

After a payment commits, one consumer must create a notification. Volume is a single application's outbox, not a multi-tenant log with many independent consumer groups. We need a dead-letter queue, retries, and routing by event type (`payment.created`, `payment.completed`, `payment.cancelled`).

## Decision

Use RabbitMQ 3.13 with a topic exchange `payments.events`, one durable notifications queue, and a dead-letter exchange `payments.events.dlx`.

The broker is a transport. PostgreSQL remains the source of truth via the outbox. We do not use the broker as the ledger.

## Consequences

- Competing consumers, per-message ack, and DLQ are built in.
- Ordering is per queue, not a partitioned log. A confirm can theoretically be delivered before its create if they race across connections. Notification types are independent rows, so the user can see both.
- Ops must run RabbitMQ (or a managed equivalent) beside PostgreSQL and Redis.
- Switching to Kafka later means a new relay publisher. The outbox table does not change.

## Alternatives

| Option | Why not |
|---|---|
| Kafka | Strong log retention and replay, at the cost of partitions, consumer-group ops, and a heavier local stack. Nothing here needs long retention in the broker; the outbox and `notifications` tables already retain the facts. |
| Database polling by the notifications module | Couples notifications to the payments tables and skips the explicit event contract. |
| Spring application events only | In-process events die with the process and do not survive the gap between commit and a listener. |
