package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;

import java.util.UUID;

public record ScreenedPayment(
        UUID paymentId,
        UUID userId,
        Money money,
        String description,
        FraudScreeningOutcome outcome,
        String reason
) {
}
