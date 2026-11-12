# Kubernetes

Base in `deploy/k8s/base`, overlays in `deploy/k8s/overlays/staging` and `production`.

## Objects

| File | Kind | Role |
|---|---|---|
| `namespace.yaml` | Namespace | `payments-system` in the base. Overlays rewrite it. |
| `configmap.yaml` | ConfigMap | Non-secret settings. `SPRING_FLYWAY_ENABLED=false` on the API. |
| `secret.example.yaml` | Secret example | Not applied as-is. Replace every `CHANGE_ME` value out of band. |
| `deployment.yaml` | Deployment | Non-root, read-only root filesystem, requests and limits, startup, liveness, readiness. |
| `service.yaml` | Service | ClusterIP in front of port 8080. |
| `hpa.yaml` | HPA | CPU 75% and memory 80%, 2–10 replicas. |
| `pdb.yaml` | PodDisruptionBudget | Keeps a pod up during voluntary disruption. |
| `ingress.yaml` | Ingress | TLS via cert-manager (`letsencrypt-prod`) and nginx. |
| `networkpolicy.yaml` | NetworkPolicy | Restricts ingress to the ingress controller and egress to data stores. |
| `flyway-job.yaml` | Job | Same image, `SPRING_FLYWAY_ENABLED=true`, `APP_MIGRATION_ONLY=true`. |

## Validate

```bash
kustomize build deploy/k8s/overlays/staging | kubeconform -strict -kubernetes-version 1.30.0 -summary
kustomize build deploy/k8s/overlays/production | kubeconform -strict -kubernetes-version 1.30.0 -summary
```

## Apply

This does not talk to a cluster by itself. When a cluster exists:

```bash
kubectl apply -k deploy/k8s/overlays/staging
kubectl -n payments-staging create secret generic payments-secret --from-env-file=secrets.env
kubectl -n payments-staging delete job payments-flyway-migration --ignore-not-found
kubectl apply -k deploy/k8s/overlays/staging
kubectl -n payments-staging wait --for=condition=complete job/payments-flyway-migration --timeout=180s
kubectl -n payments-staging rollout status deployment/payments-deployment
```

Create the Secret before the Job or the first apply will fail to start the container. `secret.example.yaml` shows the keys.

Managed PostgreSQL, Redis, and RabbitMQ stay outside the cluster. The manifests reference them through the Secret. The image does not bundle those services.

Probes hit actuator health groups. A database outage fails readiness and the Service stops sending traffic. Liveness stays up unless the process itself is stuck, so a database blip does not restart every pod.

Rollback: `kubectl rollout undo deployment/payments-deployment`. Flyway versions that already applied stay applied.
