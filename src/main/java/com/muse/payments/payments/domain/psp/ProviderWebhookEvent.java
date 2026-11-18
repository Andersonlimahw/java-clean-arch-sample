package com.muse.payments.payments.domain.psp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A verified provider event, already translated to the local meaning. {@code eventId} is
 * the provider's unique delivery id and is the dedupe key. {@code paymentId} comes from
 * provider metadata; a null id means the event cannot be addressed and is ignored.
 */
public record ProviderWebhookEvent(
        String provider,
        String eventId,
        String eventType,
        UUID paymentId,
        String providerReference,
        ProviderEventOutcome outcome,
        String reason,
        Instant occurredAt
) {

    public ProviderWebhookEvent {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
