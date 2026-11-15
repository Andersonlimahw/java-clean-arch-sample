package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentRejectedInReviewEvent(
        UUID eventId,
        UUID paymentId,
        UUID userId,
        UUID reviewerId,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public PaymentRejectedInReviewEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(reviewerId, "reviewerId must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public PaymentRejectedInReviewEvent(UUID paymentId, UUID userId, UUID reviewerId, String reason, Instant occurredAt) {
        this(UUID.randomUUID(), paymentId, userId, reviewerId, reason, occurredAt);
    }
}
