---
name: local-gate
description: Run the required local quality gate, Docker and Kubernetes validation, Maven unit/integration tests and documentation checks before a PR or merge.
argument-hint: "[focused-test]"
---

# local-gate

Read AGENTS.md and docs/testing.md. Run from the repository root. An optional test name from the request is a fast feedback step, never a replacement for the full gate.

1. Check `rtk git status`, Java 25 (`rtk proxy java -version`), Docker (`rtk proxy docker info`) and kustomize. Do not print environment values or secrets.
2. Optional focused test: `rtk mvn -B -Dtest=<TestName> test`. Integration tests use `rtk mvn -B -Dit.test=<Name>IT test-compile failsafe:integration-test failsafe:verify`.
3. Run `rtk proxy bash scripts/ci-local.sh`: shell syntax, Compose base/debug, strict kubeconform staging/production, then `mvn -B clean verify` with real Testcontainers. On macOS Colima use `rtk proxy env TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock bash scripts/ci-local.sh`. Ensure the Docker endpoint is configured for Colima; never guess a socket or weaken TLS.
4. Run `rtk proxy bash scripts/validate-docs.sh`, `rtk proxy python3 -I scripts/validate-ai-tooling.py`, and `rtk git diff --check`.
5. Record exit status, unit and integration totals from target/surefire-reports and target/failsafe-reports, and skipped tests separately. Missing Docker or skipped infrastructure is a failure. GitHub Actions is optional and is never local evidence.
6. Before merging, fetch main, merge it into the feature branch (never rebase), and repeat this gate after the merge. Preserve unrelated work. Do not push directly to main.

Done: all required commands exit zero on the final revision; report real counts and any checks not run.
