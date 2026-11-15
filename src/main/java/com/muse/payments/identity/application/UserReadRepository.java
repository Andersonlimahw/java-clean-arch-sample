package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserReadRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
