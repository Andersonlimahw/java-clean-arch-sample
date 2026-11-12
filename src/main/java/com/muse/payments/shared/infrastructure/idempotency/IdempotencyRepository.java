package com.muse.payments.shared.infrastructure.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    Optional<IdempotencyRecord> find(UUID userId, String key);

    boolean tryInsertProcessing(UUID userId, String key, String requestHash, Duration ttl);

    void markCompleted(UUID userId, String key, String requestHash, int responseCode, String responseBodyJson);

    int deleteExpired(Instant now, int limit);
}
