package com.muse.payments.payments.domain;

public enum PaymentStatus {
    PENDING,
    /**
     * Fraud screening did not clear the payment. Confirm is rejected.
     * The owner may cancel. A review may release it to {@link #PENDING} or reject it to {@link #CANCELLED}.
     * This status is not an approval.
     */
    UNDER_REVIEW,
    COMPLETED,
    CANCELLED
}
