package com.muse.payments.payments.domain.psp;

import com.muse.payments.payments.domain.Money;
import com.muse.payments.shared.domain.ValidationException;

import java.util.Objects;
import java.util.UUID;

/**
 * A request to open a payment intent at the provider. The payment id is carried to the
 * provider as metadata so a later webhook can address the local payment without a
 * shared database key.
 */
public record ProviderIntentRequest(UUID paymentId, UUID userId, Money money, String description) {

    public ProviderIntentRequest {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(money, "money must not be null");
        if (description != null && description.length() > 255) {
            throw new ValidationException("Description must not exceed 255 characters");
        }
    }
}
