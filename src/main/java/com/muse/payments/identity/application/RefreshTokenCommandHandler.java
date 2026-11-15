package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshToken;
import com.muse.payments.identity.domain.RefreshTokenFamily;
import com.muse.payments.identity.domain.RefreshTokenFamilyRepository;
import com.muse.payments.identity.domain.User;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.security.JwtProperties;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;

@Service
public class RefreshTokenCommandHandler implements CommandHandler<RefreshTokenCommand, AuthTokensResponse> {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCommandHandler.class);

    private final RefreshTokenReadRepository refreshTokenReadRepository;
    private final RefreshTokenFamilyRepository refreshTokenFamilyRepository;
    private final UserReadRepository userReadRepository;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;
    private final TransactionTemplate transactionTemplate;

    public RefreshTokenCommandHandler(
            RefreshTokenReadRepository refreshTokenReadRepository,
            RefreshTokenFamilyRepository refreshTokenFamilyRepository,
            UserReadRepository userReadRepository,
            JwtTokenIssuer jwtTokenIssuer,
            JwtProperties jwtProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.refreshTokenReadRepository = Objects.requireNonNull(refreshTokenReadRepository);
        this.refreshTokenFamilyRepository = Objects.requireNonNull(refreshTokenFamilyRepository);
        this.userReadRepository = Objects.requireNonNull(userReadRepository);
        this.jwtTokenIssuer = Objects.requireNonNull(jwtTokenIssuer);
        this.jwtProperties = Objects.requireNonNull(jwtProperties);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
    }

    @Override
    public AuthTokensResponse handle(RefreshTokenCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        String hashedToken = TokenHash.hash(command.refreshToken());
        RefreshToken existingToken = refreshTokenReadRepository.findByTokenHash(hashedToken)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (existingToken.isRevoked()) {
            log.warn("Security breach alert: revoked refresh token reused in family {}. Revoking family.", existingToken.getFamilyId());
            refreshTokenFamilyRepository.revokeFamilyInNewTransaction(
                    existingToken.getFamilyId(), "Refresh token reuse detected");
            throw new BadCredentialsException("Refresh token was previously revoked. Entire token family invalidated.");
        }

        Instant now = Instant.now();
        if (existingToken.isExpired(now)) {
            transactionTemplate.executeWithoutResult(status ->
                    refreshTokenFamilyRepository.findById(existingToken.getFamilyId()).ifPresent(family -> {
                        family.revokeToken(hashedToken, "Refresh token expired", now);
                        refreshTokenFamilyRepository.save(family);
                    }));
            throw new BadCredentialsException("Refresh token is expired");
        }

        User user = userReadRepository.findById(existingToken.getUserId())
                .orElseThrow(() -> new BadCredentialsException("User associated with token not found"));

        if (!user.isEnabled()) {
            throw new BadCredentialsException("User account is disabled");
        }

        String newRawToken = TokenHash.generateOpaqueToken();
        String newHashedToken = TokenHash.hash(newRawToken);
        Instant newExpiresAt = now.plus(jwtProperties.refreshTokenTtl());

        try {
            transactionTemplate.executeWithoutResult(status -> {
                RefreshTokenFamily family = refreshTokenFamilyRepository.findById(existingToken.getFamilyId())
                        .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
                family.rotate(hashedToken, newHashedToken, newExpiresAt, now);
                refreshTokenFamilyRepository.save(family);
            });
        } catch (ConflictException ex) {
            log.warn("Concurrent refresh token rotation detected in family {}. Revoking family.", existingToken.getFamilyId());
            refreshTokenFamilyRepository.revokeFamilyInNewTransaction(
                    existingToken.getFamilyId(), "Concurrent refresh token rotation");
            throw new BadCredentialsException("Refresh token was previously revoked. Entire token family invalidated.");
        }

        String newAccessToken = jwtTokenIssuer.issueAccessToken(user.getId(), user.getRoles());
        return AuthTokensResponse.of(newAccessToken, newRawToken, jwtProperties.accessTokenTtl().toSeconds());
    }
}
