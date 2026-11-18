package com.muse.payments.payments.domain.psp;

import java.util.Map;
import java.util.Objects;

/**
 * A raw inbound callback. {@code rawBody} is the exact bytes received: a provider signs
 * the bytes, so nothing may re-serialize them before verification. Header lookup is
 * case-insensitive in the adapter.
 */
public record ProviderWebhookRequest(String provider, String rawBody, Map<String, String> headers) {

    public ProviderWebhookRequest {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
