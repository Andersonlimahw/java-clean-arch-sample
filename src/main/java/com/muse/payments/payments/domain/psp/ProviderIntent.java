package com.muse.payments.payments.domain.psp;

import java.util.Objects;

/**
 * A provider-side intent reference. {@code providerStatus} is the provider's own status
 * string, kept for diagnostics only; the local payment state stays authoritative.
 */
public record ProviderIntent(String provider, String reference, String providerStatus) {

    public ProviderIntent {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
    }
}
