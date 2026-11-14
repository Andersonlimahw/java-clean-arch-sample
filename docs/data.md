---
title: Data Model
status: active
updated: 2026-11-13
related: [architecture.md, concurrency.md, testing.md]
---

# Database schema and indexes

PostgreSQL is the system of record. Flyway scripts are append-only and run in version order from `src/main/resources/db/migration`.

## Flyway migrations

| Version | Migration | Schema change |
|---|---|---|
| V1 | `V1__shared_outbox_inbox.sql` | Creates `outbox_events`, `processed_events`, and `idempotency_keys`, including the outbox pending and idempotency expiry indexes. |
| V2 | `V2__identity.sql` | Creates `users` and `refresh_tokens`; `refresh_tokens.user_id` references `users.id` with cascade delete. |
| V3 | `V3__payments.sql` | Creates `payments` with a `version` column for optimistic locking and the initial payment-list indexes. |
| V4 | `V4__notifications.sql` | Creates `notifications`. Its `user_id` and `payment_id` are UUID columns without cross-module foreign keys. |
| V5 | `V5__idempotency_lease.sql` | Adds `idempotency_keys.lease_id` so completion or release applies only to the request that acquired the processing lease. |
| V6 | `V6__payments_list_index.sql` | Builds the payment-list index concurrently, then concurrently drops the two earlier payment indexes. The `.conf` file disables Flyway's transaction for this migration. |
| V7 | `V7__payment_screening_reason.sql` | Adds `screening_reason`, `held_reason`, `reviewer_id`, and `reviewed_at` to `payments`. |

There are no foreign keys from `payments` or `notifications` into the identity module. The identity refresh-token foreign key stays within identity.

## Indexes and constraints

| Migration | Index or constraint | Definition | Purpose and current use |
|---|---|---|---|
| V1 | `outbox_events` primary key | `(id)` | Identifies an outbox event. |
| V1 | `idx_outbox_pending` | `(next_attempt_at) WHERE published_at IS NULL` | Finds eligible unpublished events in the relay's `next_attempt_at` order. |
| V1 | `processed_events` primary key | `(consumer, event_id)` | Makes processing idempotent for each consumer and event. |
| V1 | `idempotency_keys` primary key | `(user_id, key)` | Enforces one key per caller and supports request-key lookup. |
| V1 | `idx_idempotency_expires` | `(expires_at)` | Supports expiry cleanup ordered by expiration time. |
| V2 | `users` primary key; unique constraint on `email` | `(id)`; `UNIQUE(email)` | User lookup and duplicate-email protection. PostgreSQL backs the unique constraint with an index. |
| V2 | `refresh_tokens` primary key; unique constraint on `token_hash` | `(id)`; `UNIQUE(token_hash)` | Token identity and unique hashed-token lookup. The unique constraint creates its own index. |
| V2 | `idx_refresh_tokens_family` | `(family_id)` | Finds tokens in a family for rotation/revocation. |
| V2 | `idx_refresh_tokens_hash` | `(token_hash)` | Intended to support hashed-token lookup; it duplicates the index created by the unique `token_hash` constraint. |
| V3 | `payments` primary key | `(id)` | Payment identity and direct lookup. |
| V3, dropped in V6 | `idx_payments_user_status` | `(user_id, status)` | Its columns support combined owner/status filtering. V6 removes it. Current code has no query filtering by both; the held-payment query filters by `status` alone, so this composite index would not serve that query through its leading column. |
| V3, dropped in V6 | `idx_payments_created_at` | `(created_at DESC)` | Initially supported global creation-time ordering. Current owner lists filter by `user_id`, so V6 replaces it with an owner-scoped index. |
| V4 | `notifications` primary key | `(id)` | Notification identity. |
| V4 | `idx_notifications_user_id` | `(user_id, created_at DESC)` | Supports the caller-scoped notification query ordered newest first. |
| V6 | `idx_payments_user_created_at_id` | `(user_id, created_at DESC, id DESC)` | Supports owner-scoped payment pages and deterministic tie-breaking. PostgreSQL can scan it backward for ascending order. |

Primary-key and unique-constraint indexes are listed because they also affect lookup and uniqueness behavior. V5 and V7 add no indexes.

## V6 concurrent index migration

V6 uses `CREATE INDEX CONCURRENTLY` so normal payment inserts, updates, and deletes can continue while PostgreSQL builds the index. PostgreSQL does not allow that statement inside a transaction, so `V6__payments_list_index.sql.conf` sets `executeInTransaction=false`; `spring.flyway.postgresql.transactional-lock=false` is configured in `application.yml` to use a session advisory lock.

The migration has no `IF NOT EXISTS`. If a concurrent build fails, PostgreSQL may leave an invalid index with the target name. Remove that invalid index with `DROP INDEX CONCURRENTLY idx_payments_user_created_at_id` before retrying the migration. Do not edit V6 after it has been applied to a shared database.

## Code and migration note

The V6 SQL comment says no payment query filters by status. Current code does filter by status: `ListHeldPaymentsQueryHandler` calls `PaymentRepository.findByStatus(UNDER_REVIEW, ...)`, backed by `PaymentJpaRepository.findByStatus`. Since V6 drops `idx_payments_user_status` and no replacement status index is created, the held-payment list has no status-specific index. This page records the current schema and code; it does not change the query or migration.

## Implementation references

- Migration SQL and V6 configuration: `src/main/resources/db/migration/V1__shared_outbox_inbox.sql` through `V7__payment_screening_reason.sql`, plus `V6__payments_list_index.sql.conf`.
- Runtime Flyway lock setting: `src/main/resources/application.yml`.
- Payment list and held-payment queries: `src/main/java/com/muse/payments/payments/application/ListPaymentsQueryHandler.java`, `src/main/java/com/muse/payments/payments/application/ListHeldPaymentsQueryHandler.java`, and `src/main/java/com/muse/payments/payments/infrastructure/PaymentJpaRepository.java`.
- Outbox and expiry query patterns: `src/main/java/com/muse/payments/shared/infrastructure/OutboxJpaRepository.java` and `src/main/java/com/muse/payments/shared/infrastructure/idempotency/JdbcIdempotencyRepository.java`.

## Eval

```bash
rtk mvn -q -Dtest=MigrationOnlyModeTest,JavaVersionPinsTest test
```

Expected result: migration-only startup and version pins remain valid.
