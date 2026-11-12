# Deploy

The artifact is the Docker image built from the repository `Dockerfile`. One process serves HTTP, relays the outbox, and consumes notification messages. PostgreSQL is the source of truth. Redis and RabbitMQ are required beside it.

Do not deploy this service to Vercel Functions. The relay and the RabbitMQ listener are loops, not request handlers. The hosting trade-off is ADR 0011 in the architecture docs.

Nothing in this repository creates a Railway, Render, or Google Cloud project. Running those commands spends money and needs a separate decision.

## Environment

Copy `.env.example`. Never commit filled-in secrets.

| Variable | Required | Notes |
|---|---|---|
| `PORT` | no | Defaults to 8080. Railway and Render inject `PORT`. |
| `SPRING_PROFILES_ACTIVE` | yes in prod | `prod` turns on ECS JSON logs once that profile file is present. Local compose uses `local`. |
| `DB_URL` | yes | `jdbc:postgresql://HOST:5432/payments` |
| `DB_USER` / `DB_PASSWORD` | yes | Database credentials. |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | yes | Cache and auth rate limit. The app still serves reads if Redis is down. |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | yes | Outbox relay and notification consumer. |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | yes outside local/test | PEM. PKCS8 private key and X.509 public key. Generate with `scripts/generate-jwt-keys.sh`. |
| `JWT_ISSUER` / `JWT_AUDIENCE` | yes | Must match token checks. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | no | Set only when a collector exists. |
| `SPRING_FLYWAY_ENABLED` | prod | `false` on the API Deployment. `true` on the migration Job. |

`prod` without JWT PEMs refuses to start. That is intentional.

## Local

```bash
docker compose up -d postgres redis rabbitmq
export DB_PASSWORD=postgres REDIS_PASSWORD=redis_secret
mvn spring-boot:run
```

Smoke, from another shell, after readiness is up:

```bash
./scripts/validate-api.sh
```

Full container stack, including the app image:

```bash
docker compose config
docker compose up -d --build
./scripts/validate-api.sh
```

## Railway (preferred)

`railway.json` builds the Dockerfile and checks `/actuator/health/readiness`.

1. Create a project only when you intend to pay for it.
2. Add PostgreSQL, Redis, and RabbitMQ (Railway plugins or reachable managed services).
3. Set the variables above. Paste PEM keys as single-line values with `\n` escapes, which the parser accepts.
4. Deploy the Docker service. Confirm readiness before sending traffic.
5. Run `BASE_URL=https://<your-host> ./scripts/validate-api.sh`.

Cost note: a always-on web service plus three data services is the bill. There is no free production path that keeps RabbitMQ connected.

## Render

`render.yaml` describes a Docker web service on the standard plan and marks data and JWT variables as `sync: false` so they are entered in the dashboard, not in git.

Render does not provide RabbitMQ. Point `RABBITMQ_HOST` at CloudAMQP or another broker. Use Render Postgres or another managed Postgres. Redis is a Render key-value instance or another provider.

Health check path: `/actuator/health/readiness`.

## Google Cloud Run

`deploy/cloudrun/service.yaml` is a Knative Service with `minScale: 1` and CPU throttling off so the relay keeps polling. Scale-to-zero would stall outbox publishing.

Replace `gcr.io/PROJECT_ID/payments-platform:latest`, set env from Secret Manager, and put the service on a VPC connector that can reach PostgreSQL, Redis, and RabbitMQ. Cloud Run is the alternative, not the default, because a continuous consumer wants a process that is actually running.

## Kubernetes

Operator notes: [deploy/k8s/README.md](../deploy/k8s/README.md).

```bash
kustomize build deploy/k8s/overlays/staging | kubeconform -strict -kubernetes-version 1.30.0 -summary
kustomize build deploy/k8s/overlays/production | kubeconform -strict -kubernetes-version 1.30.0 -summary
```

Apply order: namespace and ConfigMap, Secret (from `secret.example.yaml`, not from git), migration Job, then the Deployment. The API ConfigMap sets `SPRING_FLYWAY_ENABLED=false`. The Job sets it `true`.

## Rollback

- Railway / Render: redeploy the previous image tag or the previous successful deploy in the dashboard. Flyway migrations are append-only; a rollback does not drop columns. If a migration already ran, the previous image must still tolerate that schema.
- Kubernetes: `kubectl rollout undo deployment/payments-deployment -n <namespace>`. Do not delete the migration Job's applied versions.
- If a bad event storms the DLQ, scale the consumer only after the poison message is understood. Republishing the outbox without checking `processed_events` is safe because the consumer dedupes on `event_id`.

## Troubleshooting

| Symptom | Check |
|---|---|
| Readiness never goes green | Database URL, security group, and Flyway. `/actuator/health/readiness` includes the DB. |
| `Production environment must define JWT_PRIVATE_KEY` | Profile is not `local`/`test`/`default` and the PEMs are empty. |
| Payments succeed, notifications stay empty | RabbitMQ credentials, exchange `payments.events`, unpublished rows in `outbox_events`. |
| 401 on every call | `JWT_ISSUER` / `JWT_AUDIENCE` differ from the token, or the public key does not match the private key. |
| 409 on confirm | Another request already moved the payment, or the idempotency key was reused with a different body. |
| 429 on login | Auth rate limit. Honor `Retry-After`. |

## Smoke test

`scripts/validate-api.sh` registers a unique user, logs in, rejects a missing token and a missing idempotency key, creates and confirms a payment, and reads notifications. It prints each status code. Set `BASE_URL` when the process is not on `http://127.0.0.1:8080`.
