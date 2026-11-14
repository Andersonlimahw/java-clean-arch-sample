---
title: AI tooling workflow
status: active
updated: 2026-11-14
related: [../AGENTS.md, testing.md, postman.md]
---

# AI tooling workflow

Canonical skill bodies live in `.agents/skills/`; Claude discovers relative symlinks under `.claude/skills/`. Codex reads `.agents/skills/` directly. Gemini commands are small pointers to the same bodies with arguments. `CLAUDE.md` and `GEMINI.md` point to `AGENTS.md`. Other harnesses read AGENTS.md and the selected canonical SKILL.md. Existing Spec Kit artifacts stay unchanged.

| Request | Canonical entry |
|---|---|
| `create-crud products` | [create-crud](../.agents/skills/create-crud/SKILL.md): owner-scoped starter fields name/description; adapt requested fields. |
| `test-resource products` | [test-resource](../.agents/skills/test-resource/SKILL.md): domain, handlers, architecture and HTTP tests. |
| `local-gate` | [local-gate](../.agents/skills/local-gate/SKILL.md): strict local CI, docs and alias checks. |
| `run-api-requests` | [run-api-requests](../.agents/skills/run-api-requests/SKILL.md): ordered local Newman execution. |
| `flyway-migration add resource index` | [flyway-migration](../.agents/skills/flyway-migration/SKILL.md): next version, constraints and fresh database proof. |
| `add-integration-event products ProductCreated` | [add-integration-event](../.agents/skills/add-integration-event/SKILL.md): outbox routing, queue and consumer. |
| `docs-sync products` | [docs-sync](../.agents/skills/docs-sync/SKILL.md): code/document reconciliation. |

Read-only architecture review uses [the canonical reviewer](../.agents/reviewers/architecture.md). Claude discovers its alias under `.claude/agents/`; other harnesses read the canonical file as a review prompt. This repository role is not a hosted bot or authorization to merge.

## CRUD proof and boundaries

Preview with `rtk proxy python3 -I .agents/skills/create-crud/scripts/scaffold.py products --dry-run`, then run without `--dry-run` only in a feature worktree. The generator checks anchors/collisions before writing. It adds domain/application/infrastructure/web code, SQL, tests, security/routing entries and Postman requests. Narrative docs and arbitrary business fields need the adaptation steps in the skill.

Prove tooling changes by generating products in a disposable worktree from current main using `--root`. Run full CI there, inspect the generated diff, retain evidence outside the checkout, then remove the disposable example/worktree. Never commit it into the tooling branch. Main application code and configuration are outside this delivery scope.

## Eval

```bash
rtk proxy python3 -I scripts/validate-ai-tooling.py
rtk proxy bash scripts/validate-docs.sh
rtk proxy bash scripts/ci-local.sh
```

Expected: aliases resolve, scaffold preview succeeds without writes, docs have no broken links, and infrastructure/Maven checks pass. Automatic discovery in live Claude/Codex/Gemini sessions needs separate runtime evidence; filesystem validation proves layout only.
