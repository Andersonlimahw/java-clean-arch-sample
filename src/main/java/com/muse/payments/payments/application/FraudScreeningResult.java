package com.muse.payments.payments.application;

import java.util.Objects;

public record FraudScreeningResult(FraudScreeningOutcome outcome, String reason) {

    public FraudScreeningResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    public static FraudScreeningResult clear() {
        return new FraudScreeningResult(FraudScreeningOutcome.CLEAR, "Fraud provider cleared the payment");
    }

    public static FraudScreeningResult declined(String reason) {
        return new FraudScreeningResult(FraudScreeningOutcome.DECLINED, reason);
    }

    public static FraudScreeningResult underReview(String reason) {
        return new FraudScreeningResult(FraudScreeningOutcome.UNDER_REVIEW, reason);
    }
}
