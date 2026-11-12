# Payments Platform 💳

A production-grade **Payments Modular Monolith** built with **Java 21**, **Spring Boot 3.5.16**, **Spring Modulith**, **PostgreSQL 16**, **Redis 7**, and **RabbitMQ 3.13**.

Architecture follows **Domain-Driven Design (DDD)**, **Clean Architecture**, **CQRS**, and the **Transactional Outbox Pattern** to provide reliable, highly concurrent payment execution and event-driven notifications.

## Architecture

Diagrams, the HTTP contract, and the consistency model: [docs/architecture.md](docs/architecture.md).
Trade-offs: [docs/adr](docs/adr).

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
