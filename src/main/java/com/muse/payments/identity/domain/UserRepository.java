package com.muse.payments.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<User> findById(UUID id);

    User save(User user);
}
