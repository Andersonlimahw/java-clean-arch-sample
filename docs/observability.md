# Observability

The process exposes health, Prometheus metrics, and OpenTelemetry traces. PostgreSQL remains the source of truth. Metrics describe the running process; they are not a second ledger.

## Signals

| Signal | Where | Notes |
|---|---|---|
| Liveness / readiness | `/actuator/health/liveness`, `/actuator/health/readiness` | Readiness includes the database. |
| Metrics | `/actuator/prometheus` | Micrometer. Common tag `application=payments-platform`. |
| Traces | OTLP HTTP `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}` | `micrometer-tracing-bridge-otel`. Sampling `${TRACING_SAMPLING_PROBABILITY:1.0}`. |
| Correlation | `X-Correlation-Id` request and response header, MDC key `correlationId` | Also stored on the outbox row and copied to the AMQP header `X-Correlation-Id`. |
| Trace context | W3C `traceparent` | Rabbit template and listener observations are enabled, so a span continues across publish and consume when tracing is on. |
| Logs | Plain text locally. Profile `prod` switches the console to ECS JSON (`logging.structured.format.console=ecs`). | ECS JSON includes MDC, so `correlationId`, `traceId`, and `spanId` are queryable in Kibana. |

Tests set `management.tracing.enabled=false` so the suite does not need a collector.

## Custom metrics

| Meter | Type | Meaning |
|---|---|---|
| `payments.outbox.pending` | gauge | Rows in `outbox_events` with `published_at` null. |
| `payments.outbox.lag.seconds` | gauge | Age of the oldest unpublished row. |
| `payments.outbox.attempts` | gauge | Sum of `attempts` on unpublished rows (relay retries). |
| `payments.queue.messages{role="main"}` | gauge | Ready messages on `payments.created.notifications`. |
| `payments.queue.messages{role="dlq"}` | gauge | Ready messages on `payments.created.notifications.dlq`. |
| `payments.cache.hits` / `payments.cache.misses` / `payments.cache.errors` | counters | Cache-aside outcomes. |
| `payments.cache.latency` | timer | Cache read latency. |
| `http.server.requests` | timer | API latency and status. |

Prometheus names replace `.` with `_` (`payments_outbox_pending`).

A scrape that cannot reach PostgreSQL or RabbitMQ records `NaN` for that gauge and logs a warning. The HTTP request is unaffected.

## SLOs

These are targets for a single production service, not a paging contract until an operator loads the rules.

| SLO | Target | How to read it |
|---|---|---|
| Availability | 99.5% of HTTP requests over 30 days are not 5xx | `ApiErrorRateHigh` fires when the 5xx ratio stays above 1% for 10 minutes. |
| Read latency | p95 of `GET /api/v1/payments/{id}` under 300 ms | `http_server_requests_seconds` bucket for that URI. |
| Outbox publish lag | 95% of events published within 30 seconds of `occurred_at` | `payments_outbox_lag_seconds`. Page if it stays above 60 seconds for 5 minutes. |
| DLQ | Zero messages during a healthy hour | `NotificationDlqNotEmpty` is critical. Replay is manual. |
| Cache | Misses fall through to PostgreSQL; errors must not fail the GET | `payments_cache_errors` should stay near zero. |

Alert rules: `deploy/observability/prometheus-alerts.yml`.

## Local stack

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml config
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

- Jaeger UI: `http://127.0.0.1:16686`
- Prometheus: `http://127.0.0.1:9090`
- Grafana: `http://127.0.0.1:3000` (user `admin`, password `${GRAFANA_ADMIN_PASSWORD:-admin}` — local only)

Logs profile (Elasticsearch + Kibana, extra memory):

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile logs up -d
```

Kibana: `http://127.0.0.1:5601`. Point it at ECS JSON from the `prod` profile. Do not enable that profile in tests.

Set `OTEL_EXPORTER_OTLP_ENDPOINT` in Kubernetes or Railway to a collector you already run. This repository does not provision a hosted collector.
