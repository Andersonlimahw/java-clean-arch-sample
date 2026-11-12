# ADR 0005 — RabbitMQ instead of Kafka

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

After a payment is created, confirmed, or cancelled, one consumer must create a notification. Volume is a single application's outbox, not a multi-tenant log with many independent consumer groups. Publish retries live in the outbox relay ([ADR 0006](0006-transactional-outbox.md)). The broker needs a route per event type the relay emits: `payment.created`, `payment.completed`, and `payment.cancelled`.

## Decision

Use RabbitMQ 3.13. `RabbitMqConfig` declares:

- topic exchange `payments.events` (durable)
- direct dead-letter exchange `payments.events.dlx` (durable)
- durable queue `payments.created.notifications`, bound to `payments.events` with routing keys `payment.created`, `payment.completed`, and `payment.cancelled`, and arguments `x-dead-letter-exchange=payments.events.dlx`, `x-dead-letter-routing-key=payment.created.dlq`
- durable queue `payments.created.notifications.dlq`, bound to `payments.events.dlx` with routing key `payment.created.dlq`

The queue name and the dead-letter routing key stayed from the created-only topology. Completed and cancelled are additional bindings, and every notification reject uses that same dead-letter key. `PaymentNotificationConsumer` listens on `payments.created.notifications` and creates a notification for `PaymentCreated`, `PaymentCompleted`, and `PaymentCancelled`.

`defaultRequeueRejected` is false. Permanent failures (`AmqpRejectAndDontRequeueException`: bad JSON, missing or invalid event id, missing event type, missing or invalid identifiers, unknown event type) skip the listener retry budget and are rejected without requeue. Transient failures are retried by the advice chain on the listener container factory (`app.messaging.listener-*`: default 5 attempts, initial interval 500 ms, multiplier 2, max interval 10 seconds, exponential backoff with jitter). After that budget, `RejectAndDontRequeueRecoverer` rejects without requeue. RabbitMQ then routes the message to the DLQ. The queue sets no message TTL and no max length. DLQ messages stay there until an operator inspects them. `spring.rabbitmq.listener.simple.retry` does not configure this chain.

The broker is a transport. PostgreSQL remains the source of truth via the outbox. The broker is not the ledger.

## Consequences

- Competing consumers and per-message ack are available on `payments.created.notifications`. Transient failures are retried, then dead-lettered. Permanent failures are dead-lettered without that retry budget.
- Ordering is per queue, not a partitioned log. Created, completed, and cancelled events share this queue, so each produces a notification row when the consumer accepts it.
- Ops must run RabbitMQ (or a managed equivalent) beside PostgreSQL and Redis.
- Switching to Kafka later means a new relay publisher. The outbox table does not change.

## Alternatives

| Option | Why not |
|---|---|
| Kafka | Strong log retention and replay, at the cost of partitions, consumer-group ops, and a heavier local stack. Nothing here needs long retention in the broker; the outbox and `notifications` tables already retain the facts. |
| Database polling by the notifications module | Couples notifications to the payments tables and skips the explicit event contract. |
| Spring application events only | In-process events die with the process and do not survive the gap between commit and a listener. |
