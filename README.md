# Payments Platform 💳

A production-grade **Payments Modular Monolith** built with **Java 21**, **Spring Boot 3.5.16**, **Spring Modulith**, **PostgreSQL 16**, **Redis 7**, and **RabbitMQ 3.13**.

Architecture follows **Domain-Driven Design (DDD)**, **Clean Architecture**, **CQRS**, and the **Transactional Outbox Pattern** to provide reliable, highly concurrent payment execution and event-driven notifications.

## Architecture

Diagrams, the HTTP contract, and the consistency model: [docs/architecture.md](docs/architecture.md).
Trade-offs: [docs/adr](docs/adr/README.md).

- **Modular Monolith**: base package `com.muse.payments` with verifiable architectural boundaries.
  - `identity`: User registration, authentication, token rotation, and role claims on the access token. Payment routes require `USER` or `ADMIN`. Other authenticated routes do not authorize by role.
  - `payments`: Payment aggregate root, state machine (PENDING → COMPLETED / CANCELLED), idempotency key deduplication, and optimistic locking.
  - `notifications`: Event-driven notification dispatch via RabbitMQ with idempotent consumer (`processed_events`).
  - `shared`: Common domain building blocks, the outbox port, the processed-event store, security filters, and RFC 7807 problem details.
- **Persistence**: PostgreSQL 16 managed via Flyway append-only migrations.
- **Cache**: Redis 7 cache-aside for payment lookups with safe fallbacks.
- **Messaging**: RabbitMQ topic exchange `payments.events`. Created, completed, and cancelled events share `payments.created.notifications`, with listener retry, a dead-letter queue, and publisher confirms on the outbox relay. See [docs/architecture.md](docs/architecture.md).
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
Start PostgreSQL, Redis, and RabbitMQ, then the application, from the repository root as described in [docs/deploy.md](docs/deploy.md). That runbook sources `.env.example` so `DB_PASSWORD` and `REDIS_PASSWORD` match Compose. `mvn spring-boot:run` stays in the foreground. From a second shell, after the process is booting:

```bash
./scripts/validate-api.sh
```

The script polls readiness, then walks register, login, payment create/confirm, and notifications. API documentation is at `http://localhost:8080/swagger-ui.html`. The same statuses are in `src/test/postman/payments-v1.postman_collection.json`.

### Kubernetes migration Job

The Job in `deploy/k8s/base/flyway-job.yaml` is named `payments-flyway-migration` and sets `APP_MIGRATION_ONLY=true`. The process applies Flyway, does not serve HTTP, and does not start Rabbit consumers or the outbox scheduler. It exits `0` only after migrations are applied and nothing is pending, and exits non-zero if startup or Flyway fails. Replicas inherit `SPRING_FLYWAY_ENABLED=false` from the ConfigMap and leave `APP_MIGRATION_ONLY` unset, so they do not migrate and they keep serving traffic.

Local migration against Compose dependencies, without starting the HTTP server:

```bash
APP_MIGRATION_ONLY=true mvn spring-boot:run
```

## Deploy

Runbooks for Railway (preferred), Render, and Cloud Run, plus rollback and troubleshooting: [docs/deploy.md](docs/deploy.md).
Kubernetes layout and apply order: [deploy/k8s/README.md](deploy/k8s/README.md) and `scripts/k8s-apply.sh`.
HTTP smoke test: `scripts/validate-api.sh`.
Do not run this JVM on Vercel Functions. That decision is [ADR 0011](docs/adr/0011-railway-vs-other-hosting.md). No production project is created from this repository.
