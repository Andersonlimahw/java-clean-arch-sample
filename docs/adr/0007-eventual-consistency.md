# ADR 0007 — Eventual consistency for notifications

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The client that creates a payment receives `201` as soon as PostgreSQL commits. The notification row is created only after the relay publishes and the consumer handles the message. Those two moments are not the same.

## Decision

The payment row in PostgreSQL is strongly consistent. A caller that just received `201` can `GET` that new id and see it, because create does not leave an older status in Redis. Confirm and cancel delete the cache entry after commit ([ADR 0008](0008-redis-cache-invalidation.md)). If that delete fails while Redis still answers reads, the cached status can remain until the TTL (default 10 minutes). If Redis is down, the GET reads PostgreSQL.

Notifications for `payment.created`, `payment.completed`, and `payment.cancelled` are eventually consistent. `GET /api/v1/notifications` can omit the new row for a short time after the HTTP command returns. The UI, if any, polls or refreshes. Create, confirm, and cancel return after the database commit and do not wait for the broker. Each of those commands appends an outbox row, and the consumer writes the matching notification after it handles the message ([ADR 0005](0005-rabbitmq-vs-kafka.md)).

`Idempotency-Key` on create is strongly consistent inside PostgreSQL. The same user, key, and body replay the stored response with header `Idempotent-Replayed: true`. A different body, or a key whose row is still `PROCESSING`, returns 409. Keys are scoped by `user_id` and limited to 120 characters. `expires_at` is set 24 hours ahead. `JdbcIdempotencyRepository.find` does not compare `expires_at`, and no job deletes expired rows, so the stored response still replays after that time.

Optimistic locking on `payments.version` turns overlapping confirm or cancel transactions into 409 `urn:problem-type:concurrent-modification`. There is no `ETag` or `If-Match`; the check covers only the read-modify-write inside the transaction. A request that starts after the winner commits hits the state machine (`urn:problem-type:409`). Deadlock and serialization failure are 503 `urn:problem-type:transient-lock` with `Retry-After`.

## Consequences

- Create, confirm, and cancel latency does not include RabbitMQ.
- A notification can arrive seconds later if the broker or the confirm wait is slow. The outbox holds the event until a publisher confirm succeeds.
- Support should look at `outbox_events.published_at` before assuming the consumer lost an event.
- We do not offer a read-your-notification-immediately guarantee.

## Alternatives

| Option | Why not |
|---|---|
| Create the notification in the payment transaction | Notifications would depend on the payments transaction and could not be retried independently. It also hides the event boundary we want to keep. |
| Synchronous RPC from payments to notifications | A notifications outage would fail payment creation. |
