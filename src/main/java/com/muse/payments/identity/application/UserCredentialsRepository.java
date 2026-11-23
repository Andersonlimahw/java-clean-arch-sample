package com.muse.payments.identity.application;

import java.util.Optional;
import java.util.UUID;

public interface UserCredentialsRepository {

    void save(UUID userId, String passwordHash);

    Optional<String> findPasswordHashByUserId(UUID userId);

    /** Removes the stored password hash; used when the user's personal data is erased. */
    void delete(UUID userId);
}
