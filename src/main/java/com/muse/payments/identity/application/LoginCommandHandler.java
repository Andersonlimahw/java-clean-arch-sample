package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshToken;
import com.muse.payments.identity.domain.RefreshTokenRepository;
import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.security.JwtProperties;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class LoginCommandHandler implements CommandHandler<LoginCommand, AuthTokensResponse> {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;

    public LoginCommandHandler(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenIssuer jwtTokenIssuer,
            JwtProperties jwtProperties
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.jwtTokenIssuer = Objects.requireNonNull(jwtTokenIssuer);
        this.jwtProperties = Objects.requireNonNull(jwtProperties);
    }

    @Override
    @Transactional
    public AuthTokensResponse handle(LoginCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        String normalizedEmail = command.email().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isEnabled()) {
            throw new BadCredentialsException("User account is disabled");
        }

        String rawRefreshToken = TokenHash.generateOpaqueToken();
        String hashedToken = TokenHash.hash(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());

        RefreshToken refreshToken = RefreshToken.create(user.getId(), hashedToken, expiresAt);
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtTokenIssuer.issueAccessToken(user.getId(), user.getRoles());
        return AuthTokensResponse.of(accessToken, rawRefreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }
}
