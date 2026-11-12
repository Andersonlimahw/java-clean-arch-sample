package com.muse.payments.shared.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyDecision(
        boolean isReplayed,
        Integer responseCode,
        String responseBody,
        UUID leaseId,
        Instant acquiredAt
) {
    public static IdempotencyDecision proceed(UUID leaseId, Instant acquiredAt) {
        return new IdempotencyDecision(
                false,
                null,
                null,
                Objects.requireNonNull(leaseId, "leaseId must not be null"),
                Objects.requireNonNull(acquiredAt, "acquiredAt must not be null")
        );
    }

    public static IdempotencyDecision replayed(int responseCode, String responseBody) {
        return new IdempotencyDecision(true, responseCode, responseBody, null, null);
    }
}
