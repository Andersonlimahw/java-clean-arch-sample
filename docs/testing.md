---
title: Testing
status: active
updated: 2026-11-13
related: [architecture.md, deploy.md, ../scripts/ci-local.sh]
---

# Testing guide

## Test pyramid

1. **Unit tests** use JUnit and focused fakes/mocks for domain rules, application handlers, cache behavior, retry/backoff helpers, and web error mapping. Maven Surefire selects `*Test.java` and excludes `*IT.java`.
2. **Integration tests** use Spring and Testcontainers for PostgreSQL 16, RabbitMQ 3.13, and Redis 7. Maven Failsafe selects `*IT.java`. The integration suite covers HTTP/security, migrations, event delivery, idempotency, cache, fraud screening, concurrency, and observability.
3. **HTTP smoke and performance checks** exercise a running local stack through the Postman/Newman collection, `scripts/validate-api.sh`, and the k6 scenario in `perf/`.

The repository has no Maven Wrapper. Install Maven and invoke `mvn` from the project root.

## Canonical local gate

`scripts/ci-local.sh` is strict by design. It validates shell syntax, Docker Compose, both Kubernetes overlays with kustomize and kubeconform, and then runs `mvn -B clean verify`. Missing Docker, Compose, kustomize, or a reachable Docker daemon is a failed gate; the script never reports skipped infrastructure as success.

```bash
rtk proxy bash scripts/ci-local.sh
```

The gate writes rendered manifests to a temporary directory and removes them on exit. Use the focused commands above only for iteration; they are not a substitute for the full gate.

## Maven commands

```bash
# Unit tests only
mvn -q test

# Integration tests only; compiles test classes, then invokes the Failsafe goals
mvn -q test-compile failsafe:integration-test failsafe:verify

# Canonical full build: unit tests, integration tests, and verification
mvn -q clean verify
```

The integration tests start Docker containers. Keep Docker or Colima available for the full suite.

## macOS + Colima

Prerequisites:

- JDK 25. Spring Boot 3.5.16 supports Java 17 through 25; Java 26 is not supported by this project.
- Maven installed on the host. The project does not include `mvnw`.
- Colima running with Docker-compatible CLI access; verify with `docker info`.

Select Java 25 with your installed JDK manager. Example for SDKMAN:

```bash
sdk default java 25.0.3-jbr
mvn -version # confirm Maven reports Java 25 for the build
```

Colima's socket mount can prevent Testcontainers from starting Ryuk. Add these exports to `~/.zshrc`:

```bash
export TESTCONTAINERS_RYUK_DISABLED=true
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Load the shell settings, then run the complete build:

```bash
source ~/.zshrc
mvn clean verify
```

`TESTCONTAINERS_RYUK_DISABLED` disables the Testcontainers resource reaper for this machine. `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` supplies the socket path inside the Colima VM for containers that need a Docker socket mount. If you do not want persistent shell settings, export both variables in the current terminal before running Maven.

| Symptom | Cause | Correction |
|---|---|---|
| `error while creating mount source path '.../colima/.../docker.sock': operation not supported` while starting `testcontainers/ryuk` (`ContainerLaunchException`) | Colima's socket mount does not support the Ryuk mount operation used by Testcontainers. | Export `TESTCONTAINERS_RYUK_DISABLED=true` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, reload the shell, and rerun `mvn clean verify`. |
| Maven reports Java 26 is outside the project's supported range | The repository pins Java 25 as the supported version. | Select JDK 25, verify `java -version` and `mvn -version`, then rerun the build. |
| Testcontainers cannot reach Docker | Colima is stopped or the Docker CLI is using another context/socket. | Start Colima, select its Docker context if needed, verify `docker info`, and rerun Maven. |

## HTTP smoke checks with Postman/Newman

Start the local application and wait for `GET /actuator/health/readiness` to return `200`. The collection registers a unique user for each run and asserts the ordered `/api/v1` smoke responses.

`SecurityWebTest` also checks that an authenticated payment-list request succeeds with only the bearer header and that `/v3/api-docs` does not expose `CurrentUser` as an input parameter or body.

Install the Newman CLI separately, then run the collection:

```bash
newman run src/test/postman/payments-v1.postman_collection.json \
  --env-var baseUrl=http://127.0.0.1:8080
```

The collection covers all business endpoints with ordered folders, collection variables, inherited bearer authentication, refresh rotation, status and payload assertions, and bounded notification polling. Fraud review is optional and skipped by default because it needs a separate reviewer and two held payments. See [Postman workflow](postman.md) for setup, persistence, expected errors, and Newman usage. `scripts/validate-api.sh` provides the complementary HTTP smoke.

## k6 payment-list test

The scripts seed rows and a test account, write temporary credentials to `/tmp/payments-k6.env`, and launch the pinned k6 container. Run from the repository root with Docker Compose and the application available:

```bash
./perf/seed-payments.sh
./perf/run-k6.sh
```

The report includes separate `early_pages` and `deep_pages` measurements. Read `p(95)` for each scenario: it is the latency at or below which 95% of that scenario's measured requests completed. The script's thresholds are local regression checks, not a general production latency guarantee. Scenario and seed details are in [perf/README.md](../perf/README.md).

## Implementation references

- Maven test selection: `pom.xml` Surefire and Failsafe plugin configuration.
- Shared containers: `src/test/java/com/muse/payments/SharedContainers.java` and `TestcontainersBase.java`.
- Testcontainers dependency version: `pom.xml`.
- API smoke: `scripts/validate-api.sh` and `src/test/postman/payments-v1.postman_collection.json`.
- Documentation contract: `scripts/validate-docs.sh` and `docs/llms.txt`.
- k6 workflow: `perf/seed-payments.sh`, `perf/run-k6.sh`, and `perf/list-payments.js`.

## Eval

```bash
rtk mvn -q -Dtest=ArchitectureTest,ListNotificationsQueryHandlerTest test
rtk proxy bash scripts/validate-docs.sh
```

Expected result: focused architecture/application tests pass and the documentation audit reports zero missing front-matter or broken links.
