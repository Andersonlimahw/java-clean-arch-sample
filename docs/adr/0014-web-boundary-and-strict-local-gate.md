# ADR 0014 — Application boundary for notifications and strict local validation

Date: 2026-11-13
Status: Accepted

## Context

The notifications controller read the domain repository directly and serialized a domain object. The authentication controller also depended on a concrete infrastructure rate limiter. Separately, `scripts/ci-local.sh` treated missing Docker/kustomize validation as a warning and still printed a passing gate.

## Decision

- Web adapters depend on application handlers and ports, not concrete infrastructure adapters or business-domain objects.
- `notifications` exposes a read query handler and an application response record; the domain remains internal to the module.
- `AuthRateLimitPort` is the application seam; the Redis/in-memory implementation remains infrastructure.
- The local CI gate fails when required infrastructure validation cannot run. It uses a temporary directory with cleanup for rendered manifests.
- Exception advice may depend on domain exceptions because it is the translation boundary from domain failures to RFC 7807 responses.

## Consequences

- Application seams are unit-testable without Spring MVC or JPA.
- ArchUnit prevents the direct web-to-infrastructure/domain regression while allowing exception translation.
- Developers must run Docker, Compose, kustomize, and kubeconform for a passing local gate; missing tools are an explicit limitation, not success.

## Verification

- `rtk mvn -q -Dtest=ArchitectureTest,ListNotificationsQueryHandlerTest test`
- `rtk proxy bash scripts/validate-docs.sh`
- `rtk proxy bash scripts/ci-local.sh`
