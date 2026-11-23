package com.muse.payments.shared.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCryptoServiceTest {

    private static final byte[] AAD = "context".getBytes(StandardCharsets.UTF_8);

    private final AesGcmCryptoService crypto = new AesGcmCryptoService();

    @Test
    void roundTripsWithTheSameKeyAndAad() {
        byte[] key = crypto.newKey();
        byte[] plaintext = "ana@example.com".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = crypto.encrypt(key, plaintext, AAD);

        assertThat(sealed).hasSize(AesGcmCryptoService.IV_BYTES + plaintext.length + 16);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("ana@example.com");
        assertThat(crypto.decrypt(key, sealed, AAD)).isEqualTo(plaintext);
    }

    @Test
    void usesAFreshIvForEveryEncryption() {
        byte[] key = crypto.newKey();
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);

        byte[] first = crypto.encrypt(key, plaintext, AAD);
        byte[] second = crypto.encrypt(key, plaintext, AAD);

        assertThat(Arrays.copyOf(first, AesGcmCryptoService.IV_BYTES))
                .isNotEqualTo(Arrays.copyOf(second, AesGcmCryptoService.IV_BYTES));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsATamperedCiphertext() {
        byte[] key = crypto.newKey();
        byte[] sealed = crypto.encrypt(key, "value".getBytes(StandardCharsets.UTF_8), AAD);
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(key, sealed, AAD))
                .isInstanceOf(AesGcmCryptoService.CryptoIntegrityException.class);
    }

    @Test
    void rejectsAnotherAadOrKey() {
        byte[] key = crypto.newKey();
        byte[] sealed = crypto.encrypt(key, "value".getBytes(StandardCharsets.UTF_8), AAD);

        assertThatThrownBy(() -> crypto.decrypt(key, sealed, "other".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AesGcmCryptoService.CryptoIntegrityException.class);
        assertThatThrownBy(() -> crypto.decrypt(crypto.newKey(), sealed, AAD))
                .isInstanceOf(AesGcmCryptoService.CryptoIntegrityException.class);
    }

    @Test
    void requiresA256BitKey() {
        assertThatThrownBy(() -> crypto.encrypt(new byte[16], new byte[1], AAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
    }
}
