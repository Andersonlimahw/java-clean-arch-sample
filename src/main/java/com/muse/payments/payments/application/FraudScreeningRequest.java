package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code paymentId} is also the provider idempotency key. It is derived from the
 * caller, the client key, and the lease timestamp, so the header and the body match
 * and a later lease does not reuse the id.
 */
public record FraudScreeningRequest(UUID paymentId, UUID userId, Money money) {

    public FraudScreeningRequest {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(money, "money must not be null");
    }
}
