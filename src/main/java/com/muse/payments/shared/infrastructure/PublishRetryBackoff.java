package com.muse.payments.shared.infrastructure;

import java.time.Duration;

/**
 * Exponential backoff with caller-supplied jitter for outbox publish retries.
 * The cap keeps a poisoned broker from pushing the next attempt past five minutes.
 */
public final class PublishRetryBackoff {

    static final int MAX_SECONDS = 300;

    private PublishRetryBackoff() {
    }

    public static Duration delay(int attempts, int jitterSeconds) {
        int safeAttempts = Math.max(1, Math.min(attempts, 8));
        int jitter = Math.max(0, jitterSeconds);
        long exponential = (1L << safeAttempts) * 2L;
        long seconds = Math.min(MAX_SECONDS, exponential + jitter);
        return Duration.ofSeconds(seconds);
    }
}
