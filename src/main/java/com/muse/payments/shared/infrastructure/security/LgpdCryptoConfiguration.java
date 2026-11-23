package com.muse.payments.shared.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Builds the KEK set from {@code app.lgpd.*} (ADR 0020). The {@code prod} profile requires a
 * configured KEK. Other profiles fall back to one ephemeral KEK per JVM, shared by every Spring
 * context in that JVM so tests that share a database keep reading each other's events.
 */
@Configuration
public class LgpdCryptoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LgpdCryptoConfiguration.class);

    @Bean
    KeyEncryptionKeys keyEncryptionKeys(
            Environment environment,
            @Value("${app.lgpd.kek-base64:}") String kekBase64,
            @Value("${app.lgpd.kek-id:lgpd-kek-v1}") String kekId,
            @Value("${app.lgpd.previous-kek-base64:}") String previousKekBase64,
            @Value("${app.lgpd.previous-kek-id:lgpd-kek-v0}") String previousKekId
    ) {
        return build(environment, kekBase64, kekId, previousKekBase64, previousKekId);
    }

    static KeyEncryptionKeys build(
            Environment environment,
            String kekBase64,
            String kekId,
            String previousKekBase64,
            String previousKekId
    ) {
        byte[] previous = isBlank(previousKekBase64) ? null : decode(previousKekBase64, "LGPD_PREVIOUS_KEK_BASE64");
        if (!isBlank(kekBase64)) {
            return new KeyEncryptionKeys(kekId, decode(kekBase64, "LGPD_KEK_BASE64"), previousKekId, previous, false);
        }
        if (!ephemeralKekAllowed(environment)) {
            throw new IllegalStateException(
                    "The prod profile requires LGPD_KEK_BASE64 (32 random bytes, base64). Ephemeral KEKs are forbidden in production.");
        }
        if (previous != null) {
            throw new IllegalStateException("LGPD_PREVIOUS_KEK_BASE64 is set without LGPD_KEK_BASE64");
        }
        log.warn("LGPD_KEK_BASE64 is not set. Using an ephemeral KEK for this JVM; personal data encrypted now "
                + "reads as redacted after a restart. Set LGPD_KEK_BASE64 to keep it readable.");
        return new KeyEncryptionKeys(EphemeralKek.ID, EphemeralKek.KEY, null, null, true);
    }

    /** Same rule as JWT keys: never under {@code prod}, even when {@code migration} is added on top. */
    static boolean ephemeralKekAllowed(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            return false;
        }
        return environment.acceptsProfiles(Profiles.of("local", "test", "default", "migration"));
    }

    private static byte[] decode(String value, String name) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not valid base64");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class EphemeralKek {
        private static final byte[] KEY = new byte[AesGcmCryptoService.KEY_BYTES];
        private static final String ID = "ephemeral-" + UUID.randomUUID().toString().substring(0, 8);

        static {
            new SecureRandom().nextBytes(KEY);
        }
    }
}
