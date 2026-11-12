package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code providerIdempotencyKey} is derived from the caller and the client idempotency key.
 * It stays the same when that key is retried after the lease is released.
 * {@code paymentId} is the row id and is a new value when an expired key starts a new payment.
 */
public record FraudScreeningRequest(UUID paymentId, UUID userId, Money money, UUID providerIdempotencyKey) {

    public FraudScreeningRequest {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(money, "money must not be null");
        Objects.requireNonNull(providerIdempotencyKey, "providerIdempotencyKey must not be null");
    }
}
