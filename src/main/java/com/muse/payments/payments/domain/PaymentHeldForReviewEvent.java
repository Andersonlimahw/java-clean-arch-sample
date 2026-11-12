package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The payment was stored for review. It is not an approval and must not be announced as created.
 */
public record PaymentHeldForReviewEvent(
        UUID eventId,
        UUID paymentId,
        UUID userId,
        BigDecimal amount,
        String currency,
        String description,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public PaymentHeldForReviewEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public PaymentHeldForReviewEvent(
            UUID paymentId,
            UUID userId,
            BigDecimal amount,
            String currency,
            String description,
            String reason
    ) {
        this(UUID.randomUUID(), paymentId, userId, amount, currency, description, reason, Instant.now());
    }
}
