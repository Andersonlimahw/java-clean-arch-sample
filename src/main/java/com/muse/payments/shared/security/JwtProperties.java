package com.muse.payments.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String keyId,
        String privateKeyPem,
        String publicKeyPem,
        String previousKeyId,
        String previousPublicKeyPem
) {
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "https://api.payments.muse.com";
        }
        if (audience == null || audience.isBlank()) {
            audience = "payments-api";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
        if (keyId == null || keyId.isBlank()) {
            keyId = "payments-jwt-key-v1";
        }
        if (previousKeyId == null || previousKeyId.isBlank()) {
            previousKeyId = "payments-jwt-key-v0";
        }
    }
}
