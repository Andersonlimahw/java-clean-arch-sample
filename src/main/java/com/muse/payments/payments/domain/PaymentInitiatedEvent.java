package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentInitiatedEvent(
        UUID eventId,
        UUID paymentId,
        UUID userId,
        BigDecimal amount,
        String currency,
        String description,
        Instant occurredAt
) implements DomainEvent {

    public PaymentInitiatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public PaymentInitiatedEvent(UUID paymentId, UUID userId, BigDecimal amount, String currency, String description) {
        this(UUID.randomUUID(), paymentId, userId, amount, currency, description, Instant.now());
    }
}
