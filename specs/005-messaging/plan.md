# Architecture Plan: PR5 — Messaging & Notifications Module

## 1. RabbitMQ Topology (`com.muse.payments.shared.infrastructure.messaging`)
- **Exchanges**:
  - `payments.events` (`TopicExchange`, durable: true)
  - `payments.events.dlx` (`DirectExchange`, durable: true)
- **Queues**:
  - `payments.created.notifications` (durable, arguments: `x-dead-letter-exchange=payments.events.dlx`, `x-dead-letter-routing-key=payment.created.dlq`)
  - `payments.created.notifications.dlq` (durable)
- **Bindings**:
  - Binding from `payments.events` to `payments.created.notifications` with routing key `payment.created`.
  - Binding from `payments.events.dlx` to `payments.created.notifications.dlq` with routing key `payment.created.dlq`.
- **Message Converter**: Jackson JSON Message Converter with ISO-8601 timestamps.
- **Connection**: Publisher Confirms enabled (`spring.rabbitmq.publisher-confirm-type=correlated`, `publisher-returns=true`).

## 2. Transactional Outbox Relay (`com.muse.payments.shared.infrastructure.outbox`)
- **Component**: `OutboxPoller`:
  - Runs periodically (`@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")`).
  - Batch size: 50.
  - Query:
    ```sql
    SELECT id, aggregate_type, aggregate_id, event_type, payload, headers, occurred_at, attempts
    FROM outbox_events
    WHERE published_at IS NULL AND next_attempt_at <= NOW()
    ORDER BY next_attempt_at ASC
    LIMIT 50
    FOR UPDATE SKIP LOCKED
    ```
  - For each event:
    - Routing key: derived from eventType (e.g. `PaymentCreated` -> `payment.created`).
    - Propagate `X-Correlation-Id` from headers into AMQP MessageProperties.
    - Publish via `RabbitTemplate`.
    - If success: mark `published_at = NOW()`.
    - If failure: increment `attempts`, compute exponential backoff: `delaySeconds = Math.min(300, (int) Math.pow(2, attempts) * 2 + randomJitter)`. Set `next_attempt_at = NOW() + delaySeconds`, update `last_error`.

## 3. Notifications Module (`com.muse.payments.notifications`)
- **Domain**:
  - `Notification` entity/aggregate (`id`, `userId`, `paymentId`, `type`, `title`, `message`, `createdAt`).
  - `NotificationRepository` port.
- **Consumer**:
  - `@RabbitListener(queues = "payments.created.notifications")`
  - Reads `X-Correlation-Id` header into MDC.
  - Queries `processed_events` using `ProcessedEventRepository` (`consumer = 'PaymentNotificationConsumer'`, `eventId`).
  - If already processed -> logs debug, skips.
  - If not processed:
    - Inserts into `notifications`.
    - Inserts into `processed_events`.
    - Commits transaction.

## 4. Database Schema
- Migration: `V4__notifications.sql`:
  - `notifications` table (`id UUID PRIMARY KEY`, `user_id UUID NOT NULL`, `payment_id UUID NOT NULL`, `type VARCHAR(50)`, `title VARCHAR(150)`, `message TEXT`, `created_at TIMESTAMPTZ`).
  - Index on `(user_id, created_at DESC)`.
