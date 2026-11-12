# ADR 0002: Pivot from Trip Planner to Payments Modular Monolith

## Status
Accepted (Supersedes ADR 0001)

## Context
The project was originally ported as a trip-planner modular monolith (`journey` domain). As part of evolving the codebase into a production-grade enterprise system demonstrating DDD, Clean Architecture, CQRS, Transactional Outbox, and high-concurrency invariants, the business domain has been pivoted to a Payments Platform.

## Decision
1. Remove trip-planner modules (`trips`, `participants`, `activities`, `links`).
2. Establish a new domain composed of three core modules:
   - `identity`: User registration, authentication, token rotation, and RBAC.
   - `payments`: Payment aggregate root, state machine (PENDING, COMPLETED, CANCELLED), idempotency key handling, and optimistic locking.
   - `notifications`: Asynchronous notification processing driven by integration events.
   - `shared`: Common domain building blocks, the `OutboxPort`, the `ProcessedEventStore`, security filters, and RFC 7807 problem details.
3. Adopt PostgreSQL 16 as the persistent store with Flyway migrations, Redis 7 for cache-aside reads, RabbitMQ 3.13 for asynchronous messaging, and Spring Modulith + ArchUnit to enforce architectural boundaries.

## Consequences
- Breaking change: Legacy trip-planner endpoints are retired.
- All new REST contracts follow `/api/v1/*`.
- Aggregate writes and outbox rows share one database transaction. Broker delivery is at-least-once, described in [ADR 0006](0006-transactional-outbox.md).
