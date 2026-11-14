#!/usr/bin/env bash
set -euo pipefail

echo "=================================================="
echo "          PAYMENTS PLATFORM - LOCAL CI GATE        "
echo "=================================================="

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/payments-ci.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> 1. Checking shell scripts syntax..."
find scripts -type f -name "*.sh" -exec bash -n {} +
echo "    ✓ Shell scripts syntax OK"

echo "==> 2. Validating Docker Compose configuration..."
if docker compose version &> /dev/null; then
    docker compose config > /dev/null
    docker compose -f docker-compose.yml -f docker-compose.debug.yml config > /dev/null
    echo "    ✓ Docker Compose config valid (docker compose, base + debug)"
elif command -v docker-compose &> /dev/null; then
    docker-compose config > /dev/null
    docker-compose -f docker-compose.yml -f docker-compose.debug.yml config > /dev/null
    echo "    ✓ Docker Compose config valid (docker-compose, base + debug)"
else
    echo "    ✗ Docker Compose command not found" >&2
    exit 1
fi

echo "==> 3. Validating Kubernetes manifests with Kubeconform..."
if ! command -v kustomize &> /dev/null; then
    echo "    ✗ kustomize is required for the local CI gate" >&2
    exit 1
fi
if ! docker info &> /dev/null; then
    echo "    ✗ Docker daemon is required for kubeconform and Testcontainers" >&2
    exit 1
fi
kustomize build deploy/k8s/overlays/staging > "$WORK_DIR/k8s-staging.yaml"
kustomize build deploy/k8s/overlays/production > "$WORK_DIR/k8s-production.yaml"
docker run --rm -v "$WORK_DIR:/work:ro" ghcr.io/yannh/kubeconform:latest \
    -strict -kubernetes-version 1.30.0 -summary /work/k8s-staging.yaml
docker run --rm -v "$WORK_DIR:/work:ro" ghcr.io/yannh/kubeconform:latest \
    -strict -kubernetes-version 1.30.0 -summary /work/k8s-production.yaml
echo "    ✓ Kubernetes manifests valid (strict 1.30.0)"

echo "==> 4. Maven clean verify..."
if [ -f "pom.xml" ]; then
    mvn -B clean verify
    echo "    ✓ Maven clean verify PASSED"
fi

echo "=================================================="
echo "            ALL LOCAL CI CHECKS PASSED            "
echo "=================================================="
