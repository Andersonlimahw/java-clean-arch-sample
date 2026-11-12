package com.muse.payments.payments.infrastructure.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.fraud-screening")
public record FraudScreeningProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        String resilienceInstance,
        int httpPoolSize
) {

    public FraudScreeningProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("app.fraud-screening.base-url is required");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("app.fraud-screening.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("app.fraud-screening.read-timeout must be positive");
        }
        if (resilienceInstance == null || resilienceInstance.isBlank()) {
            throw new IllegalArgumentException("app.fraud-screening.resilience-instance is required");
        }
        if (httpPoolSize < 1) {
            throw new IllegalArgumentException("app.fraud-screening.http-pool-size must be at least 1");
        }
    }
}
