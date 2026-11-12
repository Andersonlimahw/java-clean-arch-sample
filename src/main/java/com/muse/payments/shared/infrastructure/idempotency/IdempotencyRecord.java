package com.muse.payments.shared.infrastructure.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyRecord(
        String key,
        UUID userId,
        String requestHash,
        String status,
        Integer responseCode,
        String responseBody,
        Instant createdAt,
        Instant expiresAt
) {
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public boolean isCompleted() {
        return STATUS_COMPLETED.equalsIgnoreCase(status);
    }

    public boolean isProcessing() {
        return STATUS_PROCESSING.equalsIgnoreCase(status);
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}
