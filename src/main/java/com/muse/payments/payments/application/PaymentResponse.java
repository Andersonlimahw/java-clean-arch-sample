package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID userId,
        BigDecimal amount,
        String currency,
        String description,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        String screeningReason
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getUserId(),
                payment.getMoney().amount(),
                payment.getMoney().currency(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getVersion(),
                payment.getScreeningReason()
        );
    }
}
