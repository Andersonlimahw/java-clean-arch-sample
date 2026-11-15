package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshToken;

import java.util.Optional;

public interface RefreshTokenReadRepository {

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
