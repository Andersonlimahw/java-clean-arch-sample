---
title: AI tooling local validation
status: active
updated: 2026-11-14
related: [../ai-tooling.md, ../testing.md]
---

# AI tooling local validation

## Disposable CRUD proof

Generated `products` from main `5670fa4` in a detached disposable worktree using `.agents/skills/create-crud/scripts/scaffold.py products --root <worktree>`. The final generated 32 files match the checked-in templates; wiring adds the owner/role matcher, architecture boundary, three routing keys and nine Postman requests. No generated application code belongs to this tooling PR.

The first full run exposed HTTP 429 in AuthHashingOutsideTransactionIT: generated authentication fixtures consumed the default client's shared Redis bucket. The template now gives authentication requests a module-specific remote address, retaining the real rate limiter. Repeating the full gate passed: **132 unit tests, 46 integration tests, zero failures/errors/skips**. ProductsIT exercises authentication, validation, ownership, optimistic conflict, CRUD and publication of all three outbox events through RabbitMQ.

Command: `rtk proxy env TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock bash scripts/ci-local.sh`. It also validated shell syntax, base/debug Compose configuration and Kubernetes staging/production: **9 valid resources each, zero invalid/errors/skipped**. Docker/Testcontainers used PostgreSQL 16 and RabbitMQ 3.13, plus Redis.

## Tooling checks

The tooling branch on main `5670fa4` passed the same full local CI command: **120 unit tests, 45 integration tests, zero failures/errors/skips**, plus the two Kubernetes overlays (9 valid resources each). Application source, pom.xml and application configuration are unchanged by this delivery.

Canonical skill/alias/reviewer validation and scaffold preview passed. All 17 Gemini TOML commands (10 existing plus 7 new) parsed with Python 3.13 tomllib. Invalid traversal/resource name, reserved module and uninferrable singular were rejected with existing files unchanged. Documentation validation found zero missing front-matter and zero broken links.

These are filesystem and local execution proofs. Live harness discovery in provider sessions and Newman execution of the generated Postman folder were not performed. HTTP and broker behavior were exercised by ProductsIT. No GitHub Actions result is claimed.

## Eval

```bash
rtk proxy python3 -I scripts/validate-ai-tooling.py
rtk proxy bash scripts/validate-docs.sh
rtk git diff --check
```

Expected: zero exit codes. For a new scaffold revision, repeat generation and the complete local CI in a fresh disposable worktree before replacing the counts above.
