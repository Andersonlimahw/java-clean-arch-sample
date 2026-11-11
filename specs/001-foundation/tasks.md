# Tasks: PR1 — Platform Foundation

- [x] Configure Maven pom.xml (dependencies, plugins, testcontainers 1.21.4).
- [x] Initialize specify constitution and AGENTS.md.
- [x] Create Flyway migration `V1__shared_outbox_inbox.sql` with outbox, processed_events, and idempotency_keys.
- [x] Create `application.yml` and test/local profiles.
- [x] Create shared kernel domain classes (`AggregateRoot`, `DomainEvent`, `DomainException`).
- [x] Create shared application CQRS interfaces and `OutboxPort`.
- [x] Create shared outbox JPA adapter with direct `EntityManager.persist()` optimization.
- [x] Create shared security (RS256 JWT, `CurrentUser`, `JwtTokenIssuer`, `typ: access` validator).
- [x] Create shared web components (`CorrelationIdFilter`, RFC 7807 `GlobalExceptionHandler`, OpenAPI).
- [x] Create Application class `PaymentsApplication`.
- [x] Create Testcontainers test infrastructure and integration tests.
- [x] Run `mvn clean verify` and ensure all tests pass.
