# ADR 0010 — Docker Compose for local, Kubernetes for production shape

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Developers need PostgreSQL, Redis, RabbitMQ, and the app on a laptop. Production needs probes, rolling updates, a migration Job, an HPA, and TLS at the ingress. Those are not the same file.

## Decision

- `Dockerfile`: multi-stage build, non-root user `10001`, layered runtime image.
- `docker-compose.yml`: app, PostgreSQL 16, Redis 7, RabbitMQ 3.13, healthchecks, named volumes. Variable substitution uses the process environment and a local `.env` when Docker Compose loads one. `.env.example` lists the names and is not referenced by the compose file. CI runs `docker compose config` and does not start these containers. `mvn -B clean verify` uses Testcontainers.
- `deploy/k8s`: Kustomize base plus `staging` and `production` overlays. Deployment, Service, ConfigMap, Secret example, probes, requests and limits, HPA, PDB, NetworkPolicy, Ingress with cert-manager TLS, and a Flyway Job. CI renders both overlays through kubeconform (Kubernetes 1.30, strict).
- The Deployment inherits `SPRING_FLYWAY_ENABLED=false` from the ConfigMap, so application pods do not migrate. The Job overrides `SPRING_FLYWAY_ENABLED=true` and sets `APP_MIGRATION_ONLY=true`. No Java source reads `APP_MIGRATION_ONLY` or `app.migration-only`. After Flyway runs as part of normal startup, the process stays up as the full application, including the web server and the relay. The Job manifest records the intended one-shot strategy; the running image does not exit when that variable is set.
- Compose is not applied to a cluster. Kustomize is not required for `mvn spring-boot:run`.

## Consequences

- Two manifests to keep in sync when env vars change. `.env.example`, the ConfigMap, and `secret.example.yaml` are the checklists.
- Local compose runs the app as a container only when you ask it to. Tests use Testcontainers and do not need the compose app.
- Ingress TLS is a cluster concern (cert-manager). The image does not terminate TLS.

## Alternatives

| Option | Why not |
|---|---|
| Compose in production | No PDB, no HPA, no migration Job ordering, weaker health integration. |
| Helm only | Kustomize is enough for one service and is validated with `kubeconform` without a cluster. |
| Kubernetes for local inner loop | Slower than Testcontainers plus `mvn spring-boot:run`. |
