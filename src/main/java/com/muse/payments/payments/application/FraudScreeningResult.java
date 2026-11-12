package com.muse.payments.payments.application;

import java.util.Objects;

public record FraudScreeningResult(FraudScreeningOutcome outcome, String reason) {

    public FraudScreeningResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome != FraudScreeningOutcome.CLEAR && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason is required unless the outcome is CLEAR");
        }
    }

    public static FraudScreeningResult clear() {
        return new FraudScreeningResult(FraudScreeningOutcome.CLEAR, null);
    }

    public static FraudScreeningResult declined(String reason) {
        return new FraudScreeningResult(FraudScreeningOutcome.DECLINED, reason);
    }

    public static FraudScreeningResult underReview(String reason) {
        return new FraudScreeningResult(FraudScreeningOutcome.UNDER_REVIEW, reason);
    }
}
