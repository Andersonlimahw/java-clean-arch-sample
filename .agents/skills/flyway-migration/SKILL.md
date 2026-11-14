---
name: flyway-migration
description: Add a versioned PostgreSQL Flyway migration for a named schema change, with module boundaries, indexes and integration verification.
argument-hint: "<change-description>"
---

# flyway-migration

Read docs/data.md, existing migrations and the affected JPA mapping. Take the change description from the request.

1. List src/main/resources/db/migration/V*__*.sql. Select the next unused integer version and a descriptive snake_case name. Recheck after merging main: concurrent branches can select the same number.
2. Never modify an applied/shared migration. Add a forward migration; do not run repair, clean or production migrations. Reversible SQL and backup requirements must be described before any authorized shared database change.
3. Keep domain models pure. Map the SQL types to the JPA adapter, timestamps, optimistic version and nullability. Reference another module only by UUID plus index; no cross-module foreign keys.
4. Index the actual owner/filter/sort query. Follow V6's separate configuration for CREATE INDEX CONCURRENTLY; do not put it inside a transaction. Verify constraints with negative integration cases.
5. Run Testcontainers integration tests on a fresh PostgreSQL 16 database, then local-gate. Update docs/data.md and affected API docs with docs-sync.

Done: migrations apply in order, Hibernate validates the schema, query/constraint tests pass and full local gate is green. A migration file alone is not proof of application.
