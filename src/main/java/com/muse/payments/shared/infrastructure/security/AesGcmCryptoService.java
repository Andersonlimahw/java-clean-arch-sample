package com.muse.payments.shared.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * AES-256-GCM with a fresh random 96-bit IV per call and a 128-bit tag. Output is
 * {@code iv || ciphertext || tag}. Callers bind context through the additional authenticated data.
 */
@Component
public class AesGcmCryptoService {

    public static final String ALGORITHM = "AES-256-GCM";
    public static final int KEY_BYTES = 32;
    static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();

    public byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return key;
    }

    public byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        Objects.requireNonNull(plaintext, "plaintext is required");
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(Objects.requireNonNull(aad, "aad is required"));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] sealed = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, sealed, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, sealed, IV_BYTES, ciphertext.length);
            return sealed;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * @throws CryptoIntegrityException when the key, the AAD, or the ciphertext does not match
     */
    public byte[] decrypt(byte[] key, byte[] sealed, byte[] aad) {
        Objects.requireNonNull(sealed, "sealed value is required");
        if (sealed.length < IV_BYTES + TAG_BITS / 8) {
            throw new CryptoIntegrityException("Sealed value is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(key),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, IV_BYTES)));
            cipher.updateAAD(Objects.requireNonNull(aad, "aad is required"));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new CryptoIntegrityException("Authentication tag mismatch");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    private static SecretKeySpec secretKey(byte[] key) {
        Objects.requireNonNull(key, "key is required");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 requires a 32-byte key");
        }
        return new SecretKeySpec(key, "AES");
    }

    /** The sealed value was not produced with this key and AAD. Never carries key or plaintext. */
    public static final class CryptoIntegrityException extends RuntimeException {
        CryptoIntegrityException(String message) {
            super(message);
        }
    }
}
