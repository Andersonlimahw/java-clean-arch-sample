# Specification: PR5 — Messaging with RabbitMQ & Notifications Module

**Module**: `com.muse.payments.shared.infrastructure.messaging` & `com.muse.payments.notifications`  
**Feature Branch**: `feat/pr5-messaging`  
**Status**: Delivered — merged in PR #5 (`0ee3ed4`, 2026-11-11); confirms, lifecycle events and DLQ in PR #7  

---

## 1. Objectives

Implement a production-grade event-driven messaging infrastructure using RabbitMQ and Transactional Outbox:
1. **Outbox Poller Relay**: Asynchronous worker running `SELECT ... FOR UPDATE SKIP LOCKED` on `outbox_events` where `published_at IS NULL AND next_attempt_at <= NOW()`.
2. **RabbitMQ Publisher Confirms**: Every event published to RabbitMQ waits for broker confirmation. Upon ack, sets `published_at = NOW()`. Upon nack/failure, increments `attempts`, stores `last_error`, and applies exponential backoff with jitter to `next_attempt_at`.
3. **Topology Design**:
   - Topic Exchange: `payments.events` (durable).
   - Dead Letter Exchange: `payments.events.dlx` (direct, durable).
   - Queue: `payments.created.notifications` (durable, bound to `payments.events` with routing key `payment.created`, with `x-dead-letter-exchange: payments.events.dlx` and `x-dead-letter-routing-key: payment.created.dlq`).
   - DLQ: `payments.created.notifications.dlq` (durable).
4. **Idempotent Consumer (`notifications` module)**:
   - Listens to `payments.created.notifications`.
   - Extracts `eventId` from event payload / headers.
   - Atomically attempts to insert into `processed_events (consumer, event_id)`.
   - If duplicate detected -> acks message immediately and skips duplicate notification processing.
   - If new -> persists notification record into `notifications` table, then acks message.
5. **Observability & Traceability**:
   - `X-Correlation-Id` propagated across HTTP -> Outbox headers -> AMQP message headers -> Consumer MDC.
6. **No Distributed Transactions (2PC)**:
   - Guarantees at-least-once delivery with end-to-end idempotent deduplication at consumer.
