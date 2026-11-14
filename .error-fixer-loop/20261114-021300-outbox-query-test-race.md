# Fix prompt — outbox query test race

## Role / Context

Maintain deterministic integration tests in the Payments Platform. Preserve production outbox publication behavior.

## Task

Fix `PaymentsApplicationIT.findPendingForPublishingQueriesCorrectly` without disabling the relay in production or weakening its pending-row assertion.

## Inputs / files

- Command: `rtk proxy env TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TMPDIR=/Volumes/lemon-ssd/projects-ssd/tmp/java-payments-git bash scripts/ci-local.sh`.
- Working directory: repository worktree `.worktrees/refactor-expert`.
- Exit: 1; Failsafe reported 44 tests, 1 failure, 0 errors.
- Failure: `PaymentsApplicationIT.findPendingForPublishingQueriesCorrectly:63` — `Expecting actual not to be empty`.
- Evidence: the test committed its synthetic PaymentCreated fixture; its Rabbit listener log showed that event delivered before the pending-row assertion.

## Root cause and class

The query test fails because a live relay can publish the committed fixture between insertion and selection. Class: `missing-test` (missing isolation in an existing integration test).

## Constraints

Keep real PostgreSQL and the existing assertion. Do not add sleeps, retries, or disable production publication to mask the race.

## Plan

1. Annotate only the pending-query test with `@Transactional` and flush its insert before the native query. The row stays uncommitted until the test rolls back. → verify: the focused Failsafe class passes.
2. Run the complete strict local gate with Docker/Testcontainers. → verify: all unit/integration tests and Kubernetes checks pass.
3. Persist the isolation rule in `docs/rules/refactor-and-validation.md`, already indexed by AGENTS.md. → verify: documentation validation and diff checks pass.

## Output contract

Report actual verification outcomes. Commit the regression fix with the boundary hardening; do not claim remote publication until GitHub confirms the ref.

## Feedback DO

Use transaction visibility to protect a pending-row fixture from an independent publisher.

## Feedback DO NOT

Do not accept an empty result, widen status expectations, change production scheduling, or replace the native query with a mock.

```text
EXEC-MAP v1
intent: fix-bug
executor: codex
effort: low
time: 5-10 min
tokens: 2k-5k
skills: [error-fixer-loop]
models: {impl: balanced}
agents: inline
mcp: []
router: heuristic
router_mode: heuristic
router_confidence: unavailable
router_fallback: static-catalog
```
