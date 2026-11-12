package com.muse.payments.shared.application;

import java.time.Duration;
import java.util.UUID;

/**
 * Idempotency for commands. {@link #acquireProcessing} uses a short lease.
 * {@link #saveCompleted} extends that lease to {@link #COMPLETED_TTL}.
 */
public interface IdempotencyPort {

    Duration COMPLETED_TTL = Duration.ofHours(24);

    IdempotencyDecision acquireProcessing(UUID userId, String key, String payloadJson);

    void saveCompleted(UUID userId, String key, String payloadJson, UUID leaseId, String responseJson);

    void releaseProcessing(UUID userId, String key, String payloadJson, UUID leaseId);
}
