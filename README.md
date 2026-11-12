# Payments Platform 💳

A production-grade **Payments Modular Monolith** built with **Java 21**, **Spring Boot 3.5.16**, **Spring Modulith**, **PostgreSQL 16**, **Redis 7**, and **RabbitMQ 3.13**.

Architecture follows **Domain-Driven Design (DDD)**, **Clean Architecture**, **CQRS**, and the **Transactional Outbox Pattern** to provide reliable, highly concurrent payment execution and event-driven notifications.

## Architecture

- **Modular Monolith**: base package `com.muse.payments` with verifiable architectural boundaries.
  - `identity`: User registration, authentication, token rotation, and RBAC.
  - `payments`: Payment aggregate root, state machine (PENDING → COMPLETED / CANCELLED), idempotency key deduplication, and optimistic locking.
  - `notifications`: Event-driven notification dispatch via RabbitMQ with idempotent consumer (`processed_events`).
  - `shared`: Common domain building blocks, transactional outbox/inbox ports, security filters, and RFC 7807 problem details.
- **Persistence**: PostgreSQL 16 managed via Flyway append-only migrations.
- **Cache**: Redis 7 cache-aside for payment lookups with safe fallbacks.
- **Messaging**: RabbitMQ with topic exchanges, DLQ, exponential retry with jitter, and Transactional Outbox.
- **Security**: Stateless Spring Security Resource Server with RS256 JWT tokens.

## Authorization

Roles travel in the JWT `roles` claim and are enforced as `ROLE_USER` and `ROLE_ADMIN`. A missing or invalid token is **401**. A known token whose role is not allowed is **403** `application/problem+json`. Ownership misses on payments stay **404** so callers cannot tell whether another user's payment exists.

| Endpoint | Who can call it | Ownership |
|---|---|---|
| `POST /api/v1/auth/**` | Public | — |
| `POST /api/v1/payments` | `USER` or `ADMIN` | The caller owns the payment that is created |
| `GET /api/v1/payments/{id}` | `USER` or `ADMIN` | Owner, or any payment when the caller is `ADMIN`. Another user's payment is **404** |
| `GET /api/v1/payments` | `USER` or `ADMIN` | Always the caller's own payments, including for `ADMIN` |
| `POST /api/v1/payments/{id}/confirm` | `USER` or `ADMIN` | Owner only. Another user's payment is **404** |
| `POST /api/v1/payments/{id}/cancel` | `USER` or `ADMIN` | Owner only. Another user's payment is **404** |
| `GET /api/v1/notifications` | Any authenticated user | The caller's own notifications |
| Actuator health, info, prometheus, and OpenAPI | Public | — |

`Idempotency-Key` on `POST /api/v1/payments` is kept for 24 hours. After `expires_at`, the same key is a new request. A failed create releases that attempt so the key can be retried. Completing a key updates only the unexpired `PROCESSING` row for the lease acquired by that attempt. Each cleanup run deletes expired rows in batches of 500 (`FOR UPDATE SKIP LOCKED`) until none remain; the run interval is `app.idempotency.cleanup-interval-ms` (default 1 hour). The lookup index is `idx_idempotency_expires` from `V1__shared_outbox_inbox.sql`.

## Quickstart

### Prerequisites
- JDK 21+
- Maven 3.9+
- Docker Engine (for Testcontainers and local compose)

### Running tests
```bash
mvn clean verify
```

### Running locally
Local integration tests run automatically against ephemeral containers via Testcontainers (`mvn clean verify`).
To run full local platform services with Docker Compose (app, postgres, redis, rabbitmq), see the infrastructure stack delivered in [PR #2 (Platform Infra)](https://github.com/Andersonlimahw/java-clean-arch-sample/pull/2):
```bash
docker compose up -d postgres redis rabbitmq
mvn spring-boot:run
```

API documentation available at `http://localhost:8080/swagger-ui.html`.

### Kubernetes migration Job

The Job in `deploy/k8s/base/flyway-job.yaml` sets `APP_MIGRATION_ONLY=true`. The process applies Flyway, does not serve HTTP, and does not start Rabbit consumers or the outbox scheduler. It exits `0` only after migrations are applied, and exits non-zero if startup or Flyway fails. Replicas inherit `SPRING_FLYWAY_ENABLED=false` from the ConfigMap and leave `APP_MIGRATION_ONLY` unset, so they do not migrate and they keep serving traffic.

Local migration against Compose dependencies, without starting the HTTP server:

```bash
APP_MIGRATION_ONLY=true mvn spring-boot:run
```
