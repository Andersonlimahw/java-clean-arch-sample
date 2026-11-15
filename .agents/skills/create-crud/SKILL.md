---
name: create-crud
description: Scaffold a complete owner-scoped CRUD resource as a new Spring Modulith module (pure domain aggregate, CQRS handlers, JPA adapter, REST controller, Flyway migration, transactional outbox events with RabbitMQ routing, unit and integration tests, Postman requests), then adapt fields and docs. Use when asked to create a CRUD, a new resource, or a new module, for example "create-crud products".
argument-hint: "<resource-plural-kebab> [--singular <name>]"
---

# create-crud

## Input

```text
$ARGUMENTS
```

If the block above still shows the literal placeholder, take the arguments from the user's request.

| Argument | Required | Meaning |
|---|---|---|
| `<resource>` | yes | Plural, kebab-case resource name: `products`, `order-items`. It becomes the module package (`products`, `orderitems`), the route (`/api/v1/products`), and the table (`products`, `order_items`). |
| `--singular <name>` | no | Kebab-case singular when it cannot be derived (`people` → `--singular person`). |

The field list comes from the request. Without one, keep the starter fields (`name`, `description`) and say so in the report.

## What the scaffold produces

`scripts/scaffold.py` renders `templates/` into a new module and wires the four places the codebase keeps as explicit lists. Standard library only; it checks every precondition before the first write.

| Layer | Files (for `products`) |
|---|---|
| Domain, pure Java | `Product` aggregate (owner, `name`, `description`, timestamps, `version`), `ProductRepository` port, `ProductSort`, `ProductCreatedEvent`, `ProductUpdatedEvent`, `ProductDeletedEvent` |
| Application, CQRS | `Create/Update/DeleteProductCommand` + handlers, `GetProductByIdQuery` + handler, `ListProductsQuery` + handler, request and response records, `PageResponse`, `ProductSorts`, `ProductOutboxMapper` |
| Infrastructure | `ProductJpaEntity` (`@Version`), `ProductJpaRepository`, `ProductJpaAdapter`, `ProductMessagingConfig` (queue `products.events` bound to `product.*`) |
| Web | `ProductController`: `POST`, `GET /{id}`, `GET` (page), `PUT /{id}` (optimistic `version`), `DELETE /{id}` |
| Database | `V<next>__products.sql`: table without cross-module foreign keys, owner-scoped list index |
| Tests | `ProductTest`, `ProductHandlersTest` (unit), `ProductsIT` (HTTP, ownership, 409, outbox rows, relay publication) |
| Wiring edits | `ArchitectureTest` web-boundary list, `SecurityConfig` matcher (`USER` or `ADMIN`), `OutboxRelayService.resolveRoutingKey` cases, Postman folder and collection variables |

## Steps

1. **Prepare.** Work in a feature worktree as `AGENTS.md` requires, never on `main`. Run `rtk git status` and stop if unrelated changes are present.

2. **Scaffold.** Preview, then write:

   ```bash
   rtk proxy python3 -I .agents/skills/create-crud/scripts/scaffold.py <resource> --dry-run
   rtk proxy python3 -I .agents/skills/create-crud/scripts/scaffold.py <resource>
   ```

   Precondition failures write nothing. Filesystem failures during writing can leave a partial result: inspect the diff before retrying. If an anchor line changed shape, update the generator's anchor against the current file and preview again before generating; do not create only the wiring without the module. Required wiring: the module's domain package in `ArchitectureTest`, route matcher before `.anyRequest()` in `SecurityConfig`, and one `case "<Entity><Verb>" -> "<singular>.<verb>";` per event in `OutboxRelayService.resolveRoutingKey`.

3. **Prove the skeleton before changing it.**

   ```bash
   rtk mvn -B -Dtest='ArchitectureTest,ModularityTest,<Entity>Test,<Entity>HandlersTest' test
   rtk mvn -B -Dit.test=<Entities>IT test-compile failsafe:integration-test failsafe:verify
   ```

4. **Adapt the fields.** For each field you add, rename, or remove, touch every row. Run the unit tests after each field.

   | Where | Change |
   |---|---|
   | `domain/<Entity>.java` | Field, constructor, `create`, `rehydrate`, `update`, validation that throws `ValidationException`, getter. Add it to an event only when a consumer needs it. |
   | `application/` requests | Bean Validation on `Create<Entity>Request` and `Update<Entity>Request`. Keep the domain validation too: handlers are also called without HTTP. |
   | `application/` commands, handlers, `<Entity>Response` | Pass the value through. Responses stay records in the application layer. |
   | `infrastructure/<Entity>JpaEntity.java` | `@Column`, constructor, `fromDomain`, `toDomain`. Tests run with `ddl-auto: validate`, so the entity and the migration must agree. |
   | `db/migration/V<n>__<table>.sql` | Column, constraint, index. Edit this file only while it is unmerged; once it reached a shared database, add a new migration with the `flyway-migration` skill. |
   | Tests | `<Entity>Test`, the `InMemoryRepository` in `<Entity>HandlersTest`, and the `<Entities>IT` assertions. |
   | Postman folder | Request bodies and the JSON schema in each test script. |

   Rules that hold for every field:

   - The domain imports only `java..` and `com.muse.payments..domain..`. `payments.domain.Money` belongs to the payments module and is not reachable; model an amount as `BigDecimal` (`NUMERIC(19,2)`) plus a 3-letter currency inside this module.
   - A value that references another module (a user, a payment) is a UUID column with an index and no foreign key.
   - A uniqueness rule is a unique index. `DataIntegrityViolationException` already maps to `409 Data Conflict`; add an integration test that proves the duplicate is rejected.

5. **Decide the variations the request needs.** Say which one you applied.

   | Need | What to change |
   |---|---|
   | Roles other than owner-scoped `USER`/`ADMIN` | Reorder or replace the matcher in `SecurityConfig` (first match wins) and the ownership filter in the handlers. Keep "another user's row is 404". There is no public endpoint that grants roles; tests mint them the way `IdempotencyAndAccessIT` does. |
   | Retry-safe create | Follow `CreatePaymentUseCase`: require `Idempotency-Key`, call `IdempotencyPort.acquireProcessing`, store the response with `saveCompleted`, release on failure. |
   | A consumer for the events | Use the `add-integration-event` skill. Give the consumer its own queue: `<resource>.events` drops messages after 24 hours. |
   | No delete, or soft delete | Remove the route, command, and event, or replace `delete` with a status column. Update the tests and the Postman folder in the same change. |

6. **Update the docs.** Use the `docs-sync` skill. A new resource changes `docs/api.md` (endpoint table), `README.md` (authorization table), `docs/data.md` (migration and index tables), `docs/messaging.md` (routing keys and queue), `docs/postman.md` (folder table and variables), `docs/architecture.md` (module map), and `docs/llms.txt` if a page was added.

7. **Run the gate.** Use the `local-gate` skill. Then run the Postman folder against a running stack with the `run-api-requests` skill.

## Rules the generated code follows

Do not undo these while adapting it.

- Command handlers are `@Transactional` and append outbox events in the same transaction as the aggregate change. `OutboxPort.append` uses `Propagation.MANDATORY` and fails without one.
- Keep slow CPU work (including password hashing) and external calls outside these transactions; follow `OffloadedPasswordEncoder` for CPU offloading and preserve batched relay claim/lease semantics.
- Every outbox event type has a routing key and a bound queue. The relay publishes with mandatory routing: an unroutable event is returned by the broker and retried without limit. `<Entities>IT` fails when this wiring is missing.
- Controllers depend on the application layer and `shared` only. `CurrentUser` comes from the bearer token and stays `@Parameter(hidden = true)`.
- Errors are domain exceptions translated by `GlobalExceptionHandler` to RFC 7807 `application/problem+json`. Controllers do not catch them.
- Updates carry the `version` last read. A stale value is `409`; `@Version` on the JPA entity covers the race between two requests that read the same version.
- Constructor injection only, no Lombok, records for commands, queries, requests, responses, and events.

## Flyway version collisions

The scaffold takes the next free version in your checkout. Another branch can take the same number. After merging `main` into your branch, if two files share a version, rename yours to the next free number before the gate. Do that only while your migration has not been applied to a shared database.

## Done when

- The scaffold ran once and `rtk git status` shows the new module, the migration, three wiring edits, and the Postman change.
- Fields match the request in every row of the step 4 table.
- `rtk proxy bash scripts/ci-local.sh` passes, and you report the unit and integration totals from its output.
- The Postman folder ran green with Newman against a local stack, or you state that it was not run and why.
- The docs in step 6 describe the new routes, table, and events.
