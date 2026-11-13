# Testing guide

## Test pyramid

1. **Unit tests** use JUnit and focused fakes/mocks for domain rules, application handlers, cache behavior, retry/backoff helpers, and web error mapping. Maven Surefire selects `*Test.java` and excludes `*IT.java`.
2. **Integration tests** use Spring and Testcontainers for PostgreSQL 16, RabbitMQ 3.13, and Redis 7. Maven Failsafe selects `*IT.java`. The integration suite covers HTTP/security, migrations, event delivery, idempotency, cache, fraud screening, concurrency, and observability.
3. **HTTP smoke and performance checks** exercise a running local stack through the Postman/Newman collection, `scripts/validate-api.sh`, and the k6 scenario in `perf/`.

The repository has no Maven Wrapper. Install Maven and invoke `mvn` from the project root.

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

Install the Newman CLI separately, then run the collection:

```bash
newman run src/test/postman/payments-v1.postman_collection.json \
  --env-var baseUrl=http://127.0.0.1:8080
```

The collection checks response statuses and the missing-token path. Notification delivery is eventual; this collection checks the notification-list status but does not wait for the created payment notification. `scripts/validate-api.sh` polls until that notification appears.

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
- k6 workflow: `perf/seed-payments.sh`, `perf/run-k6.sh`, and `perf/list-payments.js`.
