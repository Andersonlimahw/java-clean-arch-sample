package com.muse.payments.payments.infrastructure.fraud;

import java.time.Duration;

/**
 * Worst case for one screening: every attempt runs until its timeout, plus the
 * maximum backoff between attempts (exponential wait, including the jitter ceiling).
 */
final class ScreeningBudget {

    private ScreeningBudget() {
    }

    static Duration of(
            Duration attemptTimeout,
            int maxAttempts,
            Duration initialWait,
            boolean exponential,
            double multiplier,
            boolean randomized,
            double randomizationFactor
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        double cap = randomized ? 1d + randomizationFactor : 1d;
        double intervalMillis = initialWait.toMillis();
        long backoffMillis = 0;
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            backoffMillis += (long) Math.ceil(intervalMillis * cap);
            if (exponential) {
                intervalMillis *= multiplier;
            }
        }
        return attemptTimeout.multipliedBy(maxAttempts).plusMillis(backoffMillis);
    }
}
