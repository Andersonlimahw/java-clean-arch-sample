# Tasks: PR3 — Payments Module & Idempotency

- [x] Create specification documents (`spec.md`, `plan.md`, `tasks.md`).
- [x] Create Flyway migration `V3__payments.sql` (schema, indexes, optimistic locking).
- [x] Implement domain model: `Money` VO, `PaymentStatus` enum, domain events, `Payment` aggregate root, `PaymentRepository` port.
- [x] Implement Idempotency component (hashing, atomic insert with `PROCESSING`, replay on `COMPLETED`, mismatch detection, concurrent collision detection).
- [x] Implement Application commands & handlers (`CreatePayment`, `ConfirmPayment`, `CancelPayment`, `GetPaymentById`, `ListPayments`) + Outbox integration.
- [x] Implement Infrastructure JPA entities and adapters (`PaymentJpaEntity`, `PaymentJpaRepository`, `PaymentJpaAdapter`).
- [x] Implement Web controller (`PaymentController`), DTOs, and request validation.
- [x] Write Unit and Domain tests (`PaymentTest`, `MoneyTest`, `IdempotencyServiceTest`).
- [x] Write Testcontainers integration tests (`PaymentsIT`) verifying:
  - Payment creation + Outbox event commit
  - Replay with same key + same body returns 201 with `Idempotent-Replayed: true`
  - Replay with same key + different body returns 409 Conflict
  - Optimistic locking / concurrent modification returns 409
  - State machine invariants (cannot cancel completed payment)
  - Ownership protection (user B cannot read user A's payment -> 404)
- [x] Validate ArchUnit architecture rules and Spring Modulith boundaries.
- [x] Execute `mvn -B clean verify` with Testcontainers.
- [x] Push to `feat/pr3-payments`, create PR #4, conduct review, merge, cleanup worktree (merged as `3caa063`).
