# ADR 0004 — CQRS without event sourcing

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Payment commands (`create`, `confirm`, `cancel`) enforce a state machine and optimistic locking. Reads (`get by id`, `list mine`) are owned by the caller and, for get-by-id, cached. The write model and the read model do not need the same shape, but they do need to be easy to debug in PostgreSQL.

## Decision

Use CQRS as a code boundary, not as event sourcing.

- Payment commands are `*Command` + `*CommandHandler`. The handler enforces invariants, saves the current row, and appends integration events. Identity commands use the same handler types and do not write the outbox.
- Payment queries are `*Query` + `*QueryHandler` and return records (`PaymentResponse`). Identity has commands only; `RegisterCommandHandler` returns `UserResponse`, and login and refresh return `AuthTokensResponse`.
- `GET /api/v1/notifications` is the exception: `NotificationController` calls `NotificationRepository` and returns the domain type `Notification`. That module has no `application` package.
- The `payments` table stores the current state. `version` is the optimistic lock.
- Domain events are transient inside the aggregate and are translated to outbox rows before the transaction commits. They are not the system of record.

## Consequences

- A row in `payments` is enough to answer "what is the status?". No replay.
- Handlers stay explicit. There is no command bus framework.
- The read side may cache or project without changing the write transaction.
- We do not get free temporal queries. History, if needed later, is a new table, not a rebuild from events.

## Alternatives

| Option | Why not |
|---|---|
| Event sourcing | Rebuilds every payment from events, complicates idempotency and optimistic locking, and the product does not need a full audit stream as the source of truth. The outbox records integration events the application intended to publish, including rows still waiting (`published_at` null). |
| One service method that both mutates and returns entities | Leaks persistence into the API and makes the cache path share the write model. |
