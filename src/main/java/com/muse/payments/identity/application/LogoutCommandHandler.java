package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshTokenRepository;
import com.muse.payments.shared.application.CommandHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class LogoutCommandHandler implements CommandHandler<LogoutCommand, Void> {

    private final RefreshTokenRepository refreshTokenRepository;

    public LogoutCommandHandler(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository);
    }

    @Override
    @Transactional
    public Void handle(LogoutCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        if (command.refreshToken() != null && !command.refreshToken().isBlank()) {
            String hashedToken = TokenHash.hash(command.refreshToken());
            refreshTokenRepository.findByTokenHash(hashedToken)
                    .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId()));
        }

        return null;
    }
}
