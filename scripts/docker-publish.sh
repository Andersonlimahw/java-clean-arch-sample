#!/usr/bin/env bash
# Build (and optionally push) a multi-architecture container image for Docker Hub or GHCR.
# Usage: bash scripts/docker-publish.sh --image <owner/repo> --tag <version> [--platforms linux/amd64,linux/arm64] [--push] [--dry-run] [--allow-latest]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

IMAGE=""
TAG=""
PLATFORMS="linux/amd64,linux/arm64"
PUSH=0
DRY_RUN=0
ALLOW_LATEST=0

usage() {
  cat <<'USAGE' >&2
Usage: scripts/docker-publish.sh --image <owner/repo> --tag <version> [options]

Options:
  --image <repo>        Target repository, e.g. docker.io/acme/payments-platform
  --tag <version>       Immutable release tag, e.g. 0.1.0 (latest rejected without --allow-latest)
  --platforms <list>    Target platforms (default: linux/amd64,linux/arm64)
  --push                Push multi-arch manifest to registry (requires prior `docker login`)
  --allow-latest        Also permit --tag latest
  --dry-run             Print validated buildx arguments without executing Docker
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)
      IMAGE="${2:-}"
      shift 2
      ;;
    --tag)
      TAG="${2:-}"
      shift 2
      ;;
    --platforms)
      PLATFORMS="${2:-}"
      shift 2
      ;;
    --push)
      PUSH=1
      shift
      ;;
    --allow-latest)
      ALLOW_LATEST=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$IMAGE" || -z "$TAG" ]]; then
  echo "Both --image and --tag are required." >&2
  usage
  exit 2
fi

if [[ "$TAG" == "latest" && "$ALLOW_LATEST" -ne 1 ]]; then
  echo "Refusing mutable --tag latest without --allow-latest. Pass an immutable version tag (e.g. 0.1.0)." >&2
  exit 1
fi

SHORT_SHA="$(git rev-parse --short=12 HEAD 2>/dev/null || echo "unknown")"
CMD=(docker buildx build --platform "$PLATFORMS" -t "${IMAGE}:${TAG}" -t "${IMAGE}:sha-${SHORT_SHA}")
if [[ "$PUSH" -eq 1 ]]; then
  CMD+=(--push)
fi
CMD+=(.)

if [[ "$DRY_RUN" -eq 1 ]]; then
  printf 'DRY-RUN:'
  printf ' %q' "${CMD[@]}"
  printf '\n'
  exit 0
fi

"${CMD[@]}"
