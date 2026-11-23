package com.muse.payments.shared.infrastructure.security;

import java.util.Optional;
import java.util.UUID;

/** Plain data keys per subject, as seen by the payload protector. An empty result means shredded. */
public interface SubjectKeyStore {

    /** The subject's key, created on first use. Empty when the key was destroyed. */
    Optional<byte[]> keyForWrite(UUID subjectId);

    /** The subject's key. Empty when it was destroyed or never existed. */
    Optional<byte[]> keyForRead(UUID subjectId);
}
