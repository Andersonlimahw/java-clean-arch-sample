# ADR 0005 — RabbitMQ instead of Kafka

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

After a payment is created, one consumer must create a notification. Volume is a single application's outbox, not a multi-tenant log with many independent consumer groups. Publish retries live in the outbox relay ([ADR 0006](0006-transactional-outbox.md)). The broker needs a route per event type the relay emits: `payment.created`, `payment.completed`, and `payment.cancelled`.

## Decision

Use RabbitMQ 3.13. `RabbitMqConfig` declares:

- topic exchange `payments.events` (durable)
- direct dead-letter exchange `payments.events.dlx` (durable)
- durable queue `payments.created.notifications`, bound to `payments.events` with routing key `payment.created`, and arguments `x-dead-letter-exchange=payments.events.dlx`, `x-dead-letter-routing-key=payment.created.dlq`
- durable queue `payments.created.notifications.dlq`, bound to `payments.events.dlx` with routing key `payment.created.dlq`

`OutboxRelayService` also publishes `payment.completed` and `payment.cancelled`. No queue is bound to those keys, so confirm and cancel events leave the exchange with no consumer. `PaymentNotificationConsumer` listens only to `payments.created.notifications`.

`RabbitMqConfig` leaves `defaultRequeueRejected` at the container default. Processing failures are rethrown as `RuntimeException`, which Spring's default error handler requeues onto `payments.created.notifications`. The queue sets no message TTL and no max length. The DLQ receives a message when the broker dead-letters it (reject with requeue false, TTL, or length). Spring does that reject for conversion and argument-resolution failures. This listener's arguments are a `String` plus optional headers, and JSON failures become `RuntimeException`, so they are requeued. A payload that parses but is missing `paymentId`, `userId`, `amount`, or `currency` is acknowledged and discarded after the event id is stored in `processed_events`.

The broker is a transport. PostgreSQL remains the source of truth via the outbox. The broker is not the ledger.

## Consequences

- Competing consumers and per-message ack are available on `payments.created.notifications`. The DLQ is declared and bound; the current listener path requeues failures instead of dead-lettering them.
- Ordering is per queue, not a partitioned log. Confirm and cancel are not delivered to this queue, so there is no notification row for those event types.
- Ops must run RabbitMQ (or a managed equivalent) beside PostgreSQL and Redis.
- Switching to Kafka later means a new relay publisher. The outbox table does not change.

## Alternatives

| Option | Why not |
|---|---|
| Kafka | Strong log retention and replay, at the cost of partitions, consumer-group ops, and a heavier local stack. Nothing here needs long retention in the broker; the outbox and `notifications` tables already retain the facts. |
| Database polling by the notifications module | Couples notifications to the payments tables and skips the explicit event contract. |
| Spring application events only | In-process events die with the process and do not survive the gap between commit and a listener. |
