# Tasks: PR1 — Platform Foundation

- [x] Configure Maven pom.xml (dependencies, plugins, testcontainers 1.21.4).
- [x] Initialize specify constitution and AGENTS.md.
- [ ] Create Flyway migration `V1__shared_outbox_inbox.sql`.
- [ ] Create `application.yml` and test/local profiles.
- [ ] Create shared kernel domain classes.
- [ ] Create shared application CQRS interfaces and OutboxPort.
- [ ] Create shared outbox JPA adapter.
- [ ] Create shared security (RS256 JWT, CurrentUser, JwtTokenIssuer).
- [ ] Create shared web components (CorrelationIdFilter, RFC 7807 GlobalExceptionHandler, OpenAPI).
- [ ] Create Application class `PaymentsApplication`.
- [ ] Create Testcontainers test infrastructure and integration tests.
- [ ] Run `mvn clean verify` and ensure all tests pass.
