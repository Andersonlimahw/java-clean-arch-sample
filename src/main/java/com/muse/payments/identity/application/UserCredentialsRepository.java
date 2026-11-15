package com.muse.payments.identity.application;

import java.util.Optional;
import java.util.UUID;

public interface UserCredentialsRepository {

    void save(UUID userId, String passwordHash);

    Optional<String> findPasswordHashByUserId(UUID userId);
}
