# Tasks: PR5 — Messaging with RabbitMQ & Notifications

- [x] Create specification documents (`spec.md`, `plan.md`, `tasks.md`).
- [x] Create Flyway migration `V4__notifications.sql`.
- [x] Implement RabbitMQ configuration and topology (`RabbitMqConfig`: exchanges, queues, DLX, DLQ, bindings, message converter).
- [x] Implement Outbox Poller Relay (`OutboxRelayService`, `SchedulingConfig`) with `FOR UPDATE SKIP LOCKED`, publisher confirms, and exponential backoff retry with jitter.
- [x] Implement Notifications domain model (`Notification`), repository port, JPA adapter (`NotificationJpaEntity`, `NotificationJpaRepository`, `NotificationJpaAdapter`).
- [x] Implement Idempotent Event Consumer (`PaymentNotificationConsumer`) with `processed_events` deduplication and correlation ID MDC propagation.
- [x] Configure `TestcontainersBase` to include RabbitMQ 3.13-alpine container along with PostgreSQL 16.
- [x] Write integration test `MessagingNotificationsIT` verifying:
  - Payment created -> Outbox relay picks event up and publishes to RabbitMQ
  - Notification consumer receives event, persists notification, records in `processed_events`
  - Duplicate event re-delivery causes zero duplicate notifications
  - Correlation ID is preserved from payment creation to notification log/context
- [x] Execute `mvn -B clean verify` with full container suite.
- [x] Push to `feat/pr5-messaging`, open PR #5, run review, merge, and clean worktree (merged as `0ee3ed4`).
