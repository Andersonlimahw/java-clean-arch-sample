# Plan: 012 — Event Sourcing

## Principles

- Increment 0 is delivered; owner update (2026-11-15): increments 1–6 ship together in one implementation PR. Preserve the public contract except the history endpoint. Final ancillary corrections may be committed directly to main as authorized, after local validation.
- Domain stays pure: events are records in `<module>.domain`; serialization, registry, and upcasters live in `shared.infrastructure.eventstore`.
- No new framework (Axon, EventStoreDB). PostgreSQL + JDBC, as the outbox and idempotency store already do.

## Increment 0 — Kernel and migration rewrite

1. **Migrations (D3)**: replace `V1`–`V7` and `V6__payments_list_index.sql.conf` with consolidated `V1__shared.sql`, `V2__identity.sql`, `V3__payments.sql`, `V4__notifications.sql` (spec §8). Keep current table shapes for `payments`, `users`, `refresh_tokens`, `notifications` so increments 1–5 can switch writers without schema churn. Add `event_store`, `snapshots`, `projection_checkpoints`, and a `BEFORE UPDATE OR DELETE` trigger on `event_store` that raises.
2. **Domain kernel (`shared.domain`)**: `EventSourcedAggregate<ID>` with `version`, `uncommittedEvents`, protected `raise(event)` (= apply + buffer), abstract `apply(DomainEvent)`, and static `rehydrate` via a fold. `DomainEvent` unchanged.
3. **Application ports (`shared.application`)**: `EventStore` (`append(StreamRef, long expectedVersion, List<DomainEvent>, EventMetadata)`, `load(StreamRef)`, `loadAfter(StreamRef, long version)`), `SnapshotStore`, `SyncProjector<E>` (`MANDATORY` propagation contract).
4. **Infrastructure (`shared.infrastructure.eventstore`)**: `JdbcEventStore` (batch insert, unique violation → `ConflictException`), `EventTypeRegistry` (stable name ↔ record class + current schema version), `EventSerializer` (Jackson, kept out of the domain), `Upcaster` chain, `JdbcSnapshotStore`. Metadata carries `CorrelationContext` id, `traceparent`, and actor id.
5. **ADR 0016** "Event sourcing with hybrid projections" superseding 0004; update the ADR index, and set ADR 0004's status to superseded (status line only).
6. **ArchUnit**: event records in `..domain..` with no Jackson/Spring; only `..infrastructure.eventstore..` touches `event_store`; `event_type` values come from the registry.
7. **Docs**: `docs/data.md` (tables, reset note), `docs/architecture.md` (event flow diagram), `llms.txt` regenerated.

## Increment 1 — Payment

1. Convert `Payment` to `EventSourcedAggregate<UUID>`; add `PaymentInitiated`, `PaymentReleasedFromReview`, `PaymentRejectedInReview`; keep existing event records where the name matches the spec, renaming via the registry before any event is persisted.
2. `EventSourcedPaymentRepository` (implements `PaymentRepository.save/findById`) → `EventStore`; query methods move to a `PaymentReadRepository` over the `payments` projection used by the query handlers.
3. `PaymentProjector` (sync) upserts `payments` from each event; `version` = `stream_version - 1` (keeps the 0-based API value).
4. Handlers: `ScreenedPaymentRecorder`, `Confirm`, `Cancel`, `Review` append through the repository; outbox mapping unchanged (`PaymentReleasedFromReview` → `PaymentCreated`).
5. Tests: replay = projection IT, concurrency IT, all existing payment ITs unchanged.

## Increment 2 — Async runtime and payment history

1. `AsyncProjectionRunner` (scheduled, per projection) with the `xid8` visibility query, checkpoint lock `FOR UPDATE SKIP LOCKED`, batch size and interval properties, and `projection.lag` gauge. Disabled under `APP_MIGRATION_ONLY`.
2. `PaymentHistoryProjector` → `payment_history (payment_id, stream_version, event_type, status_after, actor_id, reason, occurred_at, PK(payment_id, stream_version))`.
3. `GetPaymentHistoryQuery` + handler (same visibility as get-by-id) + controller method; OpenAPI and Postman updated.
4. `ProjectionRebuildMode` (`APP_PROJECTION_REBUILD=<name>`), modelled on `MigrationOnlyMode`.
5. Tests: interleaved-transaction IT, restart/resume IT, rebuild IT, endpoint authorization IT.

## Increment 3 — User

1. `User` → `EventSourcedAggregate`; `UserRegistered`, `UserDisabled`.
2. `user_credentials (user_id PK, password_hash, updated_at)` written by `RegisterCommandHandler` in the same transaction; `LoginCommandHandler` reads `users` projection + credentials.
3. `UserProjector` (sync); keep the `existsByEmail` pre-check; a concurrent duplicate hits the projection unique index → existing `DataIntegrityViolationException` 409 mapping and the events roll back.
4. Tests: no hash in `event_store` payloads; `IdentityIT` and `LoginPerformanceIT` unchanged.

## Increment 4 — Refresh-token family

1. `RefreshTokenFamily` aggregate (stream per family) replacing per-token `RefreshToken` writes; events per spec §5.
2. Reuse path appends `FamilyRevoked` in a `REQUIRES_NEW` transaction (same as today's `revokeFamily`).
3. `RefreshTokenProjector` (sync) maintains `refresh_tokens` for hash lookup.
4. Enable snapshots for `RefreshTokenFamily` with `app.event-store.snapshot-every`.
5. Tests: reuse survives 401, snapshot equivalence, login p95 budget.

## Increment 5 — Notification

1. `Notification` → `EventSourcedAggregate`; `NotificationRecorded`.
2. `PaymentNotificationConsumer` appends the event + `processed_events` in one transaction; `NotificationProjector` (async) fills `notifications`.
3. Tests: duplicate delivery → one event; list eventually consistent (Awaitility).

## Increment 6 — Hardening

Rebuild ITs for all projections, Prometheus alert for lag, docs/ADR/AGENTS.md/master-plan sync, `llms.txt`.

## Verification (every increment)

`rtk proxy bash scripts/ci-local.sh` (Docker + Testcontainers), `rtk mvn -q -Dtest=ArchitectureTest test`, `rtk proxy bash scripts/validate-docs.sh`.
