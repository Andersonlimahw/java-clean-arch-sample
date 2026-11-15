package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.domain.RefreshToken;
import com.muse.payments.identity.domain.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
public class RefreshTokenJpaAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository repository;

    public RefreshTokenJpaAdapter(RefreshTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId) {
        repository.revokeAllInFamily(familyId);
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        RefreshTokenJpaEntity entity = toEntity(token);
        RefreshTokenJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public RefreshToken insert(RefreshToken token) {
        RefreshTokenJpaEntity entity = toEntity(token).markNew();
        RefreshTokenJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    private RefreshToken toDomain(RefreshTokenJpaEntity entity) {
        return new RefreshToken(
                entity.getId(),
                entity.getUserId(),
                entity.getTokenHash(),
                entity.getFamilyId(),
                entity.isRevoked(),
                entity.getExpiresAt(),
                entity.getCreatedAt()
        );
    }

    private RefreshTokenJpaEntity toEntity(RefreshToken domain) {
        return new RefreshTokenJpaEntity(
                domain.getId(),
                domain.getUserId(),
                domain.getTokenHash(),
                domain.getFamilyId(),
                domain.isRevoked(),
                domain.getExpiresAt(),
                domain.getCreatedAt()
        );
    }
}
