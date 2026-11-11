# Spec: PR1 — Platform Foundation

## Objective
Establish the architectural foundation, core domain building blocks, transactional outbox infrastructure, security baseline (RS256 JWT resource server), error handling (RFC 7807), and architecture verification tests (Spring Modulith + ArchUnit) for the Payments Platform.

## Requirements
1. Remove all legacy trip-planner domain artifacts.
2. Configure Spring Boot 3.5.16, Spring Modulith, PostgreSQL, Flyway, Testcontainers, and Spring Security.
3. Implement `com.muse.payments.shared`:
   - Domain: `AggregateRoot`, `DomainEvent`, `DomainException` hierarchy.
   - Application: `Command`, `CommandHandler`, `Query`, `QueryHandler`, `OutboxPort`.
   - Infrastructure: `OutboxJpaAdapter` writing to `outbox_events` (Flyway migration `V1__shared_outbox_inbox.sql`).
   - Security: Stateless `SecurityFilterChain`, `JwtTokenIssuer`, RS256 `JwtDecoder`/`JwtEncoder` with ephemeral keys for test/local.
   - Web: `CorrelationIdFilter` (`X-Correlation-Id`), RFC 7807 `GlobalExceptionHandler`.
4. Verification:
   - ArchUnit rules for domain purity and module isolation.
   - Spring Modulith module verification test.
   - Integration test with Testcontainers verifying PostgreSQL, Outbox transaction participation, and Security endpoints.
