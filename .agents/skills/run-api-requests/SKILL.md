---
name: run-api-requests
description: Execute or update the repository Postman collection with Newman against a local test stack, preserving ordered authentication and exact contract assertions.
argument-hint: "[baseUrl] [folder-name]"
---

# run-api-requests

Read docs/postman.md. Default baseUrl is http://127.0.0.1:8080. Only local or explicitly authorized test environments: the collection registers users and creates persistent payments. Never point it at production.

1. Start the stack using README.md with PostgreSQL, Redis, RabbitMQ and fraud WireMock CLEAR. Wait for readiness 200/UP. Keep credentials private.
2. Full run: `rtk proxy newman run src/test/postman/payments-v1.postman_collection.json --env-var baseUrl=http://127.0.0.1:8080 --delay-request 250 --timeout-request 10000 --timeout-script 5000`. Replace baseUrl only with the requested local/test endpoint. An explicit environment baseUrl is only a runner override; do not override collection authentication/state variables.
3. To select a folder, include the exact account/authentication folder first and the resource folder next via repeated `--folder` options. Run them in one process; collection token/ID state does not persist across Newman runs. Logout runs last.
4. When changing a route, update requests and exact status, Content-Type, schema and ownership assertions, including RFC 7807 errors. State uses pm.collectionVariables. Never export tokens into tracked files or include response bodies in public evidence.
5. Report requests/assertions/failures, folders selected, optional fraud-review skips and baseUrl. A skipped folder is untested. Newman export-collection does not reliably persist changed script variables; see docs/postman.md.

Done: zero unexpected assertions for the requested flow, plus docs-sync and local-gate for contract edits.
