# AGENTS.md — Payments Platform Engineering Guidance

This repository is a production-grade Payments Platform built with Java 25, Spring Boot 3.5.16, Spring Modulith, PostgreSQL 16, Redis 7, RabbitMQ 3.13, and Docker/Kubernetes.

## Architecture Guidelines
- **Modular Monolith**: base package `com.muse.payments`. Modules: `shared`, `identity`, `payments`, `notifications`.
- **Domain Purity**: Domain packages must not reference Spring, JPA, Hibernate, RabbitMQ, Redis, or Jackson.
- **CQRS**: Commands modify state and record events; Queries return read-only records.
- **Transactional Outbox**: Events persisted to `outbox_events` in the aggregate's transaction (`Propagation.MANDATORY`).
- **No Cross-Module DB FKs**: Only intra-module foreign keys are allowed.
- **Security**: RS256 JWT tokens, RFC 7807 `application/problem+json` errors.
- **API auth context**: `CurrentUser` is resolved from the bearer token and must stay hidden from request schemas; see [docs/rules/api-auth-context.md](docs/rules/api-auth-context.md).

## Worktree & Execution Rules
- Worktrees are located under `.worktrees/<feature-name>`.
- Always prefix shell commands with `rtk`.
- Before PR creation or merging, execute: `mvn -B clean verify` (with Docker running for Testcontainers).

## Documentation index
- [AI tooling](docs/ai-tooling.md) — canonical `.agents/skills/`, Claude aliases, Gemini command pointers and read-only reviewer. Entries: `create-crud <resource>`, `test-resource <resource>`, `local-gate`, `run-api-requests`, `flyway-migration <change>`, `add-integration-event <module> <event>`, `docs-sync <feature>`.
- [Postman workflow](docs/postman.md) — maintain request examples and status/payload assertions with every API contract change; keep workflow state in collection variables.
- [Documentation index](docs/README.md) — human entry point for architecture, API, data, resilience, testing, operations, ADRs, and rules.
- [LLM wiki index](docs/llms.txt) — flat machine-readable inventory regenerated after documentation changes.
- [Refactor and validation rules](docs/rules/refactor-and-validation.md) — architecture-boundary, regression-test, local-gate, and documentation invariants.
- [API auth context rule](docs/rules/api-auth-context.md) — keeps `CurrentUser` out of request schemas and OpenAPI bodies.
- [Architecture decisions](docs/adr/README.md) — immutable trade-offs; new cross-cutting decisions use the next ADR number.

Documentation changes must preserve front-matter on non-ADR Markdown files and include an `## Eval` section for feature or operational guidance. Run `rtk proxy bash scripts/validate-docs.sh` and regenerate `docs/llms.txt` before delivery.
