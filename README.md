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
```bash
docker compose up -d postgres redis rabbitmq
mvn spring-boot:run
```

API documentation available at `http://localhost:8080/swagger-ui.html`.
