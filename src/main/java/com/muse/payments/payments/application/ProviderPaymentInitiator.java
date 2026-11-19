package com.muse.payments.payments.application;

import java.util.UUID;

/**
 * Seam between a payment state change and the provider call. The implementation runs after
 * the surrounding transaction commits and must never throw into the caller: a provider
 * outage leaves the payment without an intent and is counted, never silent.
 */
@FunctionalInterface
public interface ProviderPaymentInitiator {

    void initiate(UUID paymentId);

    /**
     * Defers the call to after the current transaction commits. Callers that already run
     * outside a transaction may use {@link #initiate(UUID)} directly.
     */
    default void initiateAfterCommit(UUID paymentId) {
        initiate(paymentId);
    }
}
