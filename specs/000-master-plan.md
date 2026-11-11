# Master Plan — Payments Platform (refactor of java-clean-arch-sample)

Repo: `Andersonlimahw/java-clean-arch-sample` (private). Primary checkout:
`/Volumes/lemon-ssd/projects-ssd/backend/java-clean-arch-sample`. Default branch: `main`.
Baseline before refactor (measured 2026-11-11): `mvn test` → 45 tests, 0 failures, BUILD SUCCESS (trip-planner domain).

## Owner decisions (binding)
1. The trip domain (trips, participants, activities, links) is **removed**. The product becomes a **Payments platform** with modules `identity`, `payments`, `notifications` + `shared` kernel. New contract under `/api/v1`. Old endpoints are not preserved (documented as breaking change in ADR).
2. Docker is available → Testcontainers, compose and image builds must actually run.
3. Full autonomy on GitHub: push branches, open PRs, review, fix, squash-merge into `main`.
4. `main` is the base and default branch.

## Tech baseline (fixed — do not change without coordinator approval)
- Java 21 (`<java.version>21</java.version>`; local JDK is 24, compile with release 21), Maven, **Spring Boot 3.5.16**, Spring Modulith 1.4.x (BOM), springdoc-openapi 2.8.x, Testcontainers (Boot-managed; must work with Docker Engine 29 → requires Testcontainers ≥ 1.21.4 — override `testcontainers.version` if Boot's is older), ArchUnit 1.4.x.
- No Lombok in new code (records + explicit constructors).
- PostgreSQL 16, Redis 7, RabbitMQ 3.13 (management image).
- Flyway; migrations are **append-only**. Version ownership (avoid collisions across parallel PRs):
  `V1__shared_outbox_inbox.sql` (foundation), `V2__identity.sql`, `V3__payments.sql`, `V4__notifications.sql`, later fixes use `V5+` claimed in the PR.
- **No cross-module foreign keys** (modules own their tables; references are UUID columns + index). Intra-module FKs required.
- UUID PKs generated in the application (`UUID.randomUUID()`), `timestamptz` columns, `version bigint` for optimistic locking on aggregates.

## Package layout (base package `com.muse.payments`, app class `PaymentsApplication`)
```
com.muse.payments
├── shared/                         # OPEN Modulith module (@ApplicationModule(type = OPEN))
│   ├── domain/                     # AggregateRoot, DomainEvent, Money?, DomainException hierarchy — pure Java
│   ├── application/                # Command/Query + handler interfaces, bus-less dispatch, OutboxPort, Clock port
│   ├── infrastructure/             # outbox JPA adapter, inbox (processed_events), Redis/Rabbit shared config
│   ├── security/                   # SecurityFilterChain, JWT encoder/decoder config, CurrentUser
│   └── web/                        # ProblemDetail exception handler, CorrelationIdFilter
├── identity/   {domain, application, infrastructure, web}
├── payments/   {domain, application, infrastructure, web}
└── notifications/ {domain, application, infrastructure, web}
```
Rules (enforced by ArchUnit + `ApplicationModules.of(PaymentsApplication.class).verify()`):
- `..domain..` depends only on JDK + `shared.domain` (no Spring, Jakarta Persistence, Hibernate, Redis, Rabbit, Jackson).
- `..application..` may use Spring stereotypes/`@Transactional` (pragmatic) but not infrastructure/web or JPA entities.
- `..web..` → application only. `..infrastructure..` implements ports from domain/application.
- Modules talk only via each module's public API package (`<module>.api` / named interfaces) or via integration events. `notifications` learns about payments **only through RabbitMQ events**.
- Constructor injection only; no field injection.
- CQRS: write side = `XxxCommand` + `XxxCommandHandler` (returns id/result, never entities); read side = `XxxQuery` + `XxxQueryHandler` returning read models (records) and may read via dedicated query repositories/projections. No Event Sourcing.

## API contract v1 (all JSON; errors = RFC 7807 `application/problem+json` with `type,title,status,detail,instance,correlationId`, never stack traces)
Identity (public): `POST /api/v1/auth/register` {email,password,displayName} → 201 · `POST /api/v1/auth/login` → 200 {accessToken,refreshToken,tokenType:"Bearer",expiresIn} · `POST /api/v1/auth/refresh` {refreshToken} → 200 rotated pair (reuse of a rotated token revokes the whole family → 401) · `POST /api/v1/auth/logout` → 204. Auth endpoints are rate-limited → 429 + `Retry-After`.
Payments (Bearer, role USER/ADMIN, ownership enforced; ADMIN may read any):
`POST /api/v1/payments` header `Idempotency-Key` (required) body {amount (decimal string/number >0, scale ≤2), currency (ISO-4217), description} → 201 + Location; replay with same key+body → 200/201 same resource; same key different body → 409.
`GET /api/v1/payments/{id}` (Redis cache-aside) · `GET /api/v1/payments?status=&page=&size=` (owner-scoped) · `POST /api/v1/payments/{id}/confirm` · `POST /api/v1/payments/{id}/cancel` (state machine PENDING→COMPLETED|CANCELLED; invalid transition → 409; stale version / concurrent update → 409 via optimistic locking; `If-Match` optional).
Notifications (Bearer, owner-scoped): `GET /api/v1/notifications?page=&size=`.
Status semantics: 400 validation, 401 missing/invalid token, 403 role/ownership (for other users' payments return **404** to avoid enumeration; document this), 404, 409, 429, 500 generic.
OpenAPI at `/v3/api-docs` + Swagger UI (bearer scheme), disabled-able via property in prod.

## Domain events / messaging
`PaymentCreated`, `PaymentCompleted`, `PaymentCancelled` (domain events raised by aggregate) → translated to integration events and appended to `outbox_events` **in the same transaction** → relay (`SELECT … FOR UPDATE SKIP LOCKED`, batch, publisher confirms, mark published, backoff on failure) → exchange `payments.events` (topic) routing keys `payment.created|completed|cancelled` → queue `notifications.payment-events` → consumer (idempotent via `processed_events(consumer, event_id)` PK in same tx as notification insert) → `notifications` table (simulated EMAIL channel). Retries: listener retry with exponential random backoff (jitter), then reject → DLX `payments.events.dlx` → `notifications.payment-events.dlq`. Envelope: {eventId, eventType, occurredAt, aggregateId, version, payload}; headers carry `x-correlation-id` and W3C `traceparent`. Guarantee: **at-least-once delivery + idempotent consumer = effectively-once side effects**; never claim exactly-once.

## Cache
Redis cache-aside for `GetPaymentById` (key `payments:v1:payment:{id}`, TTL 10 min ± jitter), invalidation **after commit** on any state change, Redis failure → log + metric + fall back to DB (never fail the request), single-flight per key within the instance against stampede, metrics `cache.gets{result=hit|miss|error}` + latency timer. PostgreSQL is the source of truth.

## Security
Spring Security resource server, JWT **RS256** (keys from env `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` PEM; `local`/`test` profiles may generate an ephemeral key pair — never in prod), claims `sub`(userId) `roles` `iss` `aud` `exp` `iat` `jti`, access TTL 15 min, refresh opaque 256-bit random stored **hashed** (SHA-256) with family id + rotation + reuse detection, BCrypt (strength 12) passwords. No secrets in Git (`.env.example` only).

## Ops
Dockerfile multi-stage (non-root, layered jar, JRE 21), `docker-compose.yml` (app, postgres, redis, rabbitmq, healthchecks, volumes, `.env`), `docker-compose.observability.yml` (OTel Collector, Jaeger, Prometheus, Grafana, Elasticsearch, Kibana, Filebeat or collector→ES for logs). Kubernetes manifests under `deploy/k8s` (kustomize base + overlays): Deployment, Service, ConfigMap, Secret refs, probes (startup/readiness/liveness via actuator health groups), requests/limits, HPA, Ingress + TLS (cert-manager annotation), Flyway migration Job strategy. Validate with `kubeconform` via Docker image. CI: `.github/workflows/ci.yml` (mvn verify incl. Testcontainers, docker build). Remote Actions may be unpaid → **local CI is the required evidence**. Hosting docs: Railway primary, Render + Cloud Run alternatives, no Vercel for this backend. **No production deploy / no cost-generating action.**

## Observability
Actuator (health groups liveness/readiness, prometheus), Micrometer, OTel tracing (micrometer-tracing-bridge-otel + OTLP exporter), Boot structured logging **ECS JSON** (Kibana-friendly) in non-local profiles, `X-Correlation-Id` filter (generate if absent, echo in response, MDC, propagate to outbox headers and AMQP), RabbitMQ observation enabled, custom metrics: outbox pending/lag/publish failures, consumer retries/DLQ count, cache hit/miss. SLOs + alert rules documented (Prometheus rules file).

## PR waves (one worktree + one agent per PR; worktrees in `<repo>/.worktrees/<name>`)
| Wave | PR | Branch | Scope |
|---|---|---|---|
| 1 | PR1 | `feat/pr1-foundation` | spec-kit init + constitution + AGENTS.md/CLAUDE.md, `.worktrees/` in .gitignore, remove trip domain, new skeleton + pom (all deps), profiles/config, shared kernel, ProblemDetail, correlation filter, security baseline (resource server, JWT encoder/decoder, CurrentUser, test token helper), V1 outbox/inbox tables + OutboxPort + JPA appender, Testcontainers base, ArchUnit + Modulith tests, PostgreSQL |
| 2 | PR2 | `feat/pr2-identity` | users, register/login/refresh rotation/logout, BCrypt, roles, rate limit 429 (Redis-backed with safe fallback) |
| 2 | PR3 | `feat/pr3-payments` | payment aggregate, Money/Currency VOs, state machine, CQRS handlers, JPA adapter, optimistic locking→409, idempotency key, outbox append of events, endpoints + ownership |
| 2 | PR4 | `feat/pr4-platform-infra` | Dockerfile, compose, .env.example, k8s/kustomize, HPA, ingress, migration job, CI workflow, railway/render config, kubeconform |
| 3 | PR5 | `feat/pr5-messaging` | outbox relay, Rabbit topology, confirms, retry/jitter, DLQ, notifications module + idempotent consumer + endpoint, duplicate/recovery tests |
| 3 | PR6 | `feat/pr6-cache` | Redis cache-aside for payment reads + invalidation after commit + stampede + metrics + fallback |
| 4 | PR7 | `feat/pr7-observability` | OTel, ECS logs, correlation over HTTP+AMQP, custom metrics, observability compose (ELK/Kibana, Jaeger, Prometheus, Grafana), SLO/alerts |
| 4 | PR8 | `feat/pr8-docs-e2e` | README, C4/Mermaid, ADRs (all trade-offs), deploy docs (Railway/Render/Cloud Run), Postman + `scripts/validate-api.sh`, full compose E2E smoke |

## PR agent protocol (every PR agent follows this)
1. Work **only** inside your assigned worktree path; never touch the primary checkout or other worktrees. Prefix shell commands with `rtk` (e.g. `rtk mvn …`, `rtk git …`; use `rtk proxy <cmd>` for raw output). Reuse `~/.m2` (never wipe it).
2. Spec-driven: create/update `specs/<NNN-slug>/spec.md`, `plan.md`, `tasks.md` (spec-kit format, concise) before coding.
3. Implement incrementally with tests (JUnit 5, MockMvc, Testcontainers `*IT` via failsafe). Never weaken or delete tests to get green.
4. Local CI gate (required evidence): `rtk mvn -B clean verify` green (Docker is running). Plus any PR-specific checks.
5. Semantic commits (Conventional Commits), push branch, `gh pr create --base main` with body: summary, test evidence (real numbers), risks.
6. Run **code-review-expert**: launch in parallel subagents `thermo-nuclear-review-subagent` and `thermo-nuclear-code-quality-review-subagent` with the PR diff (`git diff origin/main...HEAD`) and needed file context; synthesize findings.
7. Fix **all** valid findings (justify any rejected one in the PR as a comment), re-run the local gate, push, post a PR comment "Review findings resolved" listing each finding → fix/justification.
8. Do **not** merge. Report to the coordinator: PR URL, final local test counts, findings table, open risks. If `main` moved, rebase on `origin/main` and re-run the gate before reporting.
9. Never commit secrets; never deploy; never force-push `main`.
