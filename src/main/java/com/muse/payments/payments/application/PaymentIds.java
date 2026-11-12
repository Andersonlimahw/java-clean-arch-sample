package com.muse.payments.payments.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public final class PaymentIds {

    private PaymentIds() {
    }

    /**
     * One id for the payment row and the provider {@code Idempotency-Key}.
     * The lease {@code createdAt} is part of the seed, so a later lease for the same
     * client key (after release or after expiry) is a different payment.
     */
    public static UUID forLease(UUID userId, String idempotencyKey, Instant leaseCreatedAt) {
        String name = userId + ":" + idempotencyKey + ":" + leaseCreatedAt.toEpochMilli();
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
