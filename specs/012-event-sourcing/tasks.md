# Tasks: 012 — Event Sourcing

## Spec
- [x] Write `specs/012-event-sourcing/{spec.md,plan.md,tasks.md}` with owner decisions D1–D5.
- [x] Add `scripts/db-reset.sh` (local reset + Flyway replay via migration-only mode, Redis flush, queue purge) and document it.
- [x] Review (Change Reviewer) and merge the spec to `main` before any code.

## Increment 0 — Kernel and migration rewrite
- [x] Consolidate `V1`–`V7` into `V1__shared.sql`, `V2__identity.sql`, `V3__payments.sql`, `V4__notifications.sql`; delete `V5`–`V7` and the `.conf`.
- [x] Add `event_store`, `snapshots`, `projection_checkpoints`, and the immutability trigger.
- [x] `EventSourcedAggregate`, `EventStore`, `SnapshotStore`, `SyncProjector` ports.
- [x] `JdbcEventStore`, `EventTypeRegistry`, `EventSerializer`, upcaster chain, `JdbcSnapshotStore`.
- [x] Tests: concurrent append conflict IT, ahead-version gap rejection, fold order, upcaster fixture, stale-snapshot ignore, trigger rejects UPDATE/DELETE.
- [x] ADR 0016 (supersedes 0004), ADR index, ArchUnit rules, `docs/data.md` reset note, `llms.txt`.

## Increment 1 — Payment
- [x] Event-sourced `Payment` with six event types; `PaymentReleasedFromReview` maps to `PaymentCreated` integration event.
- [x] `EventSourcedPaymentRepository` + `PaymentReadRepository`; sync `PaymentProjector`.
- [x] Tests: replay = projection for all event types; concurrent confirm 409; existing payment/messaging/idempotency behavior preserved.

## Increment 2 — Async runtime and payment history
- [x] `AsyncProjectionRunner` with sequence/XID horizon, `xid8` filter, checkpoint lock, `projection.lag` metric.
- [x] `payment_history` projection + `GET /api/v1/payments/{id}/history` (+ OpenAPI, Postman, `docs/api.md`).
- [x] `APP_PROJECTION_REBUILD` mode.
- [x] Tests: interleaved commits (including inverted XID/position order), resume from checkpoint, rebuild equality, endpoint authorization and 404.

## Increment 3 — User
- [x] Event-sourced `User`; `user_credentials`; sync `UserProjector`.
- [x] Tests: no password hash in `event_store`; duplicate email 409; `IdentityIT` + `LoginPerformanceIT` green.

## Increment 4 — Refresh-token family
- [x] `RefreshTokenFamily` aggregate; `FamilyRevoked` in `REQUIRES_NEW`; sync `RefreshTokenProjector`; snapshots enabled.
- [x] Tests: reuse survives 401; snapshot equivalence; login p95 budget.

## Increment 5 — Notification
- [x] Event-sourced `Notification`; consumer appends event + `processed_events`; async `NotificationProjector`.
- [x] Tests: duplicate delivery → one event; eventual list read.

## Increment 6 — Hardening
- [x] Rebuild ITs for every projection; lag alert in `deploy/observability/prometheus-alerts.yml`.
- [x] Sync `docs/architecture.md`, `docs/messaging.md`, `docs/api.md`, ADR index, `AGENTS.md`, `000-master-plan.md`, `llms.txt`.

## Delivery verification

Owner update: increments 1–6 are delivered together. Code/task checks above describe implementation; final main validation and editorial handoff are separate delivery gates. Reset was exercised only in an isolated Compose; the user's data was preserved.
