# Observability

The process exposes health, Prometheus metrics, and OpenTelemetry traces. PostgreSQL remains the source of truth. Metrics describe the running process; they are not a second ledger.

## Signals

| Signal | Where | Notes |
|---|---|---|
| Liveness / readiness | `/actuator/health/liveness`, `/actuator/health/readiness` | Readiness includes the database. |
| Metrics | `/actuator/prometheus` | Micrometer. Common tag `application=payments-platform`. |
| Traces | OTLP HTTP | Local default samples every trace and exports to `http://localhost:4318/v1/traces`. Profile `prod` samples 10% and does not export until a collector URL is set. |
| Correlation | `X-Correlation-Id` request and response header, MDC key `correlationId` | Also stored on the outbox row and copied to the AMQP header `X-Correlation-Id`. |
| Trace context | W3C `traceparent` | The outbox row stores the request trace. The relay restores it before publish, and the listener continues that same trace id. |
| Logs | Plain text locally. Profile `prod` switches the console to ECS JSON (`logging.structured.format.console=ecs`). | ECS JSON includes MDC, so `correlationId`, `traceId`, and `spanId` are queryable once a shipper sends that stdout to Kibana. |

Tests set `management.tracing.enabled=false` so the suite does not need a collector.

## Custom metrics

| Meter | Type | Meaning |
|---|---|---|
| `payments.outbox.pending` | gauge | Rows in `outbox_events` with `published_at` null. |
| `payments.outbox.lag.seconds` | gauge | Age of the oldest unpublished row. |
| `payments.outbox.publish.lag` | timer | Time from `occurred_at` until the row is marked published. SLO buckets at 1s, 10s, 30s, and 60s. |
| `payments.outbox.attempts` | gauge | Sum of `attempts` on unpublished rows (relay retries). |
| `payments.queue.messages{role="main"}` | gauge | Ready messages on `payments.created.notifications`. |
| `payments.queue.messages{role="dlq"}` | gauge | Ready messages on `payments.created.notifications.dlq`. |
| `payments.cache.hits` / `payments.cache.misses` / `payments.cache.errors` | counters | Cache-aside outcomes. |
| `payments.cache.latency` | timer | Cache read latency. |
| `http.server.requests` | timer | API latency and status. SLO buckets at 100ms, 300ms, 500ms, and 1s. |
| `resilience4j.circuitbreaker.calls{name="fraudScreening"}` | timer | Fraud-screening circuit breaker calls (`kind` is `successful`, `failed`, or `ignored`). Each retry attempt is one call. |
| `payments.fraud.screening.unexpected_client_errors` | counter | Provider HTTP responses that were not an explicit decline and not a transient error. These 4xx responses are a circuit-breaker success, so a permanent 401 never opens the circuit. Suggested alert: `increase(payments_fraud_screening_unexpected_client_errors_total[5m]) > 0`. |
| `payments.fraud.screening.in.flight` | gauge | Fraud-screening HTTP calls currently running on the virtual-thread executor. |
| `resilience4j.bulkhead.available.concurrent.calls{name="fraudScreening"}` | gauge | Permits left in the fraud-screening semaphore. `max-concurrent-calls` is set per profile. |
| `resilience4j.circuitbreaker.not.permitted.calls{name="fraudScreening"}` | counter | Calls rejected because the fraud-screening circuit was open. |
| `resilience4j.retry.calls{name="fraudScreening"}` | counter | Fraud-screening retries. `failed_with_retry` is an exhausted transient call. `failed_without_retry` is an exception that is not retried, such as an open circuit or a full bulkhead. A provider 4xx is not an exception and does not increment this meter. |
| `resilience4j.timelimiter.calls{name="fraudScreening"}` | counter | Fraud-screening timeouts (`kind="timeout"`). |

Prometheus names replace `.` with `_` (`payments_outbox_pending`).

A scrape that cannot reach PostgreSQL or RabbitMQ, or that finds a queue missing, records `NaN` for that gauge and logs a warning. The HTTP request is unaffected.

## SLOs

These are targets for a single production service, not a paging contract until an operator loads the rules.

| SLO | Target | How to read it |
|---|---|---|
| Availability | 99.5% of HTTP requests over 30 days are not 5xx | `ApiErrorRateHigh` fires when the 5xx ratio stays above 1% for 10 minutes. |
| Read latency | p95 of `GET /api/v1/payments/{id}` under 300 ms | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{method="GET",uri="/api/v1/payments/{id}"}[5m])))`. `PaymentReadLatencyHigh` pages above 300 ms for 10 minutes. |
| Outbox publish lag | 95% of events published within 30 seconds of `occurred_at` | `payments_outbox_publish_lag_seconds` (timer recorded when the row is marked published). `OutboxPublishLagHigh` pages when that p95 stays above 30 seconds. |
| Oldest unpublished event | Under 60 seconds | `payments_outbox_lag_seconds` is the age of the single oldest unpublished row, aggregated with `max()` across replicas. `OutboxLagHigh` pages above 60 seconds for 5 minutes. |
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
- Grafana: `http://127.0.0.1:3000` (user `payments`, password `local-observability-only`, both overridable with `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD`). The port is bound to loopback.

Logs profile (Elasticsearch capped at 512 MB heap, plus Kibana):

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile logs up -d
```

Kibana: `http://127.0.0.1:5601`. Elasticsearch security is off because this port is loopback-only. The profile does not collect logs. Run the app with the `prod` profile to emit ECS JSON on stdout, and ship that stream with a collector you already operate. Do not enable the `prod` profile in tests.

`OTEL_EXPORTER_OTLP_ENDPOINT` is the collector base URL (`http://otel-collector:4318`). `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` is the full traces URL (`http://otel-collector:4318/v1/traces`) and wins when both are set. Profile `prod` exports nothing until one of them is set, and it samples 10% unless `TRACING_SAMPLING_PROBABILITY` is set. Spring Boot 3.5 loads `OtlpEndpointNormalizer` from `META-INF/spring.factories` and appends `/v1/traces` when the value is only a base URL. This repository does not provision a hosted collector.

Grafana provisions the Prometheus datasource and the **Payments platform** dashboard (HTTP latency and status, outbox pending and lag, queue depth and DLQ, cache hit and miss). The dashboard folder is `Payments`.

## Scrape target

`deploy/observability/prometheus.yml` scrapes `app:8080`. That name exists only when the app container is on the Compose network. The container publishes `127.0.0.1:8080`, so Prometheus cannot reach it through `host.docker.internal`.

The README local flow runs the JVM on the host (`mvn spring-boot:run`) and leaves the `app` service stopped. Use the host file for that flow. It scrapes `host.docker.internal:8080`. The Prometheus service maps that name with `extra_hosts: host.docker.internal:host-gateway` (Docker Desktop, Colima, and Linux).

```bash
PROMETHEUS_CONFIG=./deploy/observability/prometheus-host.yml \
  docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d \
  postgres redis rabbitmq otel-collector jaeger prometheus grafana
```

Leave `PROMETHEUS_CONFIG` unset for the all-in-compose stack. The default file still scrapes `app:8080`. Run one app, not both: the two files are alternatives, so the same process is not scraped twice.

Commands in this page use the Compose V2 plugin, `docker compose`. The V1 binary is `docker-compose`. Both read `docker-compose.yml`. If the plugin is missing, pass the same arguments to `docker-compose`.
