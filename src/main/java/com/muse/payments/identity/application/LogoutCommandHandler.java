package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshTokenFamilyRepository;
import com.muse.payments.shared.application.CommandHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class LogoutCommandHandler implements CommandHandler<LogoutCommand, Void> {

    private final RefreshTokenReadRepository refreshTokenReadRepository;
    private final RefreshTokenFamilyRepository refreshTokenFamilyRepository;

    public LogoutCommandHandler(
            RefreshTokenReadRepository refreshTokenReadRepository,
            RefreshTokenFamilyRepository refreshTokenFamilyRepository
    ) {
        this.refreshTokenReadRepository = Objects.requireNonNull(refreshTokenReadRepository);
        this.refreshTokenFamilyRepository = Objects.requireNonNull(refreshTokenFamilyRepository);
    }

    @Override
    @Transactional
    public Void handle(LogoutCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        if (command.refreshToken() != null && !command.refreshToken().isBlank()) {
            String hashedToken = TokenHash.hash(command.refreshToken());
            refreshTokenReadRepository.findByTokenHash(hashedToken)
                    .ifPresent(token -> refreshTokenFamilyRepository.revokeFamily(
                            token.getFamilyId(), "User logout"));
        }

        return null;
    }
}
