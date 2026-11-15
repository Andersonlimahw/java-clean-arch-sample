---
title: Data Model
status: active
updated: 2026-11-15
related: [architecture.md, concurrency.md, testing.md]
---

# Database schema and indexes

PostgreSQL is the system of record. Flyway migrations run in version order from `src/main/resources/db/migration`; the Event Sourcing clean-database rollout intentionally rewrites the former V1–V7 history once, then resumes append-only versioning at V5.

## Flyway migrations

| Version | Migration | Schema change |
|---|---|---|
| V1 | `V1__shared.sql` | Creates the outbox, processed-event and idempotency tables, plus `event_store`, `snapshots`, `projection_checkpoints`, and the event immutability trigger. |
| V2 | `V2__identity.sql` | Creates `users` and `refresh_tokens`; `refresh_tokens.user_id` references `users.id` with cascade delete. |
| V3 | `V3__payments.sql` | Creates `payments` with current screening and review columns, optimistic version, and the owner-scoped list index. |
| V4 | `V4__notifications.sql` | Creates `notifications`. Its `user_id` and `payment_id` are UUID columns without cross-module foreign keys. |
| V5–V7 | Consolidated into V1 and V3 | The lease ID and screening columns move into the initial table definitions. The current payment-list index is created normally on an empty database. |

These migrations replace the former V1–V7 history for the clean-database Event Sourcing rollout. The local reset script removes the old schema and replays the consolidated migrations; it does not backfill existing rows.

There are no foreign keys from `payments` or `notifications` into the identity module. The identity refresh-token foreign key stays within identity.

## Indexes and constraints

| Migration | Index or constraint | Definition | Purpose and current use |
|---|---|---|---|
| V1 | `outbox_events` primary key | `(id)` | Identifies an outbox event. |
| V1 | `idx_outbox_pending` | `(next_attempt_at) WHERE published_at IS NULL` | Finds eligible unpublished events in the relay's `next_attempt_at` order. |
| V1 | `processed_events` primary key | `(consumer, event_id)` | Makes processing idempotent for each consumer and event. |
| V1 | `idempotency_keys` primary key | `(user_id, key)` | Enforces one key per caller and supports request-key lookup. |
| V1 | `idx_idempotency_expires` | `(expires_at)` | Supports expiry cleanup ordered by expiration time. |
| V1 | `event_store` primary key | `(global_position)` | Assigns a global append position. |
| V1 | `event_store` unique constraints | `(event_id)`; `(stream_type, stream_id, stream_version)` | Prevents duplicate events and concurrent writes to the same stream version. |
| V1 | `event_store_immutable` trigger | `BEFORE UPDATE OR DELETE` | Rejects mutation of recorded domain events. |
| V1 | `snapshots` primary key | `(stream_type, stream_id)` | Keeps the newest optional state snapshot for each stream. |
| V1 | `projection_checkpoints` primary key | `(projection)` | Stores each asynchronous projection's progress. |
| V2 | `users` primary key; unique constraint on `email` | `(id)`; `UNIQUE(email)` | User lookup and duplicate-email protection. PostgreSQL backs the unique constraint with an index. |
| V2 | `refresh_tokens` primary key; unique constraint on `token_hash` | `(id)`; `UNIQUE(token_hash)` | Token identity and unique hashed-token lookup. The unique constraint creates its own index. |
| V2 | `idx_refresh_tokens_family` | `(family_id)` | Finds tokens in a family for rotation/revocation. |
| V2 | `idx_refresh_tokens_hash` | `(token_hash)` | Intended to support hashed-token lookup; it duplicates the index created by the unique `token_hash` constraint. |
| V3 | `payments` primary key | `(id)` | Payment identity and direct lookup. |
| V3 | `idx_payments_user_created_at_id` | `(user_id, created_at DESC, id DESC)` | Supports owner-scoped payment pages and deterministic tie-breaking. PostgreSQL can scan it backward for ascending order. |
| V4 | `notifications` primary key | `(id)` | Notification identity. |
| V4 | `idx_notifications_user_id` | `(user_id, created_at DESC)` | Supports the caller-scoped notification query ordered newest first. |

Primary-key and unique-constraint indexes are listed because they also affect lookup and uniqueness behavior. The consolidated schema creates the current payment-list index normally on an empty database; it no longer needs a separate non-transactional concurrent-index migration.

## Event store kernel

`event_store` is the append-only log planned as the system of record. Each row stores a stable registered event name, schema version, JSON payload and metadata, with a 1-based stream version and global position. `JdbcEventStore` takes a transaction-scoped advisory lock per stream, verifies that the expected version equals the current version, then appends; the unique constraint is a backstop against duplicate stream versions. This rejects stale and ahead-of-stream appends without gaps. A trigger rejects `UPDATE` and `DELETE`; corrections must be new events.

`snapshots` and `projection_checkpoints` are infrastructure tables. Snapshots are disposable and are ignored when their schema version is not current. At this increment, the kernel is installed but existing aggregate handlers still use their current writers; later increments switch each module separately.

## Resetting the local database

`scripts/db-reset.sh` wipes the local development database and replays every migration from zero. Use it after migrations are rewritten (spec 012 consolidates `V1`–`V7`) or whenever local data should start clean.

```bash
docker compose stop app            # or stop the host JVM
scripts/db-reset.sh                # prompts: type RESET
scripts/db-reset.sh --yes          # non-interactive
scripts/db-reset.sh --yes --db-only  # leave Redis and RabbitMQ alone
```

What it does:

1. Drops and recreates schema `public` on the database in `DB_URL` (psql inside the Compose `postgres` service, or host `psql` when Compose is not running).
2. Replays migrations with the application's own Flyway: `APP_MIGRATION_ONLY=true mvn spring-boot:run`, the same migration-only path as the Kubernetes Job. No Flyway Maven plugin is involved and `spring.flyway.clean-disabled` is not changed.
3. Prints `flyway_schema_history` and exits non-zero unless every `V*__*.sql` file is applied successfully.
4. Unless `--db-only`: Redis `FLUSHDB` (cached payments and rate-limit buckets would outlive the deleted rows) and purge of `payments.created.notifications` and its DLQ (queued events would create notifications for payments that no longer exist).

Safety: it refuses a `DB_URL` host other than `localhost`, `127.0.0.1`, `::1`, `postgres`, or `payments-postgres` unless `--allow-non-local` is passed; requires `--yes` when stdin is not a terminal; refuses while the Compose `app` service runs; reads settings from the environment, then `.env` (environment wins); and never prints credentials. Set `COMPOSE_PROJECT_NAME` to target a Compose project other than `payments`.

## Code and migration note

`ListHeldPaymentsQueryHandler` filters by status (`UNDER_REVIEW`), backed by `PaymentJpaRepository.findByStatus`. The current `idx_payments_user_created_at_id` index does not serve that status-only filter, so the held-payment list has no status-specific index. This page records the current schema and code; it does not change the query.

## Implementation references

- Migration SQL: `src/main/resources/db/migration/V1__shared.sql` through `V4__notifications.sql`.
- Event-store kernel and adapters: `src/main/java/com/muse/payments/shared/domain/EventSourcedAggregate.java`, `src/main/java/com/muse/payments/shared/application/EventStore.java`, and `src/main/java/com/muse/payments/shared/infrastructure/eventstore/`.
- Runtime Flyway lock setting: `src/main/resources/application.yml`.
- Payment list and held-payment queries: `src/main/java/com/muse/payments/payments/application/ListPaymentsQueryHandler.java`, `src/main/java/com/muse/payments/payments/application/ListHeldPaymentsQueryHandler.java`, and `src/main/java/com/muse/payments/payments/infrastructure/PaymentJpaRepository.java`.
- Outbox and expiry query patterns: `src/main/java/com/muse/payments/shared/infrastructure/OutboxJpaRepository.java` and `src/main/java/com/muse/payments/shared/infrastructure/idempotency/JdbcIdempotencyRepository.java`.

## Eval

```bash
rtk mvn -q -Dtest=MigrationOnlyModeTest,JavaVersionPinsTest test
DB_URL=jdbc:postgresql://db.example.com:5432/payments DB_PASSWORD=x scripts/db-reset.sh --yes; echo $?
```

Expected result: migration-only startup and version pins remain valid; Flyway applies exactly V1–V4 on a clean PostgreSQL 16 database, and the reset script refuses the non-local host with exit code `1`.
