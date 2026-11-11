package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshToken;
import com.muse.payments.identity.domain.RefreshTokenRepository;
import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.security.JwtProperties;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class RefreshTokenCommandHandler implements CommandHandler<RefreshTokenCommand, AuthTokensResponse> {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCommandHandler.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;

    public RefreshTokenCommandHandler(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            JwtTokenIssuer jwtTokenIssuer,
            JwtProperties jwtProperties
    ) {
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.jwtTokenIssuer = Objects.requireNonNull(jwtTokenIssuer);
        this.jwtProperties = Objects.requireNonNull(jwtProperties);
    }

    @Override
    @Transactional
    public AuthTokensResponse handle(RefreshTokenCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        String hashedToken = TokenHash.hash(command.refreshToken());
        RefreshToken existingToken = refreshTokenRepository.findByTokenHash(hashedToken)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (existingToken.isRevoked()) {
            log.warn("Security breach alert: revoked refresh token reused in family {}. Revoking family.", existingToken.getFamilyId());
            refreshTokenRepository.revokeFamily(existingToken.getFamilyId());
            throw new BadCredentialsException("Refresh token was previously revoked. Entire token family invalidated.");
        }

        Instant now = Instant.now();
        if (existingToken.isExpired(now)) {
            existingToken.revoke();
            refreshTokenRepository.save(existingToken);
            throw new BadCredentialsException("Refresh token is expired");
        }

        User user = userRepository.findById(existingToken.getUserId())
                .orElseThrow(() -> new BadCredentialsException("User associated with token not found"));

        if (!user.isEnabled()) {
            throw new BadCredentialsException("User account is disabled");
        }

        String newRawToken = TokenHash.generateOpaqueToken();
        String newHashedToken = TokenHash.hash(newRawToken);
        Instant newExpiresAt = now.plus(jwtProperties.refreshTokenTtl());

        RefreshToken childToken = existingToken.rotate(newHashedToken, newExpiresAt);
        refreshTokenRepository.save(existingToken);
        refreshTokenRepository.save(childToken);

        String newAccessToken = jwtTokenIssuer.issueAccessToken(user.getId(), user.getRoles());
        return AuthTokensResponse.of(newAccessToken, newRawToken, jwtProperties.accessTokenTtl().toSeconds());
    }
}
