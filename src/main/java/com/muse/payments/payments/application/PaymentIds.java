package com.muse.payments.payments.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PaymentIds {

    private PaymentIds() {
    }

    /**
     * Stable across retries of the same idempotency key, including after a released lease.
     */
    public static UUID forIdempotencyKey(UUID userId, String idempotencyKey) {
        String name = userId + ":" + idempotencyKey;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
