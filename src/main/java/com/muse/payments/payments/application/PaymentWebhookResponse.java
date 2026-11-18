package com.muse.payments.payments.application;

import java.util.Objects;

/**
 * Acknowledgment returned to the provider. The provider only needs a 2xx; the outcome
 * tells an operator or a log what the platform did with the delivery.
 */
public record PaymentWebhookResponse(String provider, String eventId, String outcome) {

    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String IGNORED = "IGNORED";
    public static final String DUPLICATE = "DUPLICATE";

    public PaymentWebhookResponse {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
