#!/usr/bin/env bash
# Apply a payments kustomize overlay in boot order.
# Namespace and ConfigMaps first, then payments-secret, then the migration Job,
# then the Deployment and the remaining objects. One `kubectl apply -k` cannot
# express that order: the Job and the Deployment both reference the Secret, and
# the Job must exit before the API (Flyway off) starts serving.
#
# Usage:
#   scripts/k8s-apply.sh --plan deploy/k8s/overlays/staging
#   scripts/k8s-apply.sh deploy/k8s/overlays/staging --secret-env-file secrets.env
set -euo pipefail

PLAN_ONLY=0
OVERLAY=""
SECRET_ENV_FILE=""
SECRET_NAME="payments-secret"

usage() {
  echo "Usage: $0 [--plan] [--secret-env-file FILE] OVERLAY" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --plan)
      PLAN_ONLY=1
      shift
      ;;
    --secret-env-file)
      if [[ $# -lt 2 ]]; then
        usage
      fi
      SECRET_ENV_FILE="$2"
      shift 2
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      ;;
    *)
      if [[ -n "$OVERLAY" ]]; then
        echo "Unexpected argument: $1" >&2
        usage
      fi
      OVERLAY="$1"
      shift
      ;;
  esac
done

if [[ -z "$OVERLAY" ]]; then
  usage
fi

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing dependency: $1" >&2
    exit 1
  fi
}

require_cmd kustomize
require_cmd python3
if [[ "$PLAN_ONLY" -eq 0 ]]; then
  require_cmd kubectl
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

rendered="$(mktemp)"
workdir="$(mktemp -d)"
trap 'rm -rf "$rendered" "$workdir"' EXIT

kustomize build "$OVERLAY" > "$rendered"

python3 - "$rendered" "$workdir" <<'PY'
import pathlib
import sys

src = pathlib.Path(sys.argv[1])
dest = pathlib.Path(sys.argv[2])
text = src.read_text()
docs = [chunk.strip() + "\n" for chunk in text.split("\n---\n") if chunk.strip()]

def meta_name(doc: str) -> str:
    in_meta = False
    for line in doc.splitlines():
        if line.startswith("metadata:"):
            in_meta = True
            continue
        if in_meta:
            if line.startswith("  name:"):
                return line.split(":", 1)[1].strip().strip('"').strip("'")
            if line and not line.startswith(" "):
                break
    return ""

def kind_of(doc: str) -> str:
    for line in doc.splitlines():
        if line.startswith("kind:"):
            return line.split(":", 1)[1].strip().strip('"').strip("'")
    return ""

groups = {"namespace": [], "configmap": [], "job": [], "rest": []}
for doc in docs:
    kind = kind_of(doc)
    if kind == "Namespace":
        groups["namespace"].append(doc)
    elif kind == "ConfigMap":
        groups["configmap"].append(doc)
    elif kind == "Job":
        groups["job"].append(doc)
    else:
        groups["rest"].append(doc)

errors = []
if not groups["namespace"]:
    errors.append("rendered manifest has no Namespace")
if not groups["configmap"]:
    errors.append("rendered manifest has no ConfigMap (the migration Job reads payments-config)")
if not groups["job"]:
    errors.append("rendered manifest has no Job")
if not any(kind_of(doc) == "Deployment" for doc in groups["rest"]):
    errors.append("rendered manifest has no Deployment in the post-migration group")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)

order = []
for phase, key in enumerate(("namespace", "configmap", "job", "rest"), start=1):
    path = dest / f"{phase:02d}-{key}.yaml"
    body = "\n---\n".join(doc.strip() for doc in groups[key]) + "\n"
    path.write_text(body)
    for doc in groups[key]:
        order.append(f"{phase} {kind_of(doc)} {meta_name(doc)}")

namespace = meta_name(groups["namespace"][0])
(dest / "namespace.txt").write_text(namespace + "\n")
job_names = [meta_name(doc) for doc in groups["job"]]
(dest / "jobs.txt").write_text("\n".join(job_names) + "\n")
(dest / "order.txt").write_text("\n".join(order) + "\n")

# ConfigMaps and the Namespace must be applied before the Job, and the Job
# before any Deployment. Fail the render if that order is not what we wrote.
phases = {}
for line in order:
    phase, kind, name = line.split(" ", 2)
    phases.setdefault(kind, []).append(int(phase))
if min(phases["Job"]) <= max(phases["Namespace"] + phases["ConfigMap"]):
    print("Job is not ordered after Namespace and ConfigMap", file=sys.stderr)
    sys.exit(1)
if min(phases["Deployment"]) <= max(phases["Job"]):
    print("Deployment is not ordered after the migration Job", file=sys.stderr)
    sys.exit(1)
PY

if [[ "$PLAN_ONLY" -eq 1 ]]; then
  cat "$workdir/order.txt"
  exit 0
fi

namespace="$(tr -d '[:space:]' < "$workdir/namespace.txt")"
if [[ -z "$namespace" ]]; then
  echo "Could not read the Namespace name from the render" >&2
  exit 1
fi

echo "Applying namespace and ConfigMaps to $namespace"
kubectl apply -f "$workdir/01-namespace.yaml"
kubectl apply -f "$workdir/02-configmap.yaml"

if [[ -n "$SECRET_ENV_FILE" ]]; then
  if [[ ! -f "$SECRET_ENV_FILE" ]]; then
    echo "Secret env file not found: $SECRET_ENV_FILE" >&2
    exit 1
  fi
  echo "Applying Secret $SECRET_NAME from $SECRET_ENV_FILE"
  kubectl -n "$namespace" create secret generic "$SECRET_NAME" \
    --from-env-file="$SECRET_ENV_FILE" \
    --dry-run=client -o yaml | kubectl apply -f -
else
  if ! kubectl -n "$namespace" get secret "$SECRET_NAME" >/dev/null 2>&1; then
    echo "Secret $SECRET_NAME is missing in namespace $namespace." >&2
    echo "Copy deploy/k8s/secrets.env.example to secrets.env, replace every CHANGE_ME, then re-run with --secret-env-file secrets.env" >&2
    exit 1
  fi
fi

echo "Ensure the pinned container image in kustomization.yaml is pushed or loaded before starting the Job."
echo "Build and load or push that tag before the Job can start."

echo "Applying migration Job"
while IFS= read -r job_name; do
  if [[ -n "$job_name" ]]; then
    kubectl -n "$namespace" delete job "$job_name" --ignore-not-found --wait=true
  fi
done < "$workdir/jobs.txt"
kubectl apply -f "$workdir/03-job.yaml"
# Rendered name is payments-flyway-migration (deploy/k8s/base/flyway-job.yaml).
# Complete means the container exited 0: Flyway applied the schema, nothing is
# pending, and HTTP, Rabbit listeners, and the outbox scheduler stayed off.
while IFS= read -r job_name; do
  if [[ -n "$job_name" ]]; then
    kubectl -n "$namespace" wait --for=condition=complete "job/$job_name" --timeout=180s
  fi
done < "$workdir/jobs.txt"

echo "Applying Deployment and remaining objects"
kubectl apply -f "$workdir/04-rest.yaml"
kubectl -n "$namespace" rollout status deployment/payments-deployment --timeout=180s
echo "Apply finished for $namespace"
