package com.muse.payments.payments.application;

/**
 * Decision returned by the fraud-screening port. Infrastructure maps provider failures
 * onto these values. {@link #UNDER_REVIEW} is never an approval.
 */
public enum FraudScreeningOutcome {
    /** The provider cleared the payment. It may be created as pending. */
    CLEAR,
    /** The provider rejected the payment. It must not be created. */
    DECLINED,
    /**
     * The provider did not return a decision (timeout, 5xx, I/O, or an open circuit).
     * The payment is held for review and must not be confirmed.
     */
    UNDER_REVIEW
}
