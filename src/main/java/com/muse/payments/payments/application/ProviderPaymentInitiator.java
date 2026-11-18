package com.muse.payments.payments.application;

import java.util.UUID;

/**
 * Seam between the create flow and the provider call. The implementation runs after the
 * create transaction has committed and must never throw into the caller.
 */
@FunctionalInterface
public interface ProviderPaymentInitiator {

    void initiate(UUID paymentId);
}
