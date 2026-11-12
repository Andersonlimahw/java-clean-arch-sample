# ADR 0012 — Fraud screening never approves on failure

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Creating a payment calls an external fraud provider. The provider can be slow, down, or return a client error that is not a business decision. Treating any of those as `CLEAR` would approve a payment the provider did not clear. Holding the database transaction open for the call would pin a Hikari connection and the idempotency lease row for the whole timeout and retry budget.

## Decision

`FraudScreeningPort` is an application port. Resilience4j and HTTP stay in `HttpFraudScreeningAdapter`.

- The idempotency lease commits in its own transaction before the call. The call runs with no transaction. The payment, the outbox row, and the stored HTTP response commit together afterwards.
- `CLEAR` stores `PENDING` and emits `PaymentCreated`.
- An explicit decline is HTTP 200 with outcome `DECLINED`, or HTTP 422 whose body is that same decision. The API returns 422 `Payment was declined by fraud screening.` The provider reason is logged only. The idempotency key stores that 422 and replays it.
- Timeout, I/O, HTTP 5xx, HTTP 408, and HTTP 429 are retried. Retry is outside the circuit breaker, so each attempt counts as one circuit-breaker call and `minimum-number-of-calls` is at least `max-attempts`. An open circuit is not retried. When the budget is exhausted, or the circuit is open, the payment is stored as `UNDER_REVIEW` with the screening reason. It is not approved. The outbox event is `PaymentHeldForReview`, not `PaymentCreated`.
- Any other 4xx is not a decline and is not retried. The payment is `UNDER_REVIEW`, the event is logged at error, and `payments.fraud.screening.unexpected_client_errors` increments.
- `UNDER_REVIEW` cannot be confirmed. The owner may cancel it. `POST /api/v1/payments/{id}/review?decision=APPROVE` moves it to `PENDING` and then emits `PaymentCreated`. `decision=REJECT` cancels it.
- The screening request sends the payment id as `Idempotency-Key` and in the body, and that id does not change between retries.
- Profile `prod` requires `FRAUD_SCREENING_BASE_URL` and `https`. Local Compose runs a WireMock that answers `CLEAR`.

## Consequences

- A provider outage creates held payments instead of failing the request closed. Operators release or reject them. Nothing in that path confirms the payment.
- Each retry is visible to the circuit breaker. A short window can open during a burst of retries. The configured minimum number of calls avoids opening on a single screening.
- The provider must treat the payment id as an idempotency key. A provider that does not will run the screening once per attempt.

## Alternatives

| Option | Why not |
|---|---|
| Approve when the provider is down | Silent approval. |
| One transaction around the HTTP call | Pins a pooled connection and blocks other requests on the same idempotency key. |
| Treat every 4xx as a decline | 401, 404, and 429 would decline every payment and would not open the circuit. |
