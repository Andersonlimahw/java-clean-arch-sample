---
title: Refactor local gate evidence
status: verified
updated: 2026-11-14
related: [../testing.md, ../rules/refactor-and-validation.md, postman-validation.md]
---

# Refactor local gate evidence

Validated on 2026-11-14 in `.worktrees/refactor-expert`, branch `refactor/expert-hardening`, based on `7095453` plus the boundary, local-gate, documentation, and Postman changes.

## Commands and results

```bash
rtk proxy env TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock bash scripts/ci-local.sh
rtk proxy bash scripts/validate-docs.sh
rtk git diff --check
```

The actual gate invocation also set `TMPDIR` to the agent's temporary directory under the SSD. Java 25.0.1 and Maven 3.9.11 were used. Kustomize 5.8.3 was installed on the host to satisfy the previously missing gate dependency. Docker Compose configuration was checked using the standalone `docker-compose` binary.

| Check | Result |
|---|---|
| Shell script syntax | Passed |
| Docker Compose configuration | Passed |
| Staging Kubernetes overlay, kubeconform strict 1.30.0 | 9 valid, 0 invalid, 0 errors, 0 skipped |
| Production Kubernetes overlay, kubeconform strict 1.30.0 | 9 valid, 0 invalid, 0 errors, 0 skipped |
| Maven Surefire unit tests | 110 tests, 0 failures, 0 errors, 0 skipped |
| Maven Failsafe integration tests | 44 tests, 0 failures, 0 errors, 0 skipped |
| Documentation validator | 0 missing front-matter entries, 0 broken links |
| Whitespace/conflict-marker diff check | Passed |

Final script output: `BUILD SUCCESS` and `ALL LOCAL CI CHECKS PASSED`. The integration suite used real Testcontainers dependencies. The [Postman evidence](postman-validation.md) records the separately executed 30 examples and 105 assertions; those are not added to the Maven test totals.

## Failure found and corrected

The initial full gate failed in `PaymentsApplicationIT.findPendingForPublishingQueriesCorrectly`: `Expecting actual not to be empty`. The test committed its outbox fixture before querying pending events, allowing the relay to publish it in between; its listener log recorded the synthetic event being delivered during the failing test.

Only the pending-query test now runs in a Spring test transaction, with an explicit flush before the native query. Other transactions cannot publish the uncommitted fixture, and the test rolls back afterward. The pending-row assertion remains unchanged. Production publication, transaction propagation, scheduling, and query SQL were not changed by this fix.

Focused verification passed before the final full gate:

```bash
rtk proxy env TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -B -Dit.test=PaymentsApplicationIT test-compile failsafe:integration-test failsafe:verify
```

Result: 3 tests, 0 failures, 0 errors, 0 skipped. The prevention rule is in [refactor and validation rules](../rules/refactor-and-validation.md).

## Eval

Re-run the strict local gate with Docker available, then documentation validation and the diff check. Require all checks to pass; unavailable tools or skipped infrastructure checks are failures, not passing evidence. No remote Actions result is claimed by this report.
