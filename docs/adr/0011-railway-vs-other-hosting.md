# ADR 0011 — Railway as the primary host, not Vercel

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The process is a long-running JVM. It holds JDBC pools, a RabbitMQ consumer, and a scheduled outbox relay. It also needs PostgreSQL, Redis, and RabbitMQ as adjacent services. The build is a Docker image.

Vercel Functions are a poor fit: short invocations, no durable consumer loop, and no collocated RabbitMQ worker. Splitting the relay into a second platform just to use Functions would fork the outbox worker from the API for no product reason.

## Decision

Prefer Railway for a first deploy of the Docker image (`railway.json`, healthcheck `/actuator/health/readiness`).

Documented alternatives, same image:

- Render web service (`render.yaml`). Postgres, Redis, and RabbitMQ are external services: the file supplies `DB_URL`, Redis, and RabbitMQ as `sync: false` environment variables and does not declare a Render database.
- Google Cloud Run (`deploy/cloudrun/service.yaml`) only if min instances stay at 1 so the consumer and the relay stay running between requests. The manifest sets `autoscaling.knative.dev/minScale` to `"1"` and `run.googleapis.com/cpu-throttling` to `"false"`. Cloud Run is a weaker fit for a continuous consumer than an always-on service, which is why it is not the default.

Do not deploy this repository to Vercel Functions.

No production project is created from this repo. Hosting docs list the env vars and the rollback. Applying them spends money and needs an explicit decision.

## Consequences

- One container runs the API, the relay, and the consumer. Scale-out means competing consumers plus `SKIP LOCKED` on the outbox, which is supported, and more than one relay is safe.
- Railway's private networking must reach Postgres, Redis, and RabbitMQ. Those may be Railway plugins or managed services elsewhere. The app does not embed them.
- Cloud Run remains a documented escape hatch with `minScale: 1` and CPU throttling disabled so the relay keeps polling.

## Alternatives

| Option | Why not the default |
|---|---|
| Vercel Functions | No long-lived RabbitMQ consumer, no natural place for the relay loop. |
| A VM and systemd | Works, but the image and probes already exist and Compose/Kubernetes are the documented runtimes. |
| Cloud Run as primary | Scale-to-zero fights the relay unless min instances are pinned. |
