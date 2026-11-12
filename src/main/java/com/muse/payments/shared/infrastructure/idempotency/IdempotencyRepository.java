package com.muse.payments.shared.infrastructure.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    Optional<IdempotencyRecord> find(UUID userId, String key);

    /**
     * Inserts a processing row, or reclaims one that is already expired.
     * Empty when a live row still owns the key.
     */
    Optional<ProcessingLease> tryInsertProcessing(UUID userId, String key, String requestHash, Duration ttl);

    int markCompleted(UUID userId, String key, String requestHash, UUID leaseId, Integer responseCode, String responseBodyJson);

    int deleteProcessing(UUID userId, String key, String requestHash, UUID leaseId);

    int deleteExpired(Instant now, int limit);
}
