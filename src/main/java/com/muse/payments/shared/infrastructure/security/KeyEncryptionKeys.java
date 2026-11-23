package com.muse.payments.shared.infrastructure.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The active key-encryption key (KEK) that wraps new data keys, and an optional previous KEK that
 * still unwraps keys during a rotation. Key bytes are copied on the way in and out and are never
 * part of {@link #toString()}.
 */
public final class KeyEncryptionKeys {

    private final String activeId;
    private final byte[] active;
    private final String previousId;
    private final byte[] previous;
    private final boolean ephemeral;

    KeyEncryptionKeys(String activeId, byte[] active, String previousId, byte[] previous, boolean ephemeral) {
        this.activeId = requireId(activeId);
        this.active = requireKey(active).clone();
        if (previous != null) {
            this.previousId = requireId(previousId);
            if (this.previousId.equals(this.activeId)) {
                throw new IllegalStateException("LGPD_PREVIOUS_KEK_ID must differ from LGPD_KEK_ID");
            }
            this.previous = requireKey(previous).clone();
        } else {
            this.previousId = null;
            this.previous = null;
        }
        this.ephemeral = ephemeral;
    }

    public static KeyEncryptionKeys of(String activeId, byte[] active) {
        return new KeyEncryptionKeys(activeId, active, null, null, false);
    }

    public String activeId() {
        return activeId;
    }

    byte[] active() {
        return active.clone();
    }

    Optional<byte[]> find(String kekId) {
        if (activeId.equals(kekId)) {
            return Optional.of(active.clone());
        }
        if (previous != null && previousId.equals(kekId)) {
            return Optional.of(previous.clone());
        }
        return Optional.empty();
    }

    /** True when the active KEK was generated for this JVM only (never under the prod profile). */
    public boolean ephemeral() {
        return ephemeral;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalStateException("A KEK id must be 1-64 characters");
        }
        return id;
    }

    private static byte[] requireKey(byte[] key) {
        Objects.requireNonNull(key, "KEK bytes are required");
        if (key.length != AesGcmCryptoService.KEY_BYTES) {
            throw new IllegalStateException("A KEK must be exactly 32 bytes (AES-256)");
        }
        if (Arrays.equals(key, new byte[key.length])) {
            throw new IllegalStateException("A KEK must not be all zeros");
        }
        return key;
    }

    @Override
    public String toString() {
        return "KeyEncryptionKeys[activeId=" + activeId + ", previousId=" + previousId
                + ", ephemeral=" + ephemeral + "]";
    }
}
