# Specification: 012 — Event Sourcing for payments, identity and notifications

**Modules**: `shared`, `payments`, `identity`, `notifications`  
**Branch**: `docs/spec-012-event-sourcing` (spec); one branch per increment afterwards  
**Status**: Draft — awaiting review  
**Supersedes**: ADR 0004 (CQRS without event sourcing) once increment 0 merges

---

## 1. Objectives

Make the append-only event stream the system of record for every aggregate, in small increments that each reach `main` green.

1. **Event store as source of truth**: aggregate state is rebuilt from its stream; current-state tables become projections.
2. **Hybrid projections**: synchronous projections, in the append transaction, for reads that need read-your-writes or uniqueness; asynchronous projections, behind a checkpoint, for read models that tolerate lag.
3. **Contract preserved**: the `/api/v1` contract, status codes, RFC 7807 bodies, idempotency, ownership 404s, and integration events on `payments.events` stay as they are. The only API addition is the payment history endpoint (§7).
4. **Clean-database migration**: there is no data to keep. The Flyway history is rewritten into consolidated per-module migrations; no backfill.

## 2. Owner decisions (2026-11-15, binding)

| # | Decision |
|---|---|
| D1 | All modules are in scope: `payments` (`Payment`), `identity` (`User`, refresh-token family), `notifications` (`Notification`). |
| D2 | Projection consistency is hybrid: synchronous for `payments`, `users`, `refresh_tokens`; asynchronous for `notifications` and the new `payment_history`. |
| D3 | No backfill. Every database is treated as new: existing migrations are rewritten to run from zero, and the append-only Flyway rule from `000-master-plan.md` is lifted for this one rewrite and restored afterwards. Local databases are reset with `scripts/db-reset.sh` (§8). |
| D4 | Password hashes stay out of events. Credentials live in a state table (`user_credentials`) written in the same transaction as the identity events. |
| D5 | The async side is exercised by `notifications` (already eventual via RabbitMQ) and a new `GET /api/v1/payments/{id}/history`. |

## 3. Current state (verified on `main` at `2af343a`)

- Aggregates extend `shared.domain.AggregateRoot`, buffer transient `DomainEvent`s, and are persisted as rows through JPA/JDBC adapters (`PaymentJpaAdapter`, `UserJpaAdapter`, `RefreshTokenJpaAdapter`, `NotificationJpaAdapter`).
- Handlers save the row and map domain events to `IntegrationEvent`s appended to `outbox_events` with `Propagation.MANDATORY` (`OutboxJpaAdapter`). `OutboxRelayService` publishes with confirms; `PaymentNotificationConsumer` deduplicates with `processed_events`.
- Optimistic locking is the `version` column on `payments` and `users`.
- `Payment.releaseFromReview` re-emits `PaymentCreatedEvent`, so notifications get a "created" message after approval.
- Refresh-token reuse revokes the family in a `REQUIRES_NEW` transaction (`RefreshTokenJpaAdapter.revokeFamily`) so the revocation survives the 401 rollback.
- Migrations: `V1` shared, `V2` identity, `V3` payments, `V4` notifications, `V5` idempotency lease, `V6` list index (non-transactional `.conf`), `V7` screening columns.

## 4. Event store model

### 4.1 Tables (owned by `shared`)

```sql
event_store (
  global_position  BIGSERIAL PRIMARY KEY,
  stream_type      VARCHAR(100) NOT NULL,      -- 'Payment', 'User', 'RefreshTokenFamily', 'Notification'
  stream_id        UUID         NOT NULL,
  stream_version   BIGINT       NOT NULL,      -- 1-based, contiguous per stream
  event_id         UUID         NOT NULL UNIQUE,
  event_type       VARCHAR(150) NOT NULL,      -- stable name, never a Java class name
  schema_version   INT          NOT NULL,
  payload          JSONB        NOT NULL,
  metadata         JSONB        NOT NULL,      -- correlationId, causationId, traceparent, actorId
  occurred_at      TIMESTAMPTZ  NOT NULL,
  recorded_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  transaction_id   XID8         NOT NULL DEFAULT pg_current_xact_id(),
  UNIQUE (stream_type, stream_id, stream_version)
)
snapshots (stream_type, stream_id, stream_version, schema_version, state JSONB, taken_at,
           PRIMARY KEY (stream_type, stream_id))
projection_checkpoints (projection VARCHAR(100) PRIMARY KEY, last_position BIGINT NOT NULL,
           last_transaction_id XID8, updated_at TIMESTAMPTZ NOT NULL)
```

- **Immutability**: a trigger rejects `UPDATE` and `DELETE` on `event_store`. Corrections are new events.
- **No cross-module FKs**: `stream_id` is a UUID reference, as today.

### 4.2 Append and optimistic concurrency

`EventStore.append(streamType, streamId, expectedVersion, events)` inserts rows with `stream_version = expectedVersion + 1..n`. A unique violation on `(stream_type, stream_id, stream_version)` becomes `ConflictException` → **409**. Exception: on `RefreshTokenFamily` rotation, a `ConflictException` means a concurrent request already rotated that token, so the loser treats it as reuse, appends `FamilyRevoked` (latest stream version, `REQUIRES_NEW`), and returns **401** (`BadCredentialsException`), preserving §1.3. This replaces the JPA `@Version` check. The projection `version` column, which `PaymentResponse.version` exposes (0 after create, 1 after confirm — `PaymentsIT`), is `stream_version - 1`, so the API value does not change. `If-Match` is not implemented today and stays out of scope.

### 4.3 Loading, snapshots

`load(streamType, streamId)` reads the latest snapshot (if any) and the events after it, upcasts them, and folds them through the aggregate's `apply` methods.

- Snapshots are infrastructure, not state of record; deleting all snapshots must not change behavior.
- They are taken after append when `stream_version - snapshot_version >= app.event-store.snapshot-every` (default 50).
- Only the refresh-token family stream can grow without bound (one event per rotation). `Payment`, `User`, and `Notification` streams are short and are not snapshotted unless measurement says otherwise.
- A snapshot whose `schema_version` is not current is ignored (full replay), never migrated in place.

### 4.4 Event versioning and upcasting

- `event_type` names are stable and registered in an `EventTypeRegistry`; renaming a type is forbidden.
- Additive or derivable changes bump `schema_version` and add an `Upcaster(type, fromVersion)` that rewrites JSON on read. Chains run in order to the current version.
- Breaking semantic changes introduce a new `event_type`; old events keep their old handler.
- Every upcaster has a unit test that loads a stored JSON fixture of the old version.

### 4.5 Integration events

The outbox stays. Event store rows are internal facts; `outbox_events` carries the published contract. Both are written in the same transaction, from the same domain events, through the existing per-module mappers. Routing keys and payloads on `payments.events` do not change. Example: the new `PaymentReleasedFromReview` domain event still maps to the `PaymentCreated` integration event, preserving today's notification behavior.

## 5. Aggregates and events

| Stream type | Stream id | Events | Notes |
|---|---|---|---|
| `Payment` | payment id | `PaymentInitiated`, `PaymentHeldForReview`, `PaymentReleasedFromReview`, `PaymentRejectedInReview`, `PaymentCompleted`, `PaymentCancelled` | Replaces reuse of `PaymentCreatedEvent` on release. |
| `User` | user id | `UserRegistered` (email, displayName, roles), `UserDisabled` | No password hash (D4). Email is immutable PII in the stream — see §9. |
| `RefreshTokenFamily` | family id | `FamilyStarted` (userId, first token hash, expiresAt), `TokenRotated` (old hash, new hash, expiresAt), `TokenRevoked` (hash, reason), `FamilyRevoked` (reason) | Token hashes only, never raw tokens. `FamilyRevoked` on reuse keeps the `REQUIRES_NEW` semantics. Snapshotted (§4.3). |
| `Notification` | notification id | `NotificationRecorded` (userId, paymentId, type, title, message, sourceEventId) | Appended by the consumer in the same transaction as `processed_events`. |

Domain events stay pure Java records in `<module>.domain`. Aggregates gain `apply(event)` methods with no side effects; command methods validate invariants and then raise and apply events.

## 6. Projections

| Projection | Mode | Source streams | Purpose |
|---|---|---|---|
| `payments` | sync | `Payment` | Get/list/under-review queries, Redis cache source, ownership 404, list index. |
| `users` | sync | `User` | Login lookup and email uniqueness: the `existsByEmail` pre-check stays (409 `ConflictException`), and the projection's unique index is the race backstop (`DataIntegrityViolationException` → 409 "Data Conflict" in `GlobalExceptionHandler`), rolling back the appended events. |
| `user_credentials` | state table, not a projection | — | Password hash, written by the register handler in the same transaction (D4). |
| `refresh_tokens` | sync | `RefreshTokenFamily` | Hash lookup on refresh/logout without replay on the hot path. |
| `notifications` | async | `Notification` | `GET /api/v1/notifications`. |
| `payment_history` | async | `Payment` | `GET /api/v1/payments/{id}/history`. |

**Sync rules**: projectors run inside the append transaction (`Propagation.MANDATORY`), after the append and before commit. A projector failure rolls back the events. Cache eviction stays `afterCommit`.

**Async rules**:
- A single scheduled poller per projection reads `event_store` in `global_position` order, only rows with `transaction_id < pg_snapshot_xmin(pg_current_snapshot())`. This avoids skipping events from transactions that commit out of sequence order.
- Each batch applies the events and advances `projection_checkpoints` in one transaction. Handlers are idempotent by `global_position`/`event_id`, so re-delivery is safe.
- Multiple instances: the checkpoint row is locked `FOR UPDATE SKIP LOCKED`; the instance that does not get the lock skips the cycle.
- Metric `projection.lag{projection}` = max position − checkpoint, and an alert rule when lag exceeds a threshold for 5 minutes.
- **Rebuild**: `APP_PROJECTION_REBUILD=<name>` (same pattern as `APP_MIGRATION_ONLY`) truncates the projection, resets its checkpoint, replays, and exits. Sync projections can be rebuilt the same way, offline.

## 7. API addition

`GET /api/v1/payments/{id}/history?page=&size=` → 200 `{ items: [{ eventType, occurredAt, status, actor, reason? }], page… }`. Same visibility as `GET /api/v1/payments/{id}`: owner, `ADMIN`, or `FRAUD_REVIEWER`; another user's payment is **404**. It reads `payment_history` (async), so a just-created payment may briefly return fewer entries; documented in `docs/api.md`. It must not expose `CurrentUser` in the OpenAPI request schema (`docs/rules/api-auth-context.md`). The Postman collection gets the request with status and payload assertions.

## 8. Migration strategy (D3)

- Rewrite into four consolidated, module-owned migrations: `V1__shared.sql` (outbox, processed_events, idempotency_keys with `lease_id`, event_store, snapshots, projection_checkpoints, immutability trigger), `V2__identity.sql` (users projection, user_credentials, refresh_tokens projection), `V3__payments.sql` (payments projection with screening/review columns and `idx_payments_user_created_at_id`, payment_history), `V4__notifications.sql`.
- `V5`–`V7` and `V6__payments_list_index.sql.conf` are deleted; their effect is folded in. Indexes are created plainly; `CONCURRENTLY` is not needed on an empty schema.
- After this rewrite merges, migrations are append-only again (new versions from `V5`).
- **Premise (owner, 2026-11-15)**: every database is treated as new. Migrations are written to run from zero; there is no compatibility with an already-applied schema and no backfill.
- Any existing local database has a different Flyway history (checksums and versions) and must be reset once with `scripts/db-reset.sh` (below) or `make clean`. Testcontainers starts empty, so CI is unaffected. No shared or production database exists (README, ADR 0011).
- **`scripts/db-reset.sh`** (delivered with this spec, works for the current and the consolidated migrations): drops schema `public` on the local database, replays every migration through the application's own Flyway in migration-only mode (`APP_MIGRATION_ONLY=true mvn spring-boot:run`, the same path as the Kubernetes Job, so there is no second migration tool and `spring.flyway.clean-disabled` stays untouched), prints `flyway_schema_history`, and fails unless every `V*__*.sql` file is applied successfully. It refuses non-local hosts without `--allow-non-local`, requires `--yes` or a typed `RESET`, refuses while the Compose `app` service runs, reads connection settings from the environment/`.env`, and never prints credentials. By default it also runs Redis `FLUSHDB` (cached payments and rate-limit buckets would outlive the rows) and purges `payments.created.notifications` and its DLQ (queued events would create notifications for payments that no longer exist); `--db-only` skips both. Documented in `docs/data.md` and the README.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Async projector skips events that commit out of position order | `xid8` visibility filter (§6); IT with two interleaved transactions. |
| Login p95 regression from extra event + projection writes | `LoginPerformanceIT` budget (p95 < 150 ms, `login.p95.budget.ms`) must stay green in increment 4. |
| Email is immutable PII in `UserRegistered`; no erasure | Documented limitation for this sample (no real users). A later spec can add crypto-shredding. |
| Event schema drift breaks replay | Registry + upcasters + stored JSON fixtures per version; ArchUnit forbids class names in `event_type`. |
| Sync projector bug corrupts the read model | Rebuild mode; IT asserting replayed state equals the projection row for every aggregate. |
| Rewritten migrations break someone's local DB | `scripts/db-reset.sh` one-time reset (§8), called out in docs and the increment 0 PR. |
| Reset script pointed at the wrong database | Local-host allowlist, `--yes`/typed confirmation, refusal while `app` or active DB sessions run; PostgreSQL reset verifies Compose port matches `DB_URL` (or uses host `psql`), and Redis/RabbitMQ steps only run when their configured host/port matches the running Compose service. |
| Two write paths during transition (row save vs append) | Each increment migrates one aggregate completely; ArchUnit rule that only projectors write projection repositories. |
| Snapshot schema change | Snapshots with non-current `schema_version` are ignored. |

## 10. Increments and acceptance criteria

Every increment: own worktree and PR, `scripts/ci-local.sh` green, `ArchitectureTest` + `ApplicationModules.verify()` green, docs updated with `scripts/validate-docs.sh` passing, Change Reviewer `OK TO MERGE` on the exact SHA, rebase merge.

| Inc | Scope | Acceptance (verifiable) |
|---|---|---|
| 0 | Event store kernel + migration rewrite + ADR 0016 | Consolidated `V1`–`V4` apply on empty PostgreSQL 16; `event_store` `UPDATE`/`DELETE` raise; `EventStore` IT: concurrent append with the same expected version → exactly one success and one `ConflictException`; load folds events in order; upcaster unit test from a v1 fixture; snapshot ignored when `schema_version` differs; all existing tests unchanged and green. ADR 0016 supersedes 0004. |
| 1 | `Payment` write side event-sourced; `payments` sync projection | Every transition writes exactly one `event_store` row with contiguous `stream_version`; `payments.version` = `stream_version - 1` and the API `version` values in `PaymentsIT`/`PaymentCacheIT` are unchanged; replaying a stream equals the projection row (IT, all six event types); concurrent confirm → one 200, one 409 (existing IT); outbox rows and routing keys unchanged (`MessagingNotificationsIT` unchanged); idempotency replay unchanged (`IdempotencyAndAccessIT`, `PaymentsIT` unchanged); `PaymentJpaAdapter` no longer writes rows outside the projector. |
| 2 | Async projection runtime + `payment_history` + history endpoint + rebuild mode + lag metric | History lists events in order for owner/ADMIN/FRAUD_REVIEWER and 404 for another `USER` (IT with Awaitility); out-of-order commit IT does not skip an event; restart resumes from checkpoint without duplicates; `APP_PROJECTION_REBUILD=payment_history` rebuilds an identical table and exits 0; `projection.lag` exposed on `/actuator/prometheus`; Postman request + assertions; OpenAPI has no `CurrentUser` input. |
| 3 | `User` event-sourced; `users` sync projection; `user_credentials` | Register writes `UserRegistered` with no password hash anywhere in `event_store` (IT greps payloads); duplicate email → 409 unchanged; login reads projection + credentials, no replay; `IdentityIT` unchanged. |
| 4 | `RefreshTokenFamily` event-sourced; `refresh_tokens` sync projection; snapshots enabled for this stream | Rotation appends `TokenRotated`; concurrent rotation of the same token → one 200 and one 401 with `FamilyRevoked` appended (IT); reuse appends `FamilyRevoked`, which survives the 401 (IT checks `event_store` after the failed request); logout appends `TokenRevoked`; snapshot taken at the configured threshold and load with or without snapshots yields the same state; `LoginPerformanceIT` p95 budget green. |
| 5 | `Notification` event-sourced; `notifications` async projection | Consumer appends `NotificationRecorded` and `processed_events` in one transaction; duplicate delivery → one event and one row; `GET /api/v1/notifications` eventually returns it (Awaitility); `MessagingNotificationsIT` adapted only to await the projection. |
| 6 | Hardening and docs | Rebuild IT for every projection (rebuilt = live); alert rule for projection lag in `deploy/observability/prometheus-alerts.yml`; `docs/architecture.md`, `docs/data.md`, `docs/messaging.md`, `docs/api.md`, ADR index, `AGENTS.md` (CQRS + event sourcing), and `docs/llms.txt` updated; `000-master-plan.md` notes the event-sourcing change. |

## 11. Out of scope

Event store technology other than PostgreSQL (EventStoreDB, Kafka); replacing the outbox with direct event-store publishing; event sourcing of infrastructure state (`idempotency_keys`, `outbox_events`, `processed_events`, rate-limit buckets); crypto-shredding; temporal query API beyond payment history; any production deploy.

## Eval

```bash
rtk proxy bash scripts/ci-local.sh
```

Expected result per increment: all local checks pass, and the increment's acceptance tests in §10 are present and green in the Surefire/Failsafe reports.
