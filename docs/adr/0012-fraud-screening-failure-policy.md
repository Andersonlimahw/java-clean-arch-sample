# ADR 0012 — Fraud screening never approves on failure

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Creating a payment calls an external fraud provider. The provider can be slow, down, or return a client error that is not a business decision. Treating any of those as `CLEAR` would approve a payment the provider did not clear. Holding the database transaction open for the call would pin a Hikari connection and the idempotency lease row for the whole timeout and retry budget.

## Decision

`FraudScreeningPort` is an application port. Resilience4j and HTTP stay in `HttpFraudScreeningAdapter`.

- The idempotency lease commits in its own transaction before the call. The call runs with no transaction. The payment, the outbox row, and the stored HTTP response commit together afterwards. The `PROCESSING` row expires after `app.idempotency.processing-ttl` (default 2 minutes), which is longer than the screening and persist budget. Completion sets `expires_at` to 24 hours. An expired `PROCESSING` row is reclaimed by the next request with the same key, so a crash between screening and persist does not lock the key for a day.
- `CLEAR` stores `PENDING` and emits `PaymentCreated`.
- An explicit decline is HTTP 200 with outcome `DECLINED`, or HTTP 422 whose body is that same decision. The API returns 422 `Payment was declined by fraud screening.` The provider reason is logged only. The idempotency key stores that 422 and replays it.
- Timeout, connection failures (`ResourceAccessException`), HTTP 5xx, HTTP 408, and HTTP 429 are retried. `IOException` does not reach Resilience4j because `RestClient` wraps it. Retry is outside the circuit breaker and the bulkhead, so each attempt is one circuit-breaker call and `minimum-number-of-calls` is at least `max-attempts`. An open circuit and a full bulkhead are not retried. When the budget is exhausted, the circuit is open, or the bulkhead is full, the payment is stored as `UNDER_REVIEW`. It is not approved. The outbox event is `PaymentHeldForReview`, not `PaymentCreated`. The published reason is truncated at the persistence boundary.
- Any other 4xx is not a decline and is not retried. It is a normal return, so it counts as a circuit-breaker success and never opens the circuit. A permanent 401 therefore stays closed. The payment is `UNDER_REVIEW`, the event is logged at error, and `payments.fraud.screening.unexpected_client_errors` increments. Alert on that counter.
- `UNDER_REVIEW` cannot be confirmed. The owner may cancel it. `POST /api/v1/payments/{id}/review?decision=APPROVE` is limited to `ADMIN` and `FRAUD_REVIEWER`, and the reviewer must not be the owner. Approval records `reviewer_id` and `reviewed_at`, clears the screening reason, moves the payment to `PENDING`, and emits `PaymentCreated`. `decision=REJECT` cancels it with the reviewer's own reason. Replaying the original `Idempotency-Key` still returns the stored 201 `UNDER_REVIEW` body.
- The provider `Idempotency-Key` is derived from `(userId, Idempotency-Key)`. A client retry after the lease is released sends that same header. The payment row id is a new UUID, so an expired key can create another payment without colliding with the previous row.
- The HTTP read timeout is longer than the time limiter. The limiter cancels the future, which interrupts the virtual thread, so the worker returns before the read timeout. The JDK client is warmed with a non-blocking `HEAD` at startup so the first request does not pay class-loading and connection setup inside the limiter.
- `screening_reason`, `reviewer_id`, and `reviewed_at` live in `V7__payment_screening_reason.sql`. `V6__payments_list_index.sql` stays the list index from main.
- Profile `prod` requires `FRAUD_SCREENING_BASE_URL` and `https`. The base Kubernetes ConfigMap does not contain a URL. Local Compose runs a WireMock that answers `CLEAR`. Startup rejects a profile whose `minimum-number-of-calls` is below `max-attempts`, whose connect timeout exceeds the limiter, or whose read timeout is not greater than the limiter.

## Consequences

- A provider outage creates held payments instead of failing the request closed. Operators release or reject them. Nothing in that path confirms the payment.
- Each retry is visible to the circuit breaker. A short window can open during a burst of retries. The configured minimum number of calls avoids opening on a single screening.
- The provider must treat the payment id as an idempotency key. A provider that does not will run the screening once per attempt.
- A crash inside the short processing window can still return 409 until that lease expires. Two minutes is the bound, not 24 hours.
- Other 4xx responses do not open the circuit. Operators have to watch `payments.fraud.screening.unexpected_client_errors`.

## Alternatives

| Option | Why not |
|---|---|
| Approve when the provider is down | Silent approval. |
| One transaction around the HTTP call | Pins a pooled connection and blocks other requests on the same idempotency key. |
| Treat every 4xx as a decline | 401, 404, and 429 would decline every payment and would not open the circuit. |
