package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentCancelledEvent(
        UUID eventId,
        UUID paymentId,
        UUID userId,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public PaymentCancelledEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public PaymentCancelledEvent(UUID paymentId, UUID userId, String reason) {
        this(UUID.randomUUID(), paymentId, userId, reason, Instant.now());
    }
}
