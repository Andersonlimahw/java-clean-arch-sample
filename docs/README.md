---
title: Documentation Index
status: active
updated: 2026-11-13
related: [architecture.md, testing.md, rules/refactor-and-validation.md, llms.txt]
---

# Documentation index

Start here for project guidance:
- [AI tooling](ai-tooling.md): universal skills, scaffold, local validation and architecture reviewer.
- [AI tooling evidence](test-plans/ai-tooling-validation.md): disposable CRUD generation, 178 tests and local infrastructure checks.

| Document | Contents |
|---|---|
| [Project README](../README.md) | Overview, stack, local run, test entry points, and demo video. |
| [Architecture](architecture.md) | Clean Architecture layers, dependency rule, CQRS, C4 diagrams, and payment/outbox flow. |
| [HTTP API](api.md) | Endpoint methods, routes, JWT roles, idempotency, pagination, sorting, and problem responses. |
| [Postman](postman.md) | Ordered requests, collection variables, token rotation, payload tests, optional fraud review, and Newman. |
| [Postman execution evidence](test-plans/postman-validation.md) | Real local run: 30 requests, 105 assertions, fraud-review fixtures, and persistence limits. |
| [Refactor gate evidence](test-plans/refactor-validation.md) | Strict local CI, 110 unit tests, 44 integration tests, and the outbox query test race fix. |
| [Data model](data.md) | Flyway V1–V7, event-sourced aggregates, independent hybrid projections, snapshots, credentials and reset/rebuild scripts. |
| [Resilience](resilience.md) | Fraud-screening retry, circuit breaker, bulkhead, time limiter, fallbacks, and review policy. |
| [Concurrency](concurrency.md) | Optimistic locking, transient database locks, virtual threads, cache stampede protection, and outbox claims. |
| [Testing](testing.md) | Unit/integration test commands, Testcontainers, macOS + Colima, Newman, and k6. |
| [Observability](observability.md) | OpenTelemetry/OTLP, Jaeger, Prometheus, Grafana dashboard, and local access. |
| [Messaging](messaging.md) | Outbox relay, RabbitMQ routing, listener retry, idempotent consumer, and DLQ. |
| [Deployment](deploy.md) | Local runbook, deployment targets, migration mode, rollback, and smoke checks. |
| [Architecture decision records](adr/README.md) | Accepted and superseded architecture decisions. |
| [LLM wiki index](llms.txt) | Flat machine-readable inventory of documentation. |
| [Refactor and validation rules](rules/refactor-and-validation.md) | Architecture, incremental Java compile, JSONB assertion, and local-gate rules. |
| [End-to-end demo](demo/e2e-demo.mp4) | Recorded walkthrough; see the [thumbnail](demo/e2e-demo-thumbnail.jpg). |

The operational behavior described here is based on the current source, configuration, migrations, tests, and scripts. Where code and migration comments disagree, the relevant page calls out the discrepancy.
