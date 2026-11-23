package com.muse.payments.shared.application;

import java.util.UUID;

/** Owns the per-subject data keys that protect personal data inside event payloads. */
public interface PersonalDataKeyPort {

    /**
     * Permanently destroys the subject's data key inside the caller's transaction. Encrypted
     * personal data of the subject becomes unreadable, and no new key can be created for them.
     *
     * @return {@code true} when this call destroyed the key, {@code false} when it was already gone
     */
    boolean destroy(UUID subjectId);
}
