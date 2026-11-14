---
name: test-resource
description: Add and execute domain, application, architecture and HTTP integration tests for a named resource or corrected behavior.
argument-hint: "<resource-or-behavior>"
---

# test-resource

Use the resource/behavior named in the request. Read docs/testing.md and the closest existing tests before editing.

1. Domain: test invariant violations and valid transitions without Spring; application: in-memory repository or mocked ports, ownership, not found, conflict, and event emission.
2. Web integration: extend TestcontainersBase, exercise bearer RS256, CurrentUser, expected roles, another owner's 404, RFC 7807, validation, pagination and optimistic conflict. Use real PostgreSQL migrations (`ddl-auto: validate`), Redis and RabbitMQ.
3. Verify aggregate change and outbox append are atomic; event routing and a bound queue must actually publish. Keep pending-row query fixtures in a rollback-only transaction, flush before native queries, avoiding scheduled relay races.
   Authentication fixtures must use a module-specific remote address so shared Redis rate-limit buckets cannot exhaust another integration test's login allowance; never disable rate limiting to hide this failure.
4. Run focused tests via the local-gate skill, including ArchitectureTest and ModularityTest when boundaries change. Then run the full gate. Read XML totals rather than inferring success from compilation.
5. Update the Postman contract using run-api-requests and docs using docs-sync. Do not invent coverage or claim mocks prove live broker delivery.
