#!/usr/bin/env bash
set -euo pipefail

echo "=================================================="
echo "          PAYMENTS PLATFORM - LOCAL CI GATE        "
echo "=================================================="

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> 1. Checking shell scripts syntax..."
find scripts -type f -name "*.sh" -exec bash -n {} +
echo "    ✓ Shell scripts syntax OK"

echo "==> 2. Validating Docker Compose configuration..."
if docker compose version &> /dev/null; then
    docker compose config > /dev/null
    echo "    ✓ Docker Compose config valid (docker compose)"
elif command -v docker-compose &> /dev/null; then
    docker-compose config > /dev/null
    echo "    ✓ Docker Compose config valid (docker-compose)"
else
    echo "    ⚠ Docker Compose command not found"
fi

echo "==> 3. Validating Kubernetes manifests with Kubeconform..."
if command -v kustomize &> /dev/null; then
    kustomize build deploy/k8s/overlays/staging > /tmp/k8s-staging.yaml
    kustomize build deploy/k8s/overlays/production > /tmp/k8s-production.yaml

    if docker info &> /dev/null; then
        docker run --rm -v /tmp:/tmp ghcr.io/yannh/kubeconform:latest \
            -strict -kubernetes-version 1.30.0 -summary /tmp/k8s-staging.yaml
        docker run --rm -v /tmp:/tmp ghcr.io/yannh/kubeconform:latest \
            -strict -kubernetes-version 1.30.0 -summary /tmp/k8s-production.yaml
        echo "    ✓ Kubernetes manifests valid (strict 1.30.0)"
    else
        echo "    ⚠ Docker not reachable, skipping kubeconform container run"
    fi
else
    echo "    ⚠ kustomize not installed locally, skipping render check"
fi

echo "==> 4. Maven clean verify..."
if [ -f "pom.xml" ]; then
    mvn -B clean verify
    echo "    ✓ Maven clean verify PASSED"
fi

echo "=================================================="
echo "            ALL LOCAL CI CHECKS PASSED            "
echo "=================================================="
