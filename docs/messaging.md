# Messaging guarantees

Payments publishes integration events through the transactional outbox. The relay and the notifications consumer together provide **at-least-once delivery with idempotent side effects**. This is not exactly-once delivery.

## Topology

| Piece | Name |
|---|---|
| Topic exchange | `payments.events` |
| Routing keys | `payment.created`, `payment.completed`, `payment.cancelled` |
| Queue | `payments.created.notifications` (all three keys) |
| Dead-letter exchange | `payments.events.dlx` |
| Dead-letter queue | `payments.created.notifications.dlq` |

The queue name stayed as introduced in the merged messaging PR so existing bindings and tests keep working. Completed and cancelled events are additional bindings on that queue.

## Outbox relay

1. The aggregate transaction inserts `outbox_events` and the business row together (`Propagation.MANDATORY`).
2. `OutboxRelayService` claims a batch with `SELECT … FOR UPDATE SKIP LOCKED`.
3. Each row is published with correlated publisher confirms (`spring.rabbitmq.publisher-confirm-type=correlated`) and `mandatory=true`.
4. `published_at` is set only after the broker acks and does not return the message.
5. A nack, return, or confirm timeout increments `attempts`, stores `last_error`, and schedules the next attempt with exponential backoff plus 1–3 seconds of jitter, capped at 300 seconds.
6. The row stays unpublished until a later attempt succeeds, so a crash between commit and ack cannot drop the event. A timeout after the broker actually accepted the message can publish a duplicate; the consumer treats that as a no-op.

## Consumer

`PaymentNotificationConsumer` stores one notification per `(consumer, event_id)` in `processed_events` in the same transaction as the notification insert.

- A duplicate `event_id` is acknowledged and ignored.
- Transient failures (for example a database error) are retried by the listener with exponential backoff and jitter (`app.messaging.listener-*`, overridable with `LISTENER_MAX_ATTEMPTS`, `LISTENER_INITIAL_INTERVAL_MS`, `LISTENER_MULTIPLIER`, `LISTENER_MAX_INTERVAL_MS`).
- After the attempt budget is exhausted the message is rejected without requeue and RabbitMQ routes it to the DLQ.
- Permanent failures (invalid JSON, missing identifiers, unknown event type) are rejected immediately via `AmqpRejectAndDontRequeueException` and land on the DLQ without a retry storm.

Headers propagated from the outbox: `X-Correlation-Id`, `X-Event-Id`, `X-Event-Type`, `X-Aggregate-Type`, `X-Aggregate-Id`. Listener observation is enabled so a W3C `traceparent` is added when tracing is on.

## What this does not promise

A message can be delivered more than once. Side effects are safe because of the processed-event primary key. Messages on the DLQ stay there until an operator inspects them; there is no automatic replay.
