# Spec 013 — Production Surface, Operational Hardening, and Plug-and-Play Delivery

- **Status**: Implemented
- **Updated**: 2026-11-17

## Goal

Close the platform, security, retention, rate-limiting, PaaS URL binding, and container/Kubernetes hardening gaps identified during the production-readiness review while keeping real-money PSP settlement explicitly out of scope for this sample platform.

## Scope & Acceptance Criteria

1. **Production HTTP & Ingress surface**:
   - Disable Swagger UI and OpenAPI endpoints by default on the `prod` profile and require `ROLE_ADMIN` for `/actuator/prometheus`, `/actuator/metrics/**`, `/v3/api-docs/**`, and `/swagger-ui/**` when `app.security.expose-public-docs-and-metrics=false`.
   - Keep `/actuator/health/**` and `/actuator/info` public for platform liveness/readiness probes.
   - Restrict Kubernetes `Ingress` routing to `/api` (`Prefix`) instead of `/`.
2. **Zero-downtime JWT RS256 key rotation**:
   - Sign access tokens with the active `JWT_KEY_ID` key pair while accepting an optional `JWT_PREVIOUS_PUBLIC_KEY` (`JWT_PREVIOUS_KEY_ID`) during verification so active tokens survive key rotation.
   - Preserve fail-closed startup on `prod` when primary RSA keys are absent.
3. **Atomic Redis rate limiting & write/account protection**:
   - Replace non-atomic `INCR` + `EXPIRE` with an atomic Redis Lua window script (with bounded in-memory fallback).
   - Enforce per-account login rate limiting (`ratelimit:auth:account:<sha256>`) alongside per-IP rate limiting, without storing raw email PII in Redis keys.
   - Enforce per-user rate limiting on payment write mutations (`POST /api/v1/payments/**`) while keeping `GET` queries unthrottled (`p95 < 50ms`).
4. **Bounded operational table retention**:
   - Add Flyway `V8__operational_retention_indexes.sql` indexing `outbox_events(published_at) WHERE published_at IS NOT NULL`, `processed_events(processed_at)`, and `refresh_tokens(expires_at)`.
   - Periodically purge aged published outbox rows, aged processed-event deduplication rows, and expired refresh-token projection rows in bounded batches without touching unpublished outbox rows or immutable `event_store` history.
5. **Plug-and-play Docker Hub & PaaS bindings**:
   - Automatically translate standard PaaS URLs (`DATABASE_URL`, `REDIS_URL`, `RABBITMQ_URL` / `CLOUDAMQP_URL`, including `rediss://` and `amqps://` TLS schemes) when explicit `DB_URL` / `REDIS_HOST` / `RABBITMQ_HOST` variables are unset, and expose `REDIS_SSL_ENABLED` / `RABBITMQ_SSL_ENABLED`.
   - Support prebuilt `PAYMENTS_IMAGE` in `docker-compose.yml` and provide `scripts/docker-publish.sh` for multi-arch (`linux/amd64,linux/arm64`) builds.
6. **Container, Kubernetes, and CI hardening**:
   - Remove debug port `5005` from production `Dockerfile` `EXPOSE`.
   - Pin versioned image tags in Kustomize, disable service-account token auto-mounting, enforce non-root + read-only rootfs + `RuntimeDefault` seccomp on both `Deployment` and `flyway-job`, and restrict `NetworkPolicy` egress to explicit infrastructure/HTTPS ports.
   - Enforce docs, AI-tooling, and Trivy security scanning in `.github/workflows/ci.yml`.
