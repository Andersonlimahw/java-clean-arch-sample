package com.muse.payments.payments.application;

import java.util.UUID;

/**
 * Dedupe and audit state for inbound provider callbacks. Claim and resolve run in the
 * same transaction as the settlement, so a rolled-back settlement also rolls the claim
 * back and the provider retry is processed.
 */
public interface PaymentProviderEventStorePort {

    /**
     * Inserts the delivery once. Returns {@code false} when the delivery was already
     * claimed (duplicate).
     */
    boolean claim(String provider, String eventId, String eventType, UUID paymentId);

    /** Records the local outcome of a claimed delivery. */
    void resolve(String provider, String eventId, String outcome);
}
