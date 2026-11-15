package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.application.RefreshTokenReadRepository;
import com.muse.payments.identity.domain.RefreshToken;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class RefreshTokenJpaReadAdapter implements RefreshTokenReadRepository {

    private final RefreshTokenJpaRepository refreshTokenJpaRepository;

    public RefreshTokenJpaReadAdapter(RefreshTokenJpaRepository refreshTokenJpaRepository) {
        this.refreshTokenJpaRepository = Objects.requireNonNull(refreshTokenJpaRepository);
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return refreshTokenJpaRepository.findByTokenHash(tokenHash)
                .map(entity -> new RefreshToken(
                        entity.getId(),
                        entity.getUserId(),
                        entity.getTokenHash(),
                        entity.getFamilyId(),
                        entity.isRevoked(),
                        entity.getExpiresAt(),
                        entity.getCreatedAt()
                ));
    }
}
