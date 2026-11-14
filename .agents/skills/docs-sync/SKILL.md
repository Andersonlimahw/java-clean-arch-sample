---
name: docs-sync
description: Reconcile documentation and machine-readable index with verified code, routes, migrations, events and local test evidence.
argument-hint: "<changed-feature>"
---

# docs-sync

1. Read AGENTS.md, docs/README.md and docs/llms.txt. Use the request and diff to identify changed behavior; document only verified facts.
2. API changes affect docs/api.md, docs/postman.md, collection requests and README authorization tables. Data changes affect docs/data.md. Module/event changes affect docs/architecture.md and docs/messaging.md. Coordinate ownership before editing a teammate's files.
3. Non-ADR docs retain title/status/updated/related YAML front-matter. Operational/feature guidance ends in an Eval section with runnable checks and expected outcomes. ADRs use the next free number and are indexed in docs/adr/README.md.
4. Add new pages to docs/README.md and docs/llms.txt. Regenerate the flat index entries from the current document inventory; preserve existing summaries that remain accurate.
5. Run `rtk proxy bash scripts/validate-docs.sh` and `rtk git diff --check`. Verify new AGENTS/README/skill links and harness symlinks via `rtk proxy python3 -I scripts/validate-ai-tooling.py`.

Done: docs describe current behavior; evidence distinguishes local tests, live smokes, remote checks, merge and deployment.
