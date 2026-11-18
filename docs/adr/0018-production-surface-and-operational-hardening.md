# ADR 0018 — Production surface, key rotation, rate limiting, and operational retention

- **Status:** Accepted
- **Date:** 2026-11-17
- **Complements:** [ADR 0006](0006-transactional-outbox.md), [ADR 0009](0009-jwt-vs-external-idp.md), [ADR 0010](0010-compose-vs-kubernetes.md), [ADR 0016](0016-event-sourcing-with-hybrid-projections.md)

## Context

A production-readiness review identified operational and surface gaps between a local showcase and an internet-exposed deployment:
1. OpenAPI, Swagger UI, and `/actuator/prometheus` were `permitAll` on port `8080` across all profiles, and the Kubernetes `Ingress` exposed `/`.
2. First-party RS256 JWT signing used a single static key ID without an overlap verification window during rotation.
3. Redis rate limiting executed `INCR` and `EXPIRE` non-atomically, only checked client IP on login, and did not throttle authenticated payment write mutations.
4. Only `idempotency_keys` had scheduled expiration; published `outbox_events`, `processed_events`, and expired `refresh_tokens` read-model rows grew without bound.
5. PaaS connection URLs (`DATABASE_URL`, `REDIS_URL`, `RABBITMQ_URL` / `CLOUDAMQP_URL`) were not normalized automatically, the production `Dockerfile` exposed debug port `5005`, and Kubernetes manifests used mutable `:latest` tags and open egress.

## Decision

1. **Fail-closed production surface**: `application-prod.yml` disables Springdoc endpoints and sets `app.security.expose-public-docs-and-metrics=false`, requiring `ROLE_ADMIN` on `/actuator/prometheus`, `/actuator/metrics/**`, `/v3/api-docs/**`, and `/swagger-ui/**` while keeping `/actuator/health/**` and `/actuator/info` public for probes. Kubernetes `Ingress` routes `/api` only, and `GlobalExceptionHandler` maps unmapped paths (`NoResourceFoundException`) to `404` and unsupported verbs to `405` instead of `500`.
2. **Dual-key JWT verification window**: `JwtEncoder` signs with `app.jwt.key-id`, while `JwtDecoder` verifies against an `ImmutableJWKSet` containing both the active public key and an optional `app.jwt.previous-public-key-pem` (`app.jwt.previous-key-id`).
3. **Atomic Lua rate limiting**: `AuthRateLimiter` and `PaymentWriteRateLimiter` execute an atomic Lua `INCR` + `PEXPIRE` script with bounded in-memory fallback. Login enforces both per-IP and per-account (`SHA-256` digest of normalized email) limits; `POST /api/v1/payments/**` enforces per-user write limits while leaving `GET` reads unthrottled (`p95 < 50ms`).
4. **Bounded operational retention (`V8`)**: `OperationalRetentionCleanup` deletes aged published `outbox_events` and `processed_events` in batches of 500; `RefreshTokenCleanup` deletes expired `refresh_tokens` rows past a grace TTL while keeping unexpired revoked tokens for reuse detection and leaving `event_store` immutable.
5. **Plug-and-play PaaS & hardened manifests**: `ServiceUrlEnvironmentPostProcessor` translates `DATABASE_URL`, `REDIS_URL`, and `RABBITMQ_URL`/`CLOUDAMQP_URL` when explicit `DB_URL`/`REDIS_HOST`/`RABBITMQ_HOST` variables are unset. `Dockerfile` exposes only `8080`, Compose accepts `PAYMENTS_IMAGE`, `scripts/docker-publish.sh` builds `linux/amd64,linux/arm64` with immutable tags, Kustomize pins versioned images, both `Deployment` and `flyway-job` run non-root with `RuntimeDefault` seccomp and disabled service-account token mounting, and `NetworkPolicy` restricts egress to explicit ports.

## Consequences

Real-money PSP settlement, double-entry accounting ledgers, and external notification providers remain intentionally outside the sample platform scope. All operational state tables now have bounded growth, zero-downtime JWT rotation works without external IdP coupling, and local/test observability flows remain unchanged.

## Eval

 Verified by `ServiceUrlEnvironmentPostProcessorTest`, `JwtKeyConfigTest`, `ProdSecuritySurfaceIT`, `RateLimitingIT`, `OperationalRetentionIT`, and strict `kubeconform` 1.30.0 checks in `scripts/ci-local.sh`.
