# Tasks: 012 — Event Sourcing

## Spec
- [x] Write `specs/012-event-sourcing/{spec.md,plan.md,tasks.md}` with owner decisions D1–D5.
- [x] Add `scripts/db-reset.sh` (local reset + Flyway replay via migration-only mode, Redis flush, queue purge) and document it.
- [ ] Review (Change Reviewer) and merge the spec to `main` before any code.

## Increment 0 — Kernel and migration rewrite
- [ ] Consolidate `V1`–`V7` into `V1__shared.sql`, `V2__identity.sql`, `V3__payments.sql`, `V4__notifications.sql`; delete `V5`–`V7` and the `.conf`.
- [ ] Add `event_store`, `snapshots`, `projection_checkpoints`, and the immutability trigger.
- [ ] `EventSourcedAggregate`, `EventStore`, `SnapshotStore`, `SyncProjector` ports.
- [ ] `JdbcEventStore`, `EventTypeRegistry`, `EventSerializer`, upcaster chain, `JdbcSnapshotStore`.
- [ ] Tests: concurrent append conflict IT, fold order, upcaster fixture, stale-snapshot ignore, trigger rejects UPDATE/DELETE.
- [ ] ADR 0016 (supersedes 0004), ADR index, ArchUnit rules, `docs/data.md` reset note, `llms.txt`.

## Increment 1 — Payment
- [ ] Event-sourced `Payment` with six event types; `PaymentReleasedFromReview` maps to `PaymentCreated` integration event.
- [ ] `EventSourcedPaymentRepository` + `PaymentReadRepository`; sync `PaymentProjector`.
- [ ] Tests: replay = projection for all event types; concurrent confirm 409; existing payment/messaging/idempotency ITs unchanged.

## Increment 2 — Async runtime and payment history
- [ ] `AsyncProjectionRunner` with `xid8` filter, checkpoint lock, `projection.lag` metric.
- [ ] `payment_history` projection + `GET /api/v1/payments/{id}/history` (+ OpenAPI, Postman, `docs/api.md`).
- [ ] `APP_PROJECTION_REBUILD` mode.
- [ ] Tests: interleaved commits, resume from checkpoint, rebuild equality, endpoint authorization and 404.

## Increment 3 — User
- [ ] Event-sourced `User`; `user_credentials`; sync `UserProjector`.
- [ ] Tests: no password hash in `event_store`; duplicate email 409; `IdentityIT` + `LoginPerformanceIT` green.

## Increment 4 — Refresh-token family
- [ ] `RefreshTokenFamily` aggregate; `FamilyRevoked` in `REQUIRES_NEW`; sync `RefreshTokenProjector`; snapshots enabled.
- [ ] Tests: reuse survives 401; snapshot equivalence; login p95 budget.

## Increment 5 — Notification
- [ ] Event-sourced `Notification`; consumer appends event + `processed_events`; async `NotificationProjector`.
- [ ] Tests: duplicate delivery → one event; eventual list read.

## Increment 6 — Hardening
- [ ] Rebuild ITs for every projection; lag alert in `deploy/observability/prometheus-alerts.yml`.
- [ ] Sync `docs/architecture.md`, `docs/messaging.md`, `docs/api.md`, ADR index, `AGENTS.md`, `000-master-plan.md`, `llms.txt`.
