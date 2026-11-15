package com.muse.payments.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void revokeFamily(UUID familyId);

    RefreshToken save(RefreshToken token);

    default RefreshToken insert(RefreshToken token) {
        return save(token);
    }
}
