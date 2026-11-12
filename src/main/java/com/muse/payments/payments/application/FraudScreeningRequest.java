package com.muse.payments.payments.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record FraudScreeningRequest(UUID userId, BigDecimal amount, String currency) {

    public FraudScreeningRequest {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
