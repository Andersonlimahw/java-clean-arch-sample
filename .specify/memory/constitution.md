# Payments Platform Constitution

## Core Principles

### I. Domain-Driven Design & Purity
The domain layer (`..domain..`) contains the enterprise business rules and must remain completely decoupled from external technology.
- Domain models depend ONLY on standard JDK classes and `com.muse.payments.shared.domain`.
- No annotations or dependencies on Spring, Jakarta Persistence/Hibernate, Redis, RabbitMQ, Jackson, or Lombok in domain classes.
- Aggregates encapsulate state, enforce business invariants, and raise domain events.

### II. Modular Monolith Boundaries
The application is a single deployable modular monolith structured with vertical slices (`identity`, `payments`, `notifications`, `shared`).
- Modularity rules are strictly verified using Spring Modulith and ArchUnit.
- Cross-module communication is permitted ONLY via explicit module public APIs (`*.api`) or via asynchronous integration events published through the Transactional Outbox.
- No cross-module database foreign keys. Modules maintain autonomy over their persistence schemas.

### III. CQRS & Event-Driven Decoupling
- Command handling (write-side) and Query handling (read-side) are separated.
- Command handlers execute aggregate actions and record domain events without exposing domain entities to web controllers.
- Integration events are appended to `outbox_events` in the exact same database transaction as the aggregate state changes.
- At-least-once delivery with idempotent consumers (`processed_events`) provides effectively-once execution guarantees. Exactly-once claims are prohibited.

### IV. Quality Gate & Evidence-Based Verification
- Every PR must verify: `mvn -B clean verify` (including Testcontainers unit & integration tests) must be completely green.
- Never assert a test or build passes without executing it and observing the output.
- Tests must cover domain logic, optimistic locking conflicts (HTTP 409), validation (HTTP 400), security (HTTP 401/403/404), and idempotency.

### V. Security & Safe Defaults
- All authenticated endpoints are protected via Spring Security Resource Server with RS256 JWT tokens.
- No secrets, real credentials, or private keys committed to Git.
- Passwords hashed using BCrypt.
- RFC 7807 `application/problem+json` for all error responses without stack trace leakage.

## Governance
This Constitution establishes the foundational engineering constraints for the payments-platform repository.
All PRs and automated reviews must comply with these rules.

**Version**: 1.0.0 | **Ratified**: 2026-11-11 | **Last Amended**: 2026-11-11
