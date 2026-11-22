# ADR 0003 — Modular monolith instead of microservices

- **Status:** Accepted
- **Date:** 2026-11-12
- **Supersedes:** the structural decision in the retired ADR 0001 for the payments codebase (ADR 0002 already retired the trip domain)

## Context

The platform is one product with three business modules that share a transaction boundary: a payment and its outbox row must commit together. The team is one deployable, one database, and one on-call surface. Splitting identity, payments, and notifications into services would add network failures, distributed transactions, and independent schema deploys before there is a scaling hotspot that requires them.

[ADR 0002](0002-payments-pivot.md) retired the trip-planner domain. This ADR supersedes the structural decision in the retired ADR 0001 (see the [ADR index](README.md#retired-records)): one deployable and one owner per module remain, and cross-module writes go through the outbox instead of another module's service API.

## Decision

Ship one Spring Boot application. Enforce module boundaries in-process with Spring Modulith and ArchUnit:

- A module owns its tables, aggregates, handlers, adapters, and HTTP types.
- Cross-module writes go through integration events in the outbox, not through another module's repository.
- `notifications` does not reference the payments domain model.
- No foreign key across modules.
- `shared` stays an open kernel of ports and cross-cutting types, not a place for payment rules.

## Consequences

- One pipeline, one schema history, local ACID for the outbox.
- A bad deploy takes every module down together. Mitigate with readiness probes, a migration Job, and rolling updates.
- Extracting a service later is possible along the event boundary (the outbox payload is already the contract) and is not justified yet.
- In-process calls are not a substitute for the event path. Payments must not import the notifications adapter.

## Alternatives

| Option | Why not now |
|---|---|
| Microservices per module | Distributed transactions or a dual-write for the outbox. Operational cost without a measured bottleneck. |
| Several databases in one process | Loses the single commit that makes the outbox correct. |
| Classic layered packages (`controller`, `service`, `repository` at the root) | That is the structure the retired ADR 0001 rejected: no owner per aggregate, easy cross-reads. |
