# Kubernetes

Base in `deploy/k8s/base`, overlays in `deploy/k8s/overlays/staging` and `production`.

## Objects

| File | Kind | Role |
|---|---|---|
| `namespace.yaml` | Namespace | `payments-system` in the base. Overlays rewrite it to `payments-staging` or `payments-production`. |
| `configmap.yaml` | ConfigMap | Non-secret settings. `SPRING_FLYWAY_ENABLED=false` on the API. `SPRING_PROFILES_ACTIVE=prod`. |
| `secret.example.yaml` | Secret example | Not in the kustomization, so `kustomize build` does not emit it. Passwords and both PEMs contain `CHANGE_ME` and are not usable keys. Copy `secrets.env.example` in this directory instead of applying this file. |
| `deployment.yaml` | Deployment | `payments-deployment`. Non-root, read-only root filesystem, requests and limits, startup, liveness, readiness. Image `payments-platform:latest`, `imagePullPolicy: IfNotPresent`. |
| `service.yaml` | Service | ClusterIP port 80, targetPort 8080. |
| `hpa.yaml` | HPA | CPU 75% and memory 80%. Base `minReplicas` 2, `maxReplicas` 10. The staging overlay sets `minReplicas` to 1 so the single-replica patch is not raised by the HPA floor. Production starts at 3 replicas inside the base 2–10 range. |
| `pdb.yaml` | PodDisruptionBudget | `minAvailable: 1`. With staging's single replica, a voluntary eviction waits until another pod is up. |
| `ingress.yaml` | Ingress | TLS via cert-manager (`letsencrypt-prod`) and nginx. Backend is Service port 80. |
| `networkpolicy.yaml` | NetworkPolicy | Selects pods labeled `app.kubernetes.io/name: payments-platform`. Ingress allows TCP 8080 from every namespace (`namespaceSelector: {}`). Egress is unrestricted so managed PostgreSQL, Redis, and RabbitMQ outside the cluster stay reachable. The migration Job is not selected; its label is `app.kubernetes.io/component: migration`. |
| `flyway-job.yaml` | Job | Name `payments-flyway-migration`. Same image. Env `SPRING_FLYWAY_ENABLED=true` and `APP_MIGRATION_ONLY=true` (profile `migration`). Exits `0` only after Flyway has applied the schema and left nothing pending, with HTTP, Rabbit listeners, and the outbox scheduler off. Any other exit fails the container; `backoffLimit` is 3. |

## Image

The manifests reference `payments-platform:latest` and do not pull from a registry unless that tag is already present (`IfNotPresent`). That image is the repository `Dockerfile`: Eclipse Temurin JRE 25, same non-root user and JVM flags as local Compose. Compose does not publish this name. Build it and load it into the cluster (or push it and set the image in the overlay) before applying:

```bash
docker build -t payments-platform:latest .
# kind load docker-image payments-platform:latest
```

## Validate

Same flags as `scripts/ci-local.sh`:

```bash
kustomize build deploy/k8s/overlays/staging | kubeconform -strict -kubernetes-version 1.30.0 -summary
kustomize build deploy/k8s/overlays/production | kubeconform -strict -kubernetes-version 1.30.0 -summary
```

`scripts/k8s-apply.sh --plan deploy/k8s/overlays/staging` prints the apply order without talking to a cluster. The migration Job is after the Namespace and ConfigMap and before the Deployment.

## Apply

This does not talk to a cluster unless you run it against one. When a cluster exists, fill `secrets.env` from `deploy/k8s/secrets.env.example` (real PEMs, not `CHANGE_ME`) and apply. A second run updates the Secret from a client-side dry-run and deletes the previous migration Job before applying it, so the new image runs Flyway again. Flyway itself skips versions that already applied.

```bash
cp deploy/k8s/secrets.env.example secrets.env
# edit secrets.env
./scripts/k8s-apply.sh deploy/k8s/overlays/staging --secret-env-file secrets.env
kubectl -n payments-staging rollout status deployment/payments-deployment
```

Production uses the same script with `deploy/k8s/overlays/production`. The rendered namespace is `payments-production`.

Managed PostgreSQL, Redis, and RabbitMQ stay outside the cluster. The manifests reference them through `payments-secret`. The image does not bundle those services.

Probes hit actuator health groups. A database outage fails readiness and the Service stops sending traffic. Liveness stays up unless the process itself is stuck, so a database blip does not restart every pod.

Rollback: `kubectl rollout undo deployment/payments-deployment -n payments-staging`. Flyway versions that already applied stay applied. Undo the production overlay with `-n payments-production`.
