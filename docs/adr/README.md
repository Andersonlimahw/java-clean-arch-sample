# Architecture decision records

Operational behavior that these decisions refer to is in [../architecture.md](../architecture.md). Broker delivery is also in [../messaging.md](../messaging.md). Metrics, traces, and logs are in [../observability.md](../observability.md). When a number differs, the architecture page is the one checked against the code.

For the full guide index, see [../README.md](../README.md). Implementation guides: [API](../api.md), [data](../data.md), [resilience](../resilience.md), [concurrency](../concurrency.md), [testing](../testing.md), and [deployment](../deploy.md).

| ADR | Title | Status |
|---|---|---|
| [0002](0002-payments-pivot.md) | Pivot from trip planner to payments | Accepted. Supersedes the retired 0001 domain. Structure is 0003. |
| [0003](0003-modular-monolith-vs-microservices.md) | Modular monolith instead of microservices | Accepted. Restates 0001's structure for payments. |
| [0005](0005-rabbitmq-vs-kafka.md) | RabbitMQ instead of Kafka | Accepted |
| [0006](0006-transactional-outbox.md) | Transactional outbox | Accepted |
| [0007](0007-eventual-consistency.md) | Eventual consistency for notifications | Accepted |
| [0008](0008-redis-cache-invalidation.md) | Redis cache-aside and invalidation | Accepted |
| [0009](0009-jwt-vs-external-idp.md) | First-party RS256 JWT | Accepted |
| [0010](0010-compose-vs-kubernetes.md) | Compose for local, Kubernetes for production shape | Accepted |
| [0011](0011-railway-vs-other-hosting.md) | Railway as the primary host | Accepted |
| [0012](0012-fraud-screening-failure-policy.md) | Fraud screening never approves on failure | Accepted |
| [0013](0013-java-25-lts.md) | Java 25 LTS | Accepted |
| [0014](0014-web-boundary-and-strict-local-gate.md) | Application boundaries and strict local validation | Accepted |
| [0015](0015-virtual-thread-hot-paths.md) | Virtual-thread-aware hot paths and batched outbox relay | Accepted |
| [0016](0016-event-sourcing-with-hybrid-projections.md) | Event sourcing with hybrid projections | Accepted. Supersedes the retired 0004. |
| [0017](0017-async-projection-position-fence.md) | Safe positional checkpoints across transaction ordering | Accepted. Complements 0016. |
| [0018](0018-production-surface-and-operational-hardening.md) | Production surface, key rotation, rate limiting, and operational retention | Accepted |
| [0019](0019-psp-provider-strategy-and-webhooks.md) | Provider strategy port and signed PSP webhooks | Accepted |
| [0020](0020-crypto-shredding-and-kek-management.md) | Crypto-shredding for erasure and KEK management | Accepted |

## Retired records

Two records were removed once their subject left the codebase. Their full text stays in git history; nothing in the current code matches them.

| Record | Retired | Why |
|---|---|---|
| 0001 — Modular monolith with vertical slices | 2026-11-21 | Described `java-journey-muse-lab`, the trip-planner repository this project was ported from, including `TripController` and the trips/participants/activities/links slices. That domain is gone ([ADR 0002](0002-payments-pivot.md)) and the structure is restated for payments in [ADR 0003](0003-modular-monolith-vs-microservices.md). |
| 0004 — CQRS without event sourcing | 2026-11-21 | Chose current-state rows over an event log. [ADR 0016](0016-event-sourcing-with-hybrid-projections.md) replaced it: every aggregate appends to `event_store`, and `payments`, `users`, `refresh_tokens`, `payment_history`, and `notifications` are projections. |
