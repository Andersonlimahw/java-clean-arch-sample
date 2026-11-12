package com.muse.payments.payments.infrastructure.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.fraud-screening")
public record FraudScreeningProperties(URI baseUrl, Duration connectTimeout) {

    public FraudScreeningProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("app.fraud-screening.base-url is required");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("app.fraud-screening.connect-timeout must be positive");
        }
    }
}
