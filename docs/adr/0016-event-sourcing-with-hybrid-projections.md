# ADR 0016 — Event sourcing with hybrid projections

- **Status:** Accepted
- **Date:** 2026-11-15
- **Supersedes:** [ADR 0004](0004-cqrs-without-event-sourcing.md)

## Context

The sample needs replayable aggregate history and a payment history read model while retaining its current HTTP and RabbitMQ contracts. The existing PostgreSQL database, Flyway migrations, transactional outbox, and modular boundaries are already part of the project.

## Decision

Use PostgreSQL as the append-only event store and keep relational tables as read models. Move one aggregate per implementation increment, preserving a green `main` after each merge.

- Store events with stable registered event names, explicit schema versions, JSON payloads, metadata, global positions, and contiguous stream versions. A database trigger rejects `UPDATE` and `DELETE` on `event_store`.
- Keep domain event records and aggregate replay free of Spring, JPA, Jackson, and database dependencies. Put event registration, JSON serialization, upcasters, JDBC storage, and snapshots in the shared application/infrastructure boundaries.
- Project `payments`, `users`, and `refresh_tokens` synchronously in the append transaction. Project `notifications` and `payment_history` asynchronously with durable checkpoints.
- Keep `outbox_events` as the integration-event boundary. Event-store facts and published payloads are separate records in the same transaction; existing RabbitMQ routing keys and payloads remain unchanged.
- Use snapshots only when stream growth warrants them. A snapshot is disposable, versioned, and ignored when its schema version is stale.
- Treat databases as new for this rollout. Consolidate the current V1–V7 schema into V1–V4, do not backfill, and reset local development databases with `scripts/db-reset.sh`.

## Consequences

- Aggregate state can be rebuilt from its event stream; projections can be rebuilt independently.
- A unique `(stream_type, stream_id, stream_version)` constraint turns stale concurrent appends into the existing conflict response.
- Event names and payload schemas become durable contracts. Renames require a new stable event type; compatible schema changes need tested upcasters.
- The event stream can retain personal data, including email. The sample has no erasure flow; a later decision must address crypto-shredding before production use.
- The local migration rewrite is intentionally incompatible with existing Flyway history and has no production cutover strategy.

## Alternatives

| Option | Why not |
|---|---|
| EventStoreDB or Kafka as the event store | Adds another stateful service without a need that PostgreSQL cannot meet for this sample. |
| Replace the outbox with event-store publishing | Couples internal replay storage to the external integration contract and loses the existing retry and publisher-confirm behavior. |
| Make every projection asynchronous | Breaks read-your-writes and the uniqueness/race backstops for payments and identity. |

## Eval

```bash
rtk proxy bash scripts/ci-local.sh
```

Expected result: the event-store kernel tests, migration tests, architecture checks, and existing unit/integration suites pass on a clean PostgreSQL 16 Testcontainer.
