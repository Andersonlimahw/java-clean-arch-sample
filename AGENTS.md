# AGENTS.md — Payments Platform Engineering Guidance

This repository is a production-grade Payments Platform built with Java 25, Spring Boot 3.5.16, Spring Modulith, PostgreSQL 16, Redis 7, RabbitMQ 3.13, and Docker/Kubernetes.

## Architecture Guidelines
- **Modular Monolith**: base package `com.muse.payments`. Modules: `shared`, `identity`, `payments`, `notifications`.
- **Domain Purity**: Domain packages must not reference Spring, JPA, Hibernate, RabbitMQ, Redis, or Jackson.
- **CQRS**: Commands modify state and record events; Queries return read-only records.
- **Transactional Outbox**: Events persisted to `outbox_events` in the aggregate's transaction (`Propagation.MANDATORY`).
- **No Cross-Module DB FKs**: Only intra-module foreign keys are allowed.
- **Security**: RS256 JWT tokens, RFC 7807 `application/problem+json` errors.

## Worktree & Execution Rules
- Worktrees are located under `.worktrees/<feature-name>`.
- Always prefix shell commands with `rtk`.
- Before PR creation or merging, execute: `mvn -B clean verify` (with Docker running for Testcontainers).
