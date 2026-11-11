# Tasks: PR2 — Identity Module

- [x] Create Flyway migration `V2__identity.sql`.
- [x] Implement `User` domain aggregate and `UserRepository` port.
- [x] Implement `RefreshToken` domain entity and `RefreshTokenRepository` port.
- [x] Implement application commands, handlers, and response DTOs.
- [x] Implement JPA entities, Spring Data repositories, and infrastructure adapters.
- [x] Implement Redis-backed `AuthRateLimiter` with resilient in-memory fallback.
- [x] Implement `AuthController` with endpoints (`register`, `login`, `refresh`, `logout`).
- [x] Write domain unit tests and `IdentityIT` integration tests (Testcontainers Postgres).
- [x] Verify Modulith boundary rules and ArchUnit purity.
- [x] Run `mvn clean verify` and ensure all tests pass.
