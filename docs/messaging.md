---
title: Messaging
status: active
updated: 2026-11-14
related: [architecture.md, resilience.md, observability.md]
---

# Messaging guarantees

Payments publishes integration events through the transactional outbox. The relay and the notifications consumer together provide **at-least-once delivery with idempotent side effects**. This is not exactly-once delivery.

## Topology

| Piece | Name |
|---|---|
| Topic exchange | `payments.events` |
| Routing keys | `payment.created`, `payment.completed`, `payment.cancelled`, `payment.held` |
| Queue | `payments.created.notifications` (all four keys) |
| Dead-letter exchange | `payments.events.dlx` |
| Dead-letter routing key | `payment.created.dlq` |
| Dead-letter queue | `payments.created.notifications.dlq` |

The queue name and the dead-letter routing key stayed as introduced when the queue only carried `payment.created`. Completed and cancelled events are additional bindings on that same queue, and every notification reject uses the same dead-letter routing key. Existing bindings keep working; the names do not mean only created events are dead-lettered.

## Outbox relay

1. The aggregate transaction inserts `outbox_events` and the business row together (`Propagation.MANDATORY`).
2. `OutboxRelayService` claims up to 50 due rows in one transaction with `SELECT … FOR UPDATE SKIP LOCKED`, moves their `next_attempt_at` forward by the confirm timeout plus 10 seconds with one `UPDATE`, and commits. The row locks are not held while waiting for the broker. Another relay skips those rows until the lease expires, so two relays do not publish them at the same time. A batch holds at most one event per aggregate: a later event of the same aggregate stays unclaimed and goes out in a following batch.
3. The batch is sent without waiting between messages, and the confirms are then awaited together. Each attempt has its own confirm correlation id (`spring.rabbitmq.publisher-confirm-type=correlated`, `publisher-returns=true`, `mandatory=true`), and each message has the full confirm timeout counted from its own send. The outbox id is only the `X-Event-Id` header. A late ack or return from a timed-out attempt cannot be applied to the next attempt.
4. One short transaction sets `published_at` for every row the broker acked and did not return. `attempts` stays unchanged on success. A poll repeats claim, publish, and mark for up to 20 batches while more rows are due.
5. A nack, return, or confirm timeout increments `attempts`, stores `last_error`, and schedules the next attempt with exponential backoff plus 1–3 seconds of jitter, capped at 300 seconds. There is no attempt ceiling: the row stays unpublished until a later attempt succeeds.
6. A crash after the broker accepted the message and before `published_at` commits publishes a duplicate once the lease expires. The consumer treats that as a no-op. A crash during the confirm wait leaves the claimed rows leased, then eligible again. An interrupt stops the poll and leaves the interrupt flag set: rows whose confirm was already read are marked, and rows not yet sent or not yet confirmed keep their lease until it expires.

A failed row is rescheduled on its own; the other rows of its batch are still marked. Ordering is per aggregate inside one relay and best effort across relays: a second instance can publish a later event of an aggregate while the first still waits for the confirm of an earlier one. The consumer does not depend on order.

Confirm correlation is per attempt. Publisher returns must stay enabled; RabbitMQ acks a mandatory message it could not route, and the return is what keeps that row unpublished.

## Consumer

`PaymentNotificationConsumer` stores one notification per `(consumer, event_id)` in `processed_events` in the same transaction as the notification insert. The event id is `X-Event-Id`, or a body `eventId` when the header is absent. `paymentId` is not an event id: created, completed, and cancelled events for one payment must all be stored.

- A duplicate `event_id` is acknowledged and ignored.
- A missing or invalid event id, a missing event type, invalid JSON, a non-object payload, missing identifiers, or an unknown event type is rejected immediately via `AmqpRejectAndDontRequeueException`. The processed-event insert rolls back with the listener transaction, so a later valid delivery of that id is not treated as a duplicate.
- Transient failures (for example a database error) are retried by the listener with exponential backoff and jitter (`app.messaging.listener-*`, overridable with `LISTENER_MAX_ATTEMPTS`, `LISTENER_INITIAL_INTERVAL_MS`, `LISTENER_MULTIPLIER`, `LISTENER_MAX_INTERVAL_MS`). The cap in `LISTENER_MAX_INTERVAL_MS` is a hard cap. This retry advice is on the listener container factory and replaces Boot's listener retry, so `spring.rabbitmq.listener.simple.retry` is not the retry mechanism. Other listener container properties, including `spring.rabbitmq.listener.simple.auto-startup`, still come from Boot. The migration profile sets auto-startup to false and disables outbox scheduling so Flyway can run without consumers.
- After the attempt budget is exhausted the message is rejected without requeue and RabbitMQ routes it to the DLQ. `defaultRequeueRejected` is false.

Headers propagated from the outbox: `X-Correlation-Id`, `X-Event-Id`, `X-Event-Type`, `X-Aggregate-Type`, `X-Aggregate-Id`. Listener observation is enabled so a W3C `traceparent` is added when tracing is on.

## What this does not promise

A message can be delivered more than once. Side effects are safe because of the processed-event primary key. Messages on the DLQ stay there until an operator inspects them; there is no automatic replay. An unpublished outbox row is retried indefinitely with the delay capped at 300 seconds.

## Eval

```bash
rtk mvn -q -Dtest=OutboxRelayConfirmTest,OutboxRelayBatchTest test
rtk mvn -B -Dit.test=MessagingNotificationsIT test-compile failsafe:integration-test failsafe:verify
```

Expected result: outbox publication, retry, deduplication, and notification delivery preserve at-least-once semantics.
