# Documentation index

Start here for project guidance:

| Document | Contents |
|---|---|
| [Project README](../README.md) | Overview, stack, local run, test entry points, and demo video. |
| [Architecture](architecture.md) | Clean Architecture layers, dependency rule, CQRS, C4 diagrams, and payment/outbox flow. |
| [HTTP API](api.md) | Endpoint methods, routes, JWT roles, idempotency, pagination, sorting, and problem responses. |
| [Data model](data.md) | Flyway V1–V7, indexes, constraints, and the concurrent V6 index migration. |
| [Resilience](resilience.md) | Fraud-screening retry, circuit breaker, bulkhead, time limiter, fallbacks, and review policy. |
| [Concurrency](concurrency.md) | Optimistic locking, transient database locks, virtual threads, cache stampede protection, and outbox claims. |
| [Testing](testing.md) | Unit/integration test commands, Testcontainers, macOS + Colima, Newman, and k6. |
| [Observability](observability.md) | OpenTelemetry/OTLP, Jaeger, Prometheus, Grafana dashboard, and local access. |
| [Messaging](messaging.md) | Outbox relay, RabbitMQ routing, listener retry, idempotent consumer, and DLQ. |
| [Deployment](deploy.md) | Local runbook, deployment targets, migration mode, rollback, and smoke checks. |
| [Architecture decision records](adr/README.md) | Accepted and superseded architecture decisions. |
| [End-to-end demo](demo/e2e-demo.mp4) | Recorded walkthrough; see the [thumbnail](demo/e2e-demo-thumbnail.jpg). |

The operational behavior described here is based on the current source, configuration, migrations, tests, and scripts. Where code and migration comments disagree, the relevant page calls out the discrepancy.
