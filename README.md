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
Do not run this JVM on Vercel Functions. No production project is created from this repository.
