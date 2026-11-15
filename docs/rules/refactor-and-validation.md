---
title: Refactor And Validation Rules
status: active
updated: 2026-11-15
related: [../architecture.md, ../testing.md, ../../AGENTS.md]
---

# Refactor and validation rules

## Rules

- **Incremental Java compile**: Compile each new Java type family with the repository's Java 25 before building dependent features. Verify with: `rtk proxy mvn -B -DskipTests compile`.
- **JSONB test assertions**: Compare PostgreSQL JSONB values structurally, not by serialized whitespace. Verify by parsing returned JSON before asserting its fields.
- **Web boundary**: Controllers depend on application/shared contracts; only `@RestControllerAdvice` may translate domain exceptions. Verify with `rtk mvn -q -Dtest=ArchitectureTest test`.
- **Outbox query isolation**: Keep pending-row query fixtures uncommitted in a rollback-only test transaction and flush before the native query so a background relay cannot consume them. Verify with `rtk mvn -B -Dit.test=PaymentsApplicationIT test-compile failsafe:integration-test failsafe:verify`.
- **Strict local gate**: `scripts/ci-local.sh` must fail when Docker Compose, Docker, kustomize, or kubeconform validation is unavailable; skipped infrastructure is not a passing gate. Verify with `rtk proxy bash scripts/ci-local.sh`.
- **LLM wiki metadata**: Every non-ADR Markdown file under `docs/` starts with `title`, `status`, `updated`, and `related`, and `docs/llms.txt` is regenerated after doc changes. Verify with `rtk proxy bash scripts/validate-docs.sh`.
- **Documentation evaluation**: Feature and operational docs end with an `## Eval` section containing a runnable check and an expected result.

## Refactor checklist

- Preserve domain purity and constructor injection.
- Add a regression test for every corrected behavior or boundary.
- Keep API response records in the application layer; do not expose domain objects from web adapters.
- Update the architecture narrative, ADR index, `AGENTS.md`, and `docs/llms.txt` when a module boundary or validation gate changes.

## Eval

```bash
rtk mvn -q -Dtest=ArchitectureTest,ListNotificationsQueryHandlerTest test
rtk proxy bash scripts/validate-docs.sh
```

Expected result: focused tests pass and the audit reports no missing front-matter or broken Markdown links.
