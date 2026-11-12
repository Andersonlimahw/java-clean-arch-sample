# Deploy

The artifact is the Docker image built from the repository `Dockerfile`. `ARG JAVA_MAJOR=25` selects `maven:3.9-eclipse-temurin-25-alpine` for the build and `eclipse-temurin:25-jre-alpine` for the runtime. Railway, Render, Cloud Run, and Kubernetes all run that image. One process serves HTTP, relays the outbox, and consumes notification messages. PostgreSQL is the source of truth. Redis and RabbitMQ are required beside it.

Do not deploy this service to Vercel Functions. The relay and the RabbitMQ listener are loops, not request handlers. That choice is [ADR 0011](adr/0011-railway-vs-other-hosting.md). Local Compose versus the Kubernetes shape is [ADR 0010](adr/0010-compose-vs-kubernetes.md). The runtime those decisions assume is [the architecture page](architecture.md).

Nothing in this repository creates a Railway, Render, or Google Cloud project. Running those commands spends money and needs a separate decision.

## Environment

Copy `.env.example` for local runs. Never commit filled-in secrets. `secrets.env` is gitignored; `deploy/k8s/secrets.env.example` is the Kubernetes key list.

| Variable | Required | Notes |
|---|---|---|
| `PORT` | no | Defaults to 8080. Railway, Render, and Cloud Run inject `PORT`. |
| `SPRING_PROFILES_ACTIVE` | yes in prod | `local`, `test`, and `default` may use an ephemeral JWT key. Any other profile, including `prod`, refuses to start when a PEM is empty. Compose and `.env.example` use `local`. The image default in the `Dockerfile` is `prod`. This profile does not change the log format. |
| `DB_URL` | yes | `jdbc:postgresql://HOST:5432/payments` |
| `DB_USER` / `DB_PASSWORD` | yes | Database credentials. |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | yes | Cache and auth rate limit. The app still serves reads if Redis is down. |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | yes | Outbox relay and notification consumer. |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | yes outside local/test | PEM. PKCS#8 private key and X.509 public key. Generate with `scripts/generate-jwt-keys.sh`. Paste single-line values with `\n` escapes. |
| `JWT_ISSUER` / `JWT_AUDIENCE` | yes | Must match token checks. Defaults are in `application.yml`. |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | no | Full OTLP HTTP traces URL, for example `http://collector:4318/v1/traces`. Spring Boot creates the exporter only when this is set. |
| `APP_MIGRATION_ONLY` | k8s Job | `true` activates the `migration` profile (`application-migration.yml`). The process applies Flyway, does not serve HTTP, and does not start Rabbit listeners or the outbox scheduler. Exit `0` only when Flyway has applied the schema and nothing is pending. Startup or migration failure exits non-zero. `--app.migration-only=false` on the command line overrides a `true` environment variable. |
| `SPRING_FLYWAY_ENABLED` | k8s only | Leave unset on Railway, Render, and Cloud Run so startup migrations stay on (`application.yml` defaults `enabled: true`). The Kubernetes API ConfigMap sets `false`. The migration Job sets `true`. |
| `FRAUD_SCREENING_BASE_URL` | yes in prod | Base URL of `POST /v1/screenings`. Profile `prod` has no default and refuses to start unless the scheme is `https`. The base ConfigMap does not set it. Put the real https URL on `payments-secret`. Local Compose and `.env.example` use the WireMock on `http://localhost:8090` (inside Compose, `http://fraud-screening:8080`). That stub answers `CLEAR` so `scripts/validate-api.sh` can confirm a payment. |

`prod` without JWT PEMs refuses to start. That is intentional.

A database that already applied the old `V6__payment_screening_reason` from this branch, before that script became `V7__payment_screening_reason`, will not migrate cleanly. Drop and recreate that database. `V6__payments_list_index` is the list index and must stay V6.

Plugin URLs are not bound. `DATABASE_URL`, `REDIS_URL`, and `CLOUDAMQP_URL` do nothing until you copy the host, port, user, and password into the variables above. `DB_URL` must be a JDBC URL.

## Local

From the repository root. Passwords and hosts come from `.env.example`, which matches the Compose defaults.

```bash
set -a
source .env.example
set +a
docker compose up -d postgres redis rabbitmq fraud-screening
mvn spring-boot:run
```

`mvn spring-boot:run` stays in the foreground. Smoke from another shell. The script polls `/actuator/health/readiness` for `READY_TIMEOUT_SECONDS` (default 90) before the API calls.

```bash
./scripts/validate-api.sh
```

Full container stack, including the app image. `--wait` blocks until each service healthcheck passes, including readiness on the app container:

```bash
set -a
source .env.example
set +a
docker compose config
docker compose up -d --build --wait
./scripts/validate-api.sh
```

## Railway (preferred)

`railway.json` builds the Dockerfile and checks `/actuator/health/readiness`.

1. Create a project only when you intend to pay for it.
2. Add PostgreSQL, Redis, and RabbitMQ (Railway plugins or reachable managed services).
3. Set the variables in the table. Build `DB_URL` as `jdbc:postgresql://HOST:PORT/DATABASE` from the Postgres plugin host, port, and database name. Set `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` from the Redis plugin. Set `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, and `RABBITMQ_VHOST` from the broker. Do not set `SPRING_FLYWAY_ENABLED=false` here; there is no separate migration Job, and Flyway runs in this process.
4. Leave `SPRING_PROFILES_ACTIVE=prod` (the image default) and set both JWT PEMs.
5. Deploy the Docker service. Confirm readiness before sending traffic.
6. Run `BASE_URL=https://<your-host> ./scripts/validate-api.sh`.

Cost note: an always-on web service plus three data services is the bill. There is no free production path that keeps RabbitMQ connected.

## Render

`render.yaml` is a Docker web service on the standard plan. Health check path: `/actuator/health/readiness`.

Secrets are `sync: false` and are entered in the dashboard: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`. `JWT_ISSUER`, `JWT_AUDIENCE`, and `RABBITMQ_VHOST` are committed non-secret defaults in `render.yaml`. Leave `SPRING_FLYWAY_ENABLED` unset so this process migrates on startup.

`render.yaml` does not create PostgreSQL, Redis, or RabbitMQ. Point `DB_URL`, the Redis variables, and `RABBITMQ_HOST` at services outside that blueprint. Render does not provide RabbitMQ; use CloudAMQP or another broker.

## Google Cloud Run

`deploy/cloudrun/service.yaml` is a Knative Service with `minScale: 1`, CPU throttling off, and a VPC connector annotation so the relay keeps polling and can reach private data stores. Scale-to-zero would stall outbox publishing. `SPRING_PROFILES_ACTIVE=prod`. `JWT_ISSUER` and `JWT_AUDIENCE` are the same defaults as `application.yml`. Each other variable is a Secret Manager secret, version `latest`: `payments-db-url`, `payments-db-user`, `payments-db-password`, `payments-redis-host`, `payments-redis-port`, `payments-redis-password`, `payments-rabbitmq-host`, `payments-rabbitmq-port`, `payments-rabbitmq-user`, `payments-rabbitmq-password`, `payments-rabbitmq-vhost`, `payments-jwt-private-key`, `payments-jwt-public-key`. Cloud Run `secretKeyRef.key` is the version, not a Kubernetes secret key. Flyway stays at the application default. The runtime service account needs `roles/secretmanager.secretAccessor` on those secrets.

Replace `gcr.io/PROJECT_ID/payments-platform:latest` and `PROJECT_CONNECTOR`, create the secrets, then replace the service only when you intend to pay:

```bash
gcloud run services replace deploy/cloudrun/service.yaml --region REGION
```

Cloud Run is the alternative, not the default, because a continuous consumer wants a process that is actually running.

## Forwarded client IP on Railway, Render, and Cloud Run

`server.forward-headers-strategy=native` installs Tomcat's `RemoteIpValve`. Auth rate limiting uses `request.getRemoteAddr()` after that valve. It does not read `X-Forwarded-For` itself.

The valve changes the remote address only when the immediate peer matches `server.tomcat.remoteip.internal-proxies`. In Spring Boot 3.5.16 the default is a regex for private and loopback ranges: `10/8`, `172.16/12`, `192.168/16`, `127/8`, link-local `169.254/16`, carrier-grade NAT `100.64/10`, and the IPv6 loopback and unique-local prefixes. A peer outside that regex is not trusted. The valve then leaves the remote address as the proxy or load balancer, and every client shares that proxy's rate-limit bucket (`ratelimit:auth:<proxy-ip>` in Redis). Register, login, and refresh then return 429 for everyone once that one bucket exceeds 20 requests per minute.

Railway, Render, and Cloud Run each put a proxy in front of the container and document `X-Forwarded-For` (and `X-Forwarded-Proto`) on the incoming request. This repo does not pin those proxies' source addresses. Their published docs do not give a stable CIDR for the hop that opens the TCP connection to the container, and that hop is what the valve compares with `internal-proxies`. Before setting the regex, confirm the current platform documentation and the peer address you actually observe (access log or a one-off log of `request.getRemoteAddr()` with the header still untrusted). Do not paste a guessed cloud range.

If that peer is already inside the default private ranges, leave `internal-proxies` unset. If it is not, set the property to a regex of only those peer addresses. The value is a Java regular expression, so dots are escaped. `203.0.113.10` and `203.0.113.11` below are RFC 5737 documentation addresses, not a Railway, Render, or Cloud Run range:

```yaml
server:
  tomcat:
    remoteip:
      internal-proxies: "203\\.0\\.113\\.10|203\\.0\\.113\\.11"
      remote-ip-header: X-Forwarded-For
      protocol-header: X-Forwarded-Proto
```

The same keys as environment variables, which is how these three platforms inject configuration:

```text
SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=203\.0\.113\.10|203\.0\.113\.11
SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=X-Forwarded-For
SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER=X-Forwarded-Proto
```

`remote-ip-header` names the header that carries the client address. When `server.forward-headers-strategy=native` and this property is left empty, Boot does not override the valve, and Tomcat's own default is `X-Forwarded-For`. Set it explicitly when a platform uses a different header. `protocol-header` names the scheme (`http` or `https`). With the native strategy and the property empty, Boot sets it to `X-Forwarded-Proto`. It does not affect the rate-limit key. `server.tomcat.remoteip.trusted-proxies` is a separate regex for proxies listed inside `remote-ip-header` that should be stripped but are not internal peers. Use it only when the platform documents that extra hop; it is not a substitute for `internal-proxies`.

Do not set `internal-proxies` to `.*` on a service that accepts connections from the open internet. That trusts every peer and lets a client choose the rate-limit key by sending `X-Forwarded-For`.

## Kubernetes

Operator notes, the image tag, and the apply command: [deploy/k8s/README.md](../deploy/k8s/README.md).

Validate the same way `scripts/ci-local.sh` does:

```bash
kustomize build deploy/k8s/overlays/staging | kubeconform -strict -kubernetes-version 1.30.0 -summary
kustomize build deploy/k8s/overlays/production | kubeconform -strict -kubernetes-version 1.30.0 -summary
```

Apply with `scripts/k8s-apply.sh --secret-env-file secrets.env` after the image is loaded. The script creates the namespace and ConfigMap, applies the Secret, waits until the migration Job completes, then applies the Deployment. See the Kubernetes README for the exact command. The API ConfigMap sets `SPRING_FLYWAY_ENABLED=false`. The Job `payments-flyway-migration` in `deploy/k8s/base/flyway-job.yaml` sets `SPRING_FLYWAY_ENABLED=true` and `APP_MIGRATION_ONLY=true`. That flag forces Flyway on above the ConfigMap, skips HTTP, Rabbit listeners, and the outbox scheduler, and exits `0` only when the schema is applied and nothing is pending. `scripts/k8s-apply.sh` waits with `kubectl wait --for=condition=complete job/payments-flyway-migration --timeout=180s`. A non-zero exit fails the Job (`backoffLimit: 3`).

## Rollback

- Railway / Render: redeploy the previous image tag or the previous successful deploy in the dashboard. Flyway migrations are append-only; a rollback does not drop columns. If a migration already ran, the previous image must still tolerate that schema.
- Kubernetes: `kubectl rollout undo deployment/payments-deployment -n <namespace>`. Staging is `payments-staging`; production is `payments-production`. That rolls back HTTP, the outbox relay, and the notification consumer together. Do not delete Flyway versions that already applied.
- A poison message is handled on that same Deployment. Inspect the DLQ before scaling it. Republishing an outbox row is safe when `processed_events` already stores that `(consumer, event_id)`; the consumer inserts with `ON CONFLICT DO NOTHING`.

## Troubleshooting

| Symptom | Check |
|---|---|
| Readiness never goes green | Database URL, security group, and Flyway. `/actuator/health/readiness` includes the DB. Liveness does not, so a database blip does not restart the pod. |
| `Production environment must define JWT_PRIVATE_KEY` | Profile is not `local`/`test`/`default` and a PEM is empty. |
| `Failed to parse configured RSA JWT keys` | PEMs are non-empty but not PKCS#8 and X.509. `CHANGE_ME` and truncated placeholders hit this. Generate keys with `scripts/generate-jwt-keys.sh`. |
| Payments succeed, notifications stay empty | RabbitMQ credentials, exchange `payments.events`, unpublished rows in `outbox_events`. The relay polls about once a second. |
| 401 on every call | `JWT_ISSUER` / `JWT_AUDIENCE` differ from the token, or the public key does not match the private key. |
| 409 on `POST /api/v1/payments` | The idempotency key is in progress, or it was reused with a different body. |
| 409 on confirm | The payment is not `PENDING` (`urn:problem-type:409`), or two updates overlapped and lost the version check (`urn:problem-type:concurrent-modification`). Confirm does not read `Idempotency-Key`. |
| 503 on confirm or cancel | Deadlock or serialization failure. Honor `Retry-After` and send the same request again. |
| 429 on login for every client at once | Auth rate limit is one shared bucket. The platform proxy is probably outside `server.tomcat.remoteip.internal-proxies`, so `X-Forwarded-For` is ignored. See [Forwarded client IP](#forwarded-client-ip-on-railway-render-and-cloud-run). |
| `mkdir .../docker.sock: operation not supported` while tests start Ryuk (macOS Colima) | Export `TESTCONTAINERS_RYUK_DISABLED=true` for that `mvn verify`. Testcontainers 1.21.4 reads the environment variable only; a system property of that name does nothing. Maven does not set it. `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` is the path inside the Colima VM for a run that still starts Ryuk. This suite does not read it once Ryuk is off. |
| `docker compose` is not found | Commands in this runbook use the V2 plugin (`docker compose`). The V1 binary is `docker-compose`. Same arguments and the same `docker-compose.yml`. |

## Smoke test

`scripts/validate-api.sh` registers a unique user, logs in, rejects a missing token and a missing idempotency key, creates and confirms a payment, fetches that payment, rejects an unknown id, and polls notifications until the payment id is present or `NOTIFY_TIMEOUT_SECONDS` (default 10) elapses. It prints each status code. Set `BASE_URL` when the process is not on `http://127.0.0.1:8080`.

The Postman collection at `src/test/postman/payments-v1.postman_collection.json` asserts the same status codes and generates a new email before register. It does not poll until the notification row exists; the shell script does.
