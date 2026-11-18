package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The provider accepted the payment and returned an intent reference. The local payment
 * stays {@link PaymentStatus#PENDING} until a verified webhook settles it.
 */
public record PaymentProviderIntentCreatedEvent(
        UUID eventId,
        UUID paymentId,
        String provider,
        String providerReference,
        Instant occurredAt
) implements DomainEvent {

    public PaymentProviderIntentCreatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public PaymentProviderIntentCreatedEvent(UUID paymentId, String provider, String providerReference) {
        this(UUID.randomUUID(), paymentId, provider, providerReference, Instant.now());
    }
}
