# Architecture — Payments Platform

One Spring Boot process (`com.muse.payments.PaymentsApplication`) on Java 21.
Modules: `identity`, `payments`, `notifications`, and an open `shared` kernel. Open means a Spring Modulith module of type `OPEN`: the other modules may depend on it. It holds ports and cross-cutting types, not payment rules.
The trip-planner slices in ADR 0001 are retired. See [ADR 0002](adr/0002-payments-pivot.md) and [ADR 0003](adr/0003-modular-monolith-vs-microservices.md).

## System context (C4 L1)

```mermaid
flowchart LR
    Client["Client<br/>HTTP JSON"] -->|":8080 /api/v1"| API["payments-platform<br/>Spring Boot 3.5.16 · Java 21"]
    API --> PG[("PostgreSQL 16<br/>source of truth")]
    API --> Redis[("Redis 7<br/>cache-aside")]
    API --> MQ["RabbitMQ 3.13<br/>payments.events"]
```

## Containers (C4 L2)

The application is one deployable. Redis, PostgreSQL, and RabbitMQ are separate processes.

```mermaid
flowchart LR
    API["payments-platform.jar"] --> PG[("PostgreSQL")]
    API --> Redis[("Redis")]
    API --> MQ["RabbitMQ"]
```

## Components (C4 L3)

```mermaid
flowchart TB
    API["payments-platform.jar"]
    API --> ID["identity"]
    API --> PAY["payments"]
    API --> NTF["notifications"]
    API --> SH["shared"]
    PAY -->|"outbox row, same transaction"| PG[("PostgreSQL")]
    ID --> PG
    NTF --> PG
    SH -->|relay| MQ["RabbitMQ"]
    MQ -->|"notifications queue"| NTF
    PAY -->|GET by id| Redis[("Redis")]
    ID -->|auth rate limit| Redis
```

## Module map

| Module | Package | Owns | Talks to others |
|---|---|---|---|
| identity | `com.muse.payments.identity` | `users`, `refresh_tokens` | Issues JWT used by the other HTTP APIs. No foreign keys into payments. |
| payments | `com.muse.payments.payments` | `payments` | Appends integration events to `outbox_events`. Does not call the notifications repository. |
| notifications | `com.muse.payments.notifications` | `notifications` | Learns about payments only from RabbitMQ. |
| shared | `com.muse.payments.shared` | `outbox_events`, `processed_events`, `idempotency_keys` | Depended on by identity, payments, and notifications. Not a business module. |

`identity` and `payments` use `domain`, `application`, `infrastructure`, and `web`.
`notifications` has `domain`, `infrastructure`, and `web`, and no `application` package.
`NotificationController` calls `NotificationRepository` and returns the domain type `Notification`.
`domain` is plain Java: `ArchitectureTest` allows only the JDK and other `domain` packages.
Payment and identity commands, and the payment queries, go through application handlers. Identity has no query types.
Infrastructure holds JPA, Redis, and RabbitMQ adapters.

There is no cross-module foreign key. `payments.user_id` and `notifications.user_id` are UUID columns plus indexes. `refresh_tokens.user_id` references `users` because both tables belong to identity.

## Request paths

```mermaid
sequenceDiagram
    participant C as Client
    participant A as identity
    participant P as payments
    participant DB as PostgreSQL
    participant R as Outbox relay
    participant Q as RabbitMQ
    participant N as notifications
    C->>A: POST /api/v1/auth/login
    A-->>C: access JWT + refresh token
    C->>P: POST /api/v1/payments (Bearer, Idempotency-Key)
    P->>DB: commit idempotency lease
    P->>P: fraud screening, no database transaction
    P->>DB: commit payment + outbox + idempotent response
    P-->>C: 201 PaymentResponse
    R->>DB: SELECT FOR UPDATE SKIP LOCKED
    R->>Q: publish payment.created
    Q->>N: deliver
    N->>DB: INSERT notification if event_id is new
```

`GET /api/v1/payments/{id}` is cache-aside in Redis. PostgreSQL is read on a miss.
Confirm and cancel evict the key after the database transaction commits.
The diagram is the create path. Confirm and cancel append their own outbox rows and are delivered on the same queue. The relay commits the claim, then waits for the publisher confirm, before it sets `published_at`.

## HTTP contract

Base path `/api/v1`. Errors are RFC 7807 `application/problem+json` and omit the stack trace. `CorrelationIdFilter` accepts `X-Correlation-Id` when it is 1–64 characters from `[A-Za-z0-9_-]`, otherwise generates a UUID, echoes that value on the response header, and copies it onto the problem body as `correlationId`.

| Method | Path | Auth | Success |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | 201 |
| POST | `/api/v1/auth/login` | public | 200 |
| POST | `/api/v1/auth/refresh` | public | 200, rotated pair |
| POST | `/api/v1/auth/logout` | public | 204 |
| POST | `/api/v1/payments` | Bearer, `USER` or `ADMIN` | 201, requires `Idempotency-Key`. The caller owns the payment |
| GET | `/api/v1/payments/{id}` | Bearer, `USER`, `ADMIN`, or `FRAUD_REVIEWER` | 200 for the owner, or for any payment when the caller is `ADMIN` or `FRAUD_REVIEWER` |
| GET | `/api/v1/payments` | Bearer, `USER` or `ADMIN` | 200, the caller's own payments, including when the caller is `ADMIN` |
| GET | `/api/v1/payments/under-review` | Bearer, `ADMIN` or `FRAUD_REVIEWER` | 200, payments in `UNDER_REVIEW`. Same `page`, `size`, and `sort` rules as the owner list |
| POST | `/api/v1/payments/{id}/confirm` | Bearer, `USER` or `ADMIN`, owner | 200. Another user's payment is 404, including for `ADMIN`. `UNDER_REVIEW` is 409 |
| POST | `/api/v1/payments/{id}/review` | Bearer, `ADMIN` or `FRAUD_REVIEWER`, not the owner | 200. Query `decision=APPROVE` or `REJECT`. The owner is 403, including an `ADMIN` who created the payment |
| POST | `/api/v1/payments/{id}/cancel` | Bearer, `USER` or `ADMIN`, owner | 200. Another user's payment is 404, including for `ADMIN` |
| GET | `/api/v1/notifications` | Bearer | 200, caller's notifications |

Status codes the handlers return:

| Status | When |
|---|---|
| 400 | Bean validation, malformed JSON, missing `Idempotency-Key`, a key longer than 120 characters, an invalid payment list `page`, `size`, or `sort`, or a typed parameter that does not convert (including a payment `id` that is not a UUID). `application/problem+json` |
| 401 | Missing or invalid bearer token, bad login or refresh credentials, or a disabled account (`BadCredentialsException` / `AuthenticationException`). `application/problem+json` |
| 403 | Authenticated token that does not include `USER` or `ADMIN` on `/api/v1/payments` and `/api/v1/payments/**`, or that is not `ADMIN` or `FRAUD_REVIEWER` on `POST /api/v1/payments/{id}/review`. Also the reviewer who owns the payment. `application/problem+json` |
| 404 | Unknown payment id, or a payment the caller does not own. `ADMIN` may get any payment by id. List, confirm, and cancel stay owner-only, so a non-owner `ADMIN` is still 404 on confirm and cancel |
| 409 | Illegal payment transition, duplicate registration email, idempotency payload mismatch, idempotency key still in `PROCESSING` and unexpired, or `DataIntegrityViolationException`. These use `type` `urn:problem-type:409`. An optimistic lock uses the same status and the title `Concurrent Modification`, with `type` `urn:problem-type:concurrent-modification` |
| 422 | Explicit fraud decline. The body is `Payment was declined by fraud screening.` The provider reason is not included. The same key replays this response |
| 429 | Register, login, and refresh over 20 requests per minute for `request.getRemoteAddr()`. `Retry-After` is `60`. Logout has no limit. The app does not read `X-Forwarded-For` itself |
| 503 | Transient database lock: deadlock (`40P01` → `PessimisticLockingFailureException`) or serialization failure (`40001` → `CannotAcquireLockException`). `type` is `urn:problem-type:transient-lock` and `Retry-After` is `1`. Retry the same request |
| 500 | Unhandled exception, generic problem detail, no stack trace |

Roles travel in the JWT `roles` claim and are enforced as `ROLE_USER`, `ROLE_ADMIN`, and `ROLE_FRAUD_REVIEWER`. The payment filter chain requires `USER` or `ADMIN`, except `POST /api/v1/payments/{id}/review`, which requires `ADMIN` or `FRAUD_REVIEWER`. A missing or invalid token is 401. A known token without them is 403 `application/problem+json`. An ownership miss stays 404 so callers cannot tell whether another user's payment exists. `GET /api/v1/notifications` stays any authenticated user and owner-scoped.

`server.forward-headers-strategy` is `native`, so Tomcat's `RemoteIpValve` copies `X-Forwarded-For` into the remote address only when the immediate peer matches `server.tomcat.remoteip.internal-proxies` (private ranges, including localhost and typical ingress networks). A client that connects directly cannot pick its rate-limit key by sending the header. If the edge proxy's source address is public, add it to that property.

Optimistic locking covers the read-modify-write inside the confirm or cancel transaction. There is no `ETag` or `If-Match`. A second request that starts after the winner commits is rejected by the state machine (`urn:problem-type:409`), not by the version check.

A repeated create with the same user, unexpired `Idempotency-Key`, and canonical body (amount scaled to 2 decimals, currency uppercased) returns the stored response and sets `Idempotent-Replayed: true`. A completed row's `expires_at` is 24 hours ahead. A `PROCESSING` lease expires after `app.idempotency.processing-ttl` (default 2 minutes) and can be reclaimed. Lookup of a completed response requires `expires_at > now`. After that, the same key is a new payment. Create commits the idempotency lease in its own transaction, calls the fraud provider with no database connection held, then commits the payment, the outbox row, and the cached response together. An explicit decline commits a 422 problem body on that lease instead of a payment. Any other failure deletes the `PROCESSING` lease. Completion extends `expires_at` to 24 hours and updates only the unexpired `PROCESSING` row for that attempt's `lease_id`. Replaying the key of a payment created as `UNDER_REVIEW` returns that original 201 body even after a reviewer approves the payment. Each cleanup run deletes expired rows in batches of 500 (`FOR UPDATE SKIP LOCKED`) until a short batch remains (`app.idempotency.cleanup-interval-ms`, default 1 hour). Cancel takes an optional `reason` query parameter; the default is `Customer requested cancellation`. List payments is an offset page: `page` (default 0, must be >= 0), `size` (default 20, must be from 1 to 100), and `sort`. `page * size` must be <= 10000. `sort` is case-sensitive: `createdAt,desc` (default; `created_at DESC, id DESC`) or `createdAt,asc` (`created_at ASC, id ASC`). Spring joins repeated `sort` parameters with a comma, so `sort=createdAt&sort=desc` matches the default and two full values do not. A negative page, a size outside 1..100, a non-integer page or size, an offset above 10000, and any other sort, including `createdAt,DESC`, are HTTP 400 and are not clamped. That is a breaking change for clients that sent `size=0` or `size` above 100 and previously received a clamped page. Omitting the three parameters stays compatible. The body keeps `items`, `page`, `size`, `totalElements`, and `totalPages`, and adds `sort`. Order is stable for a static dataset. A concurrent insert or delete can shift a later offset page, and `totalElements` can disagree with `items`, because the page and the count are two reads. The list index is `idx_payments_user_created_at_id` from `V6__payments_list_index.sql` (`PAYMENTS_LIST_INDEX_VERSION`). See [perf/README.md](../perf/README.md).

OpenAPI: `/v3/api-docs`. Swagger UI: `/swagger-ui.html`.

## Consistency and delivery

Create, confirm, and cancel commit the aggregate and the outbox row in one transaction.
`OutboxJpaAdapter.append` is `@Transactional(propagation = MANDATORY)`. `OutboxPort` itself is a plain interface.
The append stores `correlationId` and the current W3C `traceparent` on the outbox headers.
Confirm and cancel follow the same relay and the same notifications queue as create.

The relay (`OutboxRelayService`) is scheduled when `app.outbox.scheduling.enabled` is unset or `true` (`SchedulingConfig`). The schedule is a fixed delay of `app.outbox.poll-interval-ms` milliseconds (default 1000). Each tick handles up to 50 due rows, one claim transaction at a time. The claim is `SELECT … LIMIT 1 … FOR UPDATE SKIP LOCKED` where `published_at IS NULL` and `next_attempt_at <= now`. It then sets `next_attempt_at` to now plus `app.messaging.confirm-timeout` (default 5s) plus 10 seconds, and commits. The row lock is released before the broker call. Another relay skips that row until the lease expires.

Each attempt publishes to the topic exchange `payments.events` with its own confirm correlation id. `spring.rabbitmq.publisher-confirm-type` is `correlated`, `publisher-returns` is true, and the `RabbitTemplate` is mandatory. The outbox id travels as `X-Event-Id`. The relay restores the stored `traceparent` before `convertAndSend`. A short transaction sets `published_at` only after the broker acks and the attempt has no return. `attempts` stays unchanged on that success. A nack, a return, a confirm timeout, or a send exception increments `attempts`, stores `last_error` (truncated at 500 characters), and sets `next_attempt_at` to now plus `min(300, 2^min(attempts, 8) * 2 + jitter)` seconds. Jitter is an integer from 1 through 3 (`PublishRetryBackoff`). The row stays unpublished until a later attempt succeeds. A crash after the broker accepts the message and before `published_at` commits publishes a duplicate once the lease expires. An interrupt stops the rest of that tick and leaves the interrupt flag set.

| Outbox `event_type` | Routing key | Queue |
|---|---|---|
| `PaymentCreated` | `payment.created` | `payments.created.notifications` |
| `PaymentHeldForReview` | `payment.held` | `payments.created.notifications` |
| `PaymentCompleted` | `payment.completed` | `payments.created.notifications` |
| `PaymentCancelled` | `payment.cancelled` | `payments.created.notifications` |

The queue name and the dead-letter routing key `payment.created.dlq` stayed from the created-only topology. Completed and cancelled are additional bindings on that queue, and every notification reject uses the same dead-letter key. The dead-letter exchange is the durable direct exchange `payments.events.dlx`, bound to the durable queue `payments.created.notifications.dlq`. The main queue sets no message TTL and no max length.

`PaymentNotificationConsumer` listens on `payments.created.notifications`. It inserts the notification and a `processed_events` row (`consumer = PaymentNotificationConsumer`, primary key `(consumer, event_id)`) in one transaction. The event id is `X-Event-Id`, or a body `eventId` when the header is absent. `paymentId` is not an event id. A second delivery of the same event id is acknowledged and does not insert a second notification. `PaymentCreated`, `PaymentHeldForReview`, `PaymentCompleted`, and `PaymentCancelled` each create a notification (`PAYMENT_CREATED`, `PAYMENT_HELD_FOR_REVIEW`, `PAYMENT_COMPLETED`, `PAYMENT_CANCELLED`). `PaymentHeldForReview` says the payment is under review and was not approved. `PaymentCreated` is emitted only for a payment that screening cleared, or later when a review releases a held payment to `PENDING`.

Payment status:

| Status | Meaning | Confirm | Cancel | Review |
|---|---|---|---|---|
| `PENDING` | Screening cleared the payment. It is not completed yet. | to `COMPLETED` | to `CANCELLED` | rejected |
| `UNDER_REVIEW` | The provider did not return a decision. This is not an approval. The screening reason is stored on the row and is not returned to the client. | 409 | to `CANCELLED` | `ADMIN` or `FRAUD_REVIEWER` who is not the owner. `APPROVE` moves it to `PENDING` and clears the reason; `REJECT` cancels it with the reviewer's reason |
| `COMPLETED` | Confirmed. | 409 | 409 | rejected |
| `CANCELLED` | Cancelled by the owner or rejected in review. | 409 | 409 | rejected |

`screening_reason`, `held_reason`, `reviewer_id`, and `reviewed_at` are added by `V7__payment_screening_reason.sql`, after the list index in `V6__payments_list_index.sql`. Approval clears `screening_reason` and keeps `held_reason`. The fraud provider is an HTTP `POST /v1/screenings` behind `FraudScreeningPort`. Resilience4j (retry, circuit breaker, bulkhead, time limiter) lives only in the infrastructure adapter. The instance name is `fraudScreening`. `FRAUD_SCREENING_BASE_URL` is required and must be `https` on the `prod` profile. The base Kubernetes ConfigMap does not set it. Local Compose runs a WireMock that answers `CLEAR`. Failure policy is [ADR 0012](adr/0012-fraud-screening-failure-policy.md).

Permanent failures throw `AmqpRejectAndDontRequeueException` and skip the retry budget: a missing or invalid event id, a missing event type, invalid JSON, a non-object payload, a missing or invalid identifier, or an unknown event type. The listener transaction rolls back, so `processed_events` does not keep that id. Transient failures (the listener wraps them in `RuntimeException`) are retried by the advice chain on the listener container factory: `app.messaging.listener-max-attempts` (default 5), `listener-initial-interval-ms` (default 500), `listener-multiplier` (default 2.0), and `listener-max-interval-ms` (default 10000, a hard cap), with exponential backoff and jitter. `spring.rabbitmq.listener.simple.retry` does not configure this chain. Other listener container properties, including `auto-startup`, still come from Boot. `defaultRequeueRejected` is false. After the attempt budget, `RejectAndDontRequeueRecoverer` rejects without requeue and RabbitMQ routes the message to the DLQ. DLQ messages stay there until an operator inspects them.

That is at-least-once delivery plus an idempotent consumer for all three payment events. Delivery detail is in [messaging.md](messaging.md).

## Observability

Health, Prometheus metrics, and OpenTelemetry traces describe the running process. PostgreSQL remains the source of truth. Alert rules and the local collector stack are in [observability.md](observability.md).

| Signal | Where |
|---|---|
| Liveness / readiness | `/actuator/health/liveness`, `/actuator/health/readiness` (readiness includes the database) |
| Metrics | `/actuator/prometheus`, common tag `application=payments-platform` |
| Traces | OTLP HTTP. Default samples every trace and exports to `http://localhost:4318/v1/traces`. Profile `prod` samples 10% unless `TRACING_SAMPLING_PROBABILITY` is set, and exports nothing until a collector URL is set. |
| Correlation | `X-Correlation-Id` on the HTTP response, the outbox headers, and the AMQP header. MDC key `correlationId`. |
| Trace context | W3C `traceparent` on the outbox row. The relay restores it before publish. Listener and template observations continue that trace id. |
| Logs | Plain text locally, with `traceId`, `spanId`, and `correlationId` in the level pattern. Profile `prod` sets `logging.structured.format.console=ecs`. |

`OTEL_EXPORTER_OTLP_ENDPOINT` is the collector base URL. `OtlpEndpointNormalizer` appends `/v1/traces` when that path is missing. Spring Boot 3.5 loads that post-processor from `META-INF/spring.factories`. `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` is the full traces URL and wins when both are set. A blank endpoint disables export. Tests set `management.tracing.enabled=false`.

| Meter | Type | Meaning |
|---|---|---|
| `payments.outbox.pending` | gauge | Rows with `published_at` null, including rows currently leased |
| `payments.outbox.lag.seconds` | gauge | Age of the oldest unpublished `occurred_at` |
| `payments.outbox.publish.lag` | timer | Time from `occurred_at` until `published_at` is set. SLO buckets at 1s, 10s, 30s, and 60s |
| `payments.outbox.attempts` | gauge | Sum of `attempts` on unpublished rows |
| `payments.queue.messages` | gauge | Ready messages. Tags `role=main` on `payments.created.notifications` and `role=dlq` on `payments.created.notifications.dlq`, plus the queue name |
| `payments.cache.hits` / `payments.cache.misses` / `payments.cache.errors` | counters | Cache-aside outcomes |
| `payments.cache.latency` | timer | Cache read latency |
| `http.server.requests` | timer | API latency. SLO buckets at 100ms, 300ms, 500ms, and 1s |

A scrape that cannot reach PostgreSQL or RabbitMQ, or that finds a queue missing, records `NaN` for that gauge and logs a warning. The HTTP request is unaffected.

## Process modes

The serving process handles HTTP, runs the outbox relay, and consumes notifications.
`APP_MIGRATION_ONLY=true` is read by `MigrationOnlyMode` (command line, then the system property `app.migration-only`, then the environment variable). `PaymentsApplication` then forces Flyway on, a non-web application type, listener auto-startup off, outbox scheduling off, and `spring.modulith.moments.enabled=false` (Moments would otherwise turn scheduling back on). Those overrides sit ahead of environment variables. The process serves no HTTP, starts no Rabbit consumers, and starts no outbox scheduler. It exits `0` only when Flyway has applied the schema, nothing is pending, and those runtime checks pass. Startup failure, a missing Flyway bean, pending migrations, or zero applied migrations exit non-zero. The Kubernetes Job sets the variable. Replicas leave it unset and inherit `SPRING_FLYWAY_ENABLED=false`.

## Boundaries

`ModularityTest` calls `ApplicationModules.of(PaymentsApplication.class).verify()`.
`ArchitectureTest` rejects `@Autowired` fields and any `domain` dependency outside the JDK and `domain` packages, which keeps Spring, JPA, Redis, and RabbitMQ out of the domain.
Production types take dependencies by constructor. The field-injection rule does not scan tests.

## Data model

Selected columns. The Flyway scripts are the full schema.

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : "identity FK"
    USERS {
        uuid id PK
        string email UK
        string password_hash
        string roles
    }
    PAYMENTS {
        uuid id PK
        uuid user_id
        numeric amount
        string currency
        string status
        bigint version
    }
    NOTIFICATIONS {
        uuid id PK
        uuid user_id
        uuid payment_id
        string type
    }
    OUTBOX_EVENTS {
        uuid id PK
        uuid aggregate_id
        string event_type
        timestamptz published_at
        int attempts
        string last_error
        timestamptz next_attempt_at
    }
    PROCESSED_EVENTS {
        string consumer PK
        uuid event_id PK
    }
    IDEMPOTENCY_KEYS {
        uuid user_id PK
        string key PK
        string status
        uuid lease_id
    }
```

Flyway migrations are append-only: `V1` `outbox_events`, `processed_events`, and `idempotency_keys`; `V2` identity; `V3` payments; `V4` notifications; `V5` `idempotency_keys.lease_id`.
