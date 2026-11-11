package com.muse.payments.shared.infrastructure.idempotency;

public record IdempotencyDecision(
        boolean isReplayed,
        Integer responseCode,
        String responseBody
) {
    public static IdempotencyDecision proceed() {
        return new IdempotencyDecision(false, null, null);
    }

    public static IdempotencyDecision replayed(int responseCode, String responseBody) {
        return new IdempotencyDecision(true, responseCode, responseBody);
    }
}
