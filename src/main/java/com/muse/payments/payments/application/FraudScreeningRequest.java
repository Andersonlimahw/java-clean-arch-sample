package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code paymentId} is generated before the call and is stable across retries.
 */
public record FraudScreeningRequest(UUID paymentId, UUID userId, Money money) {

    public FraudScreeningRequest {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(money, "money must not be null");
    }
}
