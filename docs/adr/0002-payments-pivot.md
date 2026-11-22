# ADR 0002: Pivot from Trip Planner to Payments Modular Monolith

## Status
Accepted. Supersedes the domain of the retired ADR 0001. Structure is ADR 0003.

## Context
The project was originally ported as a trip-planner modular monolith (`journey` domain). As part of evolving the codebase into a production-grade enterprise system demonstrating DDD, Clean Architecture, CQRS, Transactional Outbox, and high-concurrency invariants, the business domain has been pivoted to a Payments Platform.

## Decision
1. Remove trip-planner modules (`trips`, `participants`, `activities`, `links`).
2. Establish three business modules, plus an open `shared` kernel:
   - `identity`: User registration, authentication, token rotation, and role claims on the access token. `/api/v1/payments` requires `USER` or `ADMIN` ([ADR 0009](0009-jwt-vs-external-idp.md)). At this decision's date the other authenticated routes did not authorize by role; [ADR 0012](0012-fraud-screening-failure-policy.md) later added the `ADMIN`/`FRAUD_REVIEWER` checks on the held-payment list and the review route.
   - `payments`: Payment aggregate root, state machine (PENDING, COMPLETED, CANCELLED; `UNDER_REVIEW` arrived with [ADR 0012](0012-fraud-screening-failure-policy.md)), idempotency key handling, and optimistic locking.
   - `notifications`: Asynchronous notification processing driven by integration events.
   - `shared`: Common domain building blocks, the `OutboxPort`, the `ProcessedEventStore`, security filters, and RFC 7807 problem details.
3. Adopt PostgreSQL 16 as the persistent store with Flyway migrations, Redis 7 for cache-aside reads, RabbitMQ 3.13 for asynchronous messaging, and Spring Modulith + ArchUnit to enforce architectural boundaries.

## Consequences
- Breaking change: Legacy trip-planner endpoints are retired.
- All new REST contracts follow `/api/v1/*`.
- Aggregate writes and outbox rows share one database transaction. Broker delivery is at-least-once, described in [ADR 0006](0006-transactional-outbox.md).
