---
title: Resilience
status: active
updated: 2026-11-13
related: [architecture.md, observability.md, testing.md]
---

# Fraud-screening resilience

Payment creation calls the fraud provider through `FraudScreeningPort`. The production adapter is `HttpFraudScreeningAdapter`; resilience belongs to this infrastructure adapter, leaving the payment domain independent of Resilience4j and HTTP.

## Call policy

The decorator order is `Retry(CircuitBreaker(Bulkhead(TimeLimiter(HTTP call))))`. Each retry is a separate circuit-breaker call. The asynchronous request runs on a virtual-thread-per-task executor. The bulkhead does not wait for a permit, and the time limiter cancels the submitted future on timeout.

| Profile | Retry attempts (including first call) | Initial wait | Circuit breaker window / minimum calls | Failure threshold / open wait / half-open calls | Bulkhead permits | Time limit |
|---|---:|---:|---:|---|---:|---:|
| Default | 3 | 100 ms | 12 / 12 | 50% / 20 s / 2 | 25 | 800 ms |
| `local` | 4 | 250 ms | 16 / 16 | 60% / 10 s / inherits 2 | 8 | 2 s |
| `prod` | 2 | 200 ms | 20 / 20 | 40% / 30 s / 3 | 50 | 400 ms |

Retry waits use exponential backoff with multiplier `2` and randomized wait factor `0.5`. The retryable failures are timeouts, I/O/resource-access failures, and the adapter's transient provider exception. HTTP `408`, `429`, and `5xx` responses become transient failures. An explicit decline is not retried. Other provider `4xx` responses are counted as unexpected client errors and do not open the circuit as failures.

The adapter has safe fallbacks:

- A valid `CLEAR` response proceeds with normal payment creation.
- An explicit `DECLINED` decision returns a declined outcome; the API responds `422` without exposing the provider's reason.
- Exhausted transient retries, an open circuit, a full bulkhead, an unusable response, interruption, or another unexpected adapter failure holds the payment as `UNDER_REVIEW`. The fallback never approves a payment.

An `UNDER_REVIEW` payment cannot be confirmed. A caller with `ADMIN` or `FRAUD_REVIEWER` may review it only when they are not its owner. `APPROVE` moves it to `PENDING`; `REJECT` cancels it. An administrator who created the payment cannot review their own payment.

## Provider configuration

Local Compose starts WireMock as the fraud provider and returns `CLEAR`. Set `FRAUD_SCREENING_BASE_URL` when the provider is elsewhere. The `prod` profile requires that variable and refuses to start unless the URL uses `https`; it has no default provider URL.

Startup validates related time budgets: connect timeout cannot exceed the time limiter, read timeout must exceed it so a cancelled attempt can release, the circuit breaker's minimum call count must be at least retry max attempts, and the idempotency processing lease must exceed the worst-case screening budget.

## Implementation references

- Port and result types: `src/main/java/com/muse/payments/payments/application/FraudScreeningPort.java`, `FraudScreeningOutcome.java`, and `FraudScreeningResult.java`.
- Resilience decorators and fallback: `src/main/java/com/muse/payments/payments/infrastructure/fraud/HttpFraudScreeningAdapter.java`.
- Profile properties and startup invariants: `src/main/java/com/muse/payments/payments/infrastructure/fraud/FraudScreeningConfiguration.java`, `FraudScreeningProperties.java`, and `src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`.
- Review authorization and state changes: `src/main/java/com/muse/payments/payments/application/ReviewPaymentCommandHandler.java` and `src/main/java/com/muse/payments/payments/web/PaymentController.java`.
- Failure policy decision: [ADR 0012](adr/0012-fraud-screening-failure-policy.md).

## Eval

```bash
rtk mvn -q -Dtest='*FraudScreening*Test' test
```

Expected result: provider failure falls back to `UNDER_REVIEW` and resilience budgets remain bounded.
