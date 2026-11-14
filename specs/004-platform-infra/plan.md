# Implementation Plan: Platform Infrastructure

## Technical context

Docker Engine 29 + BuildKit, Compose v2, Kustomize v5 and kubeconform via container
images (no local kubectl), target Kubernetes ≥ 1.30, GitHub Actions (optional, may be
unpaid → local gate is the evidence).

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Build image | `ARG JAVA_MAJOR=25` then `maven:3.9-eclipse-temurin-${JAVA_MAJOR}-alpine` + `--mount=type=cache,target=/root/.m2` | Official Maven on Temurin 25 Alpine. No second JDK from `apk add maven`. Cache survives rebuilds |
| Runtime image | `eclipse-temurin:${JAVA_MAJOR}-jre-alpine` | Alpine JRE. BusyBox `wget` for the Compose healthcheck. `sh` for the entrypoint and preStop |
| Layering | `java -Djarmode=tools -jar "$JAR_FILE" extract --layers --launcher --destination target/extracted` | Boot tools mode. Same layer directories the runtime copies: `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application` |
| JVM | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+UseCompactObjectHeaders` | `JAVA_OPTS` in the Dockerfile. Container heap cap, exit on OOM, and compact object headers (JDK 25) |
| Port | `SERVER_PORT` > `PORT` > 8080 in entrypoint | Railway/Render/Cloud Run inject `PORT` |
| Secrets in k8s | `secretKeyRef` to externally managed `payments-platform-secrets` | Works with External Secrets / Sealed Secrets / CSI; nothing in Git |
| Migrations (k8s) | One-shot `Job` with the **same image** (`APP_MIGRATION_ONLY=true`, web disabled, listeners off); Deployment runs with `SPRING_FLYWAY_ENABLED=false` | Migrations stay version-locked to the code inside the jar; no ConfigMap size limit (1 MiB) or kustomize load-restrictor hacks needed to ship SQL files; Flyway's PostgreSQL advisory lock makes accidental concurrent runs safe |
| Migrations (PaaS/compose) | Migrate on startup (`SPRING_FLYWAY_ENABLED=true`) | Single instance; simplest |
| Exposure | Ingress routes only `/api` (+ docs in staging); actuator stays cluster-internal | Avoid leaking health/metrics |

Rejected: `flyway/flyway` image + ConfigMap generated from `src/main/resources/db/migration`
— kustomize refuses files outside the kustomization root unless
`--load-restrictor LoadRestrictionsNone`, ConfigMaps cap at 1 MiB and the SQL could drift
from the image actually deployed.

## Deploy sequence (Kubernetes)

1. `kubectl delete job payments-platform-migrate -n <ns> --ignore-not-found` (Jobs are immutable).
2. `kustomize build deploy/k8s/overlays/<env> | kubectl apply -f -`
3. `kubectl wait --for=condition=complete job/payments-platform-migrate -n <ns> --timeout=10m`
4. Until the Job completes, new pods fail readiness (Hibernate `ddl-auto=validate`) and the
   rolling update (`maxUnavailable: 0`) keeps old pods serving. Migrations must stay
   backward-compatible (expand → contract).

## Files

`Dockerfile`, `.dockerignore`, `docker-compose.yml`, `.env.example`,
`scripts/{generate-jwt-keys,ci-local}.sh`, `deploy/k8s/{base,overlays/staging,overlays/production}`,
`deploy/k8s/README.md`, `deploy/cloudrun/service.yaml`, `railway.json`, `render.yaml`,
`.github/workflows/ci.yml`, README section.
