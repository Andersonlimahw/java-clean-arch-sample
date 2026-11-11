package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentCompletedEvent(
        UUID eventId,
        UUID paymentId,
        UUID userId,
        Instant occurredAt
) implements DomainEvent {

    public PaymentCompletedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public PaymentCompletedEvent(UUID paymentId, UUID userId) {
        this(UUID.randomUUID(), paymentId, userId, Instant.now());
    }
}
