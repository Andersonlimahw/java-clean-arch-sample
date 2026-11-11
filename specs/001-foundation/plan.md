# Plan: PR1 — Platform Foundation

1. Structure `src/main/resources/application.yml` and profiles (`local`, `test`, `prod`).
2. Implement Flyway migration `V1__shared_outbox_inbox.sql`.
3. Implement `com.muse.payments.PaymentsApplication` annotated with `@SpringBootApplication`.
4. Implement `com.muse.payments.shared`:
   - `domain`: `AggregateRoot`, `DomainEvent`, `DomainException` (NotFound, Conflict, Validation).
   - `application`: `Command`, `CommandHandler`, `Query`, `QueryHandler`, `OutboxPort`, `IntegrationEvent`.
   - `infrastructure`: `OutboxEventEntity`, `OutboxJpaRepository`, `OutboxJpaAdapter`.
   - `security`: `SecurityConfig`, `JwtProperties`, `JwtKeyConfig`, `JwtTokenIssuer`, `CurrentUser`.
   - `web`: `CorrelationIdFilter`, `GlobalExceptionHandler`, `OpenApiConfig`.
5. Implement tests:
   - `TestcontainersBase`: reusable PostgreSQL container.
   - `PaymentsApplicationIT`: Boot context + outbox transaction rollback test.
   - `ArchitectureTest`: ArchUnit checks.
   - `ModularityTest`: Spring Modulith verification.
   - `SecurityWebTest`: 401 on unauthenticated, correlation ID propagation.
6. Local verification via `mvn -B clean verify`.
