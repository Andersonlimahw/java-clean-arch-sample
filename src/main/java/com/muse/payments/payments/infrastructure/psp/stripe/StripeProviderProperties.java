package com.muse.payments.payments.infrastructure.psp.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.psp.stripe")
public record StripeProviderProperties(
        boolean enabled,
        URI baseUrl,
        String apiKey,
        String webhookSecret,
        Duration webhookTolerance,
        Duration connectTimeout,
        Duration readTimeout
) {

    public static final String DEV_SECRET = "whsec_local_dev_only";

    public StripeProviderProperties {
        if (enabled) {
            if (baseUrl == null) {
                throw new IllegalArgumentException("app.psp.stripe.base-url is required");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("app.psp.stripe.api-key is required");
            }
            if (webhookSecret == null || webhookSecret.isBlank()) {
                throw new IllegalArgumentException("app.psp.stripe.webhook-secret is required");
            }
            requirePositive(webhookTolerance, "webhook-tolerance");
            requirePositive(connectTimeout, "connect-timeout");
            requirePositive(readTimeout, "read-timeout");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.psp.stripe." + name + " must be positive");
        }
    }
}
