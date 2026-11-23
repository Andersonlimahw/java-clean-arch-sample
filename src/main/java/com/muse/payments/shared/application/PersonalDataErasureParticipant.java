package com.muse.payments.shared.application;

import java.util.UUID;

/**
 * A module's part of an erasure request. Runs inside the erasure transaction and must leave its
 * read models in the state a projection rebuild would produce after the subject's key is destroyed.
 */
public interface PersonalDataErasureParticipant {

    void erase(UUID subjectId);
}
