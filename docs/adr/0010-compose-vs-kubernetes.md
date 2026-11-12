# ADR 0010 — Docker Compose for local, Kubernetes for production shape

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Developers need PostgreSQL, Redis, RabbitMQ, and the app on a laptop. Production needs probes, rolling updates, a migration Job, an HPA, and TLS at the ingress. Those are not the same file.

## Decision

- `Dockerfile`: multi-stage build, non-root user `10001`, layered runtime image.
- `docker-compose.yml`: app, PostgreSQL 16, Redis 7, RabbitMQ 3.13, healthchecks, named volumes, env from `.env.example`. This is the local and CI-shaped stack.
- `deploy/k8s`: Kustomize base plus `staging` and `production` overlays. Deployment, Service, ConfigMap, Secret example, probes, requests and limits, HPA, PDB, NetworkPolicy, Ingress with cert-manager TLS, and a Flyway Job.
- The Deployment does not run Flyway (`SPRING_FLYWAY_ENABLED=false` in the ConfigMap). The Job sets `SPRING_FLYWAY_ENABLED=true` and `APP_MIGRATION_ONLY=true`. The Job manifest is the migration strategy; the process honours that flag in the migration-job change, which is what makes the container exit after Flyway.
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
