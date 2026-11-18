# Plan 013 — Production Hardening

1. **Shared config & security**:
   - `ServiceUrlEnvironmentPostProcessor` in `META-INF/spring.factories` for `DATABASE_URL`, `REDIS_URL`, and `RABBITMQ_URL`/`CLOUDAMQP_URL`.
   - `SecurityConfig` + `application.yml` / `application-prod.yml` gating `/actuator/prometheus` and OpenAPI/Swagger in `prod`.
   - `JwtProperties` + `JwtKeyConfig` multi-key verification set (`JWKSet` with active + optional previous RSA public key).
2. **Rate limiting**:
   - Atomic Lua counter in `AuthRateLimiter` + SHA-256 account bucket on login.
   - `PaymentWriteRateLimitPort` + `PaymentWriteRateLimiter` wired into `PaymentController` `POST` endpoints.
3. **Operational retention**:
   - `V8__operational_retention_indexes.sql`.
   - `OperationalRetentionProperties` + `OperationalRetentionCleanup` (`shared`) + `RefreshTokenCleanup` (`identity`).
4. **Delivery & infra hardening**:
   - `Dockerfile`, `docker-compose.yml`, `scripts/docker-publish.sh`, `Makefile`.
   - `deploy/k8s/base/*` (`kustomization.yaml`, `deployment.yaml`, `flyway-job.yaml`, `ingress.yaml`, `networkpolicy.yaml`) + `scripts/k8s-apply.sh`.
   - `.github/workflows/ci.yml`, ADR 0018, docs (`deploy.md`, `data.md`, `api.md`, `architecture.md`, `README.md`, `llms.txt`).
