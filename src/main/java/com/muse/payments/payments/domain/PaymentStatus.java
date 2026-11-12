package com.muse.payments.payments.domain;

public enum PaymentStatus {
    PENDING,
    /**
     * Fraud screening did not return a decision. The payment exists so it can be reviewed,
     * and it must not be confirmed.
     */
    UNDER_REVIEW,
    COMPLETED,
    CANCELLED
}
