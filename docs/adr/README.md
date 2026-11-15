# Architecture decision records

Operational behavior that these decisions refer to is in [../architecture.md](../architecture.md). Broker delivery is also in [../messaging.md](../messaging.md). Metrics, traces, and logs are in [../observability.md](../observability.md). When a number differs, the architecture page is the one checked against the code.

For the full guide index, see [../README.md](../README.md). Implementation guides: [API](../api.md), [data](../data.md), [resilience](../resilience.md), [concurrency](../concurrency.md), [testing](../testing.md), and [deployment](../deploy.md).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith-vertical-slices.md) | Modular monolith with vertical slices (trip planner) | Superseded by [0002](0002-payments-pivot.md) (domain) and [0003](0003-modular-monolith-vs-microservices.md) (structure) |
| [0002](0002-payments-pivot.md) | Pivot from trip planner to payments | Accepted. Supersedes the domain of 0001. Structure is 0003. |
| [0003](0003-modular-monolith-vs-microservices.md) | Modular monolith instead of microservices | Accepted. Supersedes 0001's structure. |
| [0004](0004-cqrs-without-event-sourcing.md) | CQRS without event sourcing | Superseded by [0016](0016-event-sourcing-with-hybrid-projections.md) |
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
| [0016](0016-event-sourcing-with-hybrid-projections.md) | Event sourcing with hybrid projections | Accepted. Supersedes 0004. |
