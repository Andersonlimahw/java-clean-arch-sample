# ADR 0007 — Eventual consistency for notifications

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The client that creates a payment receives `201` as soon as PostgreSQL commits. The notification row is created only after the relay publishes and the consumer handles the message. Those two moments are not the same.

## Decision

The payment row in PostgreSQL is strongly consistent. A caller that just received `201` can `GET` that new id and see it, because create does not leave an older status in Redis. Confirm and cancel delete the cache entry after commit ([ADR 0008](0008-redis-cache-invalidation.md)). If that delete fails while Redis still answers reads, the cached status can remain until the TTL (default 10 minutes). If Redis is down, the GET reads PostgreSQL.

Notifications for `payment.created` are eventually consistent. `GET /api/v1/notifications` can be empty for a short time after create. The UI, if any, polls or refreshes. HTTP create returns after the database commit and does not wait for the broker. Confirm and cancel append outbox rows and do not produce notification rows, because those routing keys have no queue ([ADR 0005](0005-rabbitmq-vs-kafka.md)).

`Idempotency-Key` on create is strongly consistent inside PostgreSQL. The same user, key, and body replay the stored response with header `Idempotent-Replayed: true`. A different body, or a key whose row is still `PROCESSING`, returns 409. Keys are scoped by `user_id`, limited to 120 characters, and stored for 24 hours.

Optimistic locking on `payments.version` turns a concurrent confirm or cancel into 409. The client retries the read.

## Consequences

- Create latency does not include RabbitMQ.
- A notification can arrive seconds later if the broker is slow. The outbox holds the event until then.
- Support should look at `outbox_events.published_at` before assuming the consumer lost an event.
- We do not offer a read-your-notification-immediately guarantee.

## Alternatives

| Option | Why not |
|---|---|
| Create the notification in the payment transaction | Notifications would depend on the payments transaction and could not be retried independently. It also hides the event boundary we want to keep. |
| Synchronous RPC from payments to notifications | A notifications outage would fail payment creation. |
