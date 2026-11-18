# ADR 0019 — Provider strategy port and signed PSP webhooks

- **Status:** Accepted
- **Date:** 2026-11-18
- **Complements:** [ADR 0016](0016-event-sourcing-with-hybrid-projections.md), [ADR 0012](0012-fraud-screening-failure-policy.md)

## Context

The platform confirms payments only through a local command. There is no outbound PSP
call and no way for an external provider to confirm or reject a payment
asynchronously. Adding Stripe directly to the aggregate or the controller would couple
the core to one vendor and make a second provider a rewrite.

Providers differ in transport, payload shape, event names, and signature scheme. They
share one contract: create an intent for a payment, then deliver signed asynchronous
events about that intent.

## Decision

`PaymentProviderStrategy` is a payments-domain port with three operations:
`providerId()`, `createIntent(...)`, and `verifyAndParse(...)`. Implementations live in
`payments/infrastructure/psp/<provider>` and are discovered through a registry built
from the injected strategy list. The webhook path names the provider, and an unknown
name is `404`; there is no default fallback.

Intent creation happens after the payment transaction commits, so an external HTTP call
never holds a database connection. The result is appended as
`PaymentProviderIntentCreated`, and the provider reference lives on the aggregate and in
the `payments` projection.

Settlement enters through the same aggregate rules as a user command:
`settleFromProvider` validates the recorded provider and reference and raises the
existing `PaymentCompletedEvent` or `PaymentCancelledEvent`. The webhook transaction
first inserts `(provider, event_id)` into `payment_provider_events`; a conflict means a
duplicate delivery. Both the dedupe row and the settlement commit or roll back
together, so a retry after a local failure is still processed.

Stripe signature verification follows the documented manual algorithm: parse `t` and
every `v1` from `Stripe-Signature`, HMAC-SHA256 the bytes of `"{t}.{rawBody}"` with the
endpoint secret, compare with `MessageDigest.isEqual`, and reject a timestamp outside
the tolerance (default five minutes). `v0` and debug entries are ignored. The raw body
is read as bytes and never re-serialized.

Held payments are never settled by a webhook. An `UNDER_REVIEW` payment stays held, the
webhook is acknowledged as ignored, and a reviewer keeps the decision. Unknown event
types are acknowledged and ignored so a provider does not retry them forever.

## Consequences

A new provider is one adapter, one configuration block, and a registered webhook URL;
the core, the schema, and the existing adapters do not change. Provider-specific
secrets stay in configuration and are validated at startup under `prod`.

The webhook is unauthenticated at the HTTP layer by design: the HMAC is the
authentication, so the route is `permitAll`, is not rate-limited per user, and must
never be exposed without a configured secret.

Intent creation is best effort after the commit. A provider outage leaves a `PENDING`
payment without a reference and increments a failure counter; reconciliation is
explicitly out of scope for this sample and would be a separate sweeper.

`payment_provider_events` grows with delivery volume. It is indexed by `received_at`
so the existing bounded-retention pattern can prune it later without a schema change.

## Eval

`StripeWebhookSignatureTest` covers valid, tampered, wrong-secret, stale, `v0`-only, and
multi-`v1` headers. `PspWebhookIT` sends a signed `payment_intent.succeeded` twice and
requires one `PaymentCompleted` and one duplicate acknowledgment, plus `404` for an
unknown provider and `400` for a bad signature.
