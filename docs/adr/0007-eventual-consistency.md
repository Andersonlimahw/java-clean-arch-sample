# ADR 0007 — Eventual consistency for notifications

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The client that creates a payment receives `201` as soon as PostgreSQL commits. The notification row is created only after the relay publishes and the consumer handles the message. Those two moments are not the same.

## Decision

Payment state is strongly consistent in PostgreSQL: the caller that just got `201` can `GET` the payment and see it.

Notifications are eventually consistent. `GET /api/v1/notifications` can be empty for a short time after create. The UI, if any, polls or refreshes. We do not block the HTTP create on the broker.

Idempotency-Key on create is strongly consistent: the same key and body replay the stored response; a different body returns 409.

Optimistic locking on `payments.version` turns a concurrent confirm/cancel into 409. The client retries the read.

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
