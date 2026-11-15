package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.application.UserReadRepository;
import com.muse.payments.identity.domain.User;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class UserJpaReadAdapter implements UserReadRepository {

    private final UserJpaRepository userJpaRepository;

    public UserJpaReadAdapter(UserJpaRepository userJpaRepository) {
        this.userJpaRepository = Objects.requireNonNull(userJpaRepository);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return userJpaRepository.findById(id).map(UserJpaReadAdapter::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userJpaRepository.findByEmail(email).map(UserJpaReadAdapter::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userJpaRepository.existsByEmail(email);
    }

    private static User toDomain(UserJpaEntity entity) {
        Set<String> roles = entity.getRoles() == null || entity.getRoles().isBlank()
                ? Set.of("USER")
                : Arrays.stream(entity.getRoles().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        return User.rehydrate(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                roles,
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
