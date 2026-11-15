package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.RefreshTokenFamily;
import com.muse.payments.identity.domain.RefreshTokenFamilyRepository;
import com.muse.payments.identity.domain.User;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.security.JwtProperties;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Service
public class LoginCommandHandler implements CommandHandler<LoginCommand, AuthTokensResponse> {

    private final UserReadRepository userReadRepository;
    private final UserCredentialsRepository userCredentialsRepository;
    private final RefreshTokenFamilyRepository refreshTokenFamilyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;
    private final String dummyPasswordHash;

    public LoginCommandHandler(
            UserReadRepository userReadRepository,
            UserCredentialsRepository userCredentialsRepository,
            RefreshTokenFamilyRepository refreshTokenFamilyRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenIssuer jwtTokenIssuer,
            JwtProperties jwtProperties
    ) {
        this.userReadRepository = Objects.requireNonNull(userReadRepository);
        this.userCredentialsRepository = Objects.requireNonNull(userCredentialsRepository);
        this.refreshTokenFamilyRepository = Objects.requireNonNull(refreshTokenFamilyRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.jwtTokenIssuer = Objects.requireNonNull(jwtTokenIssuer);
        this.jwtProperties = Objects.requireNonNull(jwtProperties);
        this.dummyPasswordHash = passwordEncoder.encode(TokenHash.generateOpaqueToken());
    }

    /**
     * Not one transaction on purpose. The user read and the refresh-token insert each run in
     * the repository's own transaction, so no database connection is held while BCrypt runs.
     */
    @Override
    public AuthTokensResponse handle(LoginCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        String normalizedEmail = command.email().trim().toLowerCase(Locale.ROOT);
        User user = userReadRepository.findByEmail(normalizedEmail).orElse(null);
        String passwordHash = user == null
                ? null
                : userCredentialsRepository.findPasswordHashByUserId(user.getId()).orElse(null);
        if (user == null || passwordHash == null) {
            // Spend the same BCrypt time as a real check so response time does not reveal
            // whether the email exists (OWASP Authentication Cheat Sheet).
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!passwordEncoder.matches(command.password(), passwordHash)) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isEnabled()) {
            throw new BadCredentialsException("User account is disabled");
        }

        String rawRefreshToken = TokenHash.generateOpaqueToken();
        String hashedToken = TokenHash.hash(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());

        RefreshTokenFamily family = RefreshTokenFamily.start(user.getId(), hashedToken, expiresAt);
        refreshTokenFamilyRepository.save(family);

        String accessToken = jwtTokenIssuer.issueAccessToken(user.getId(), user.getRoles());
        return AuthTokensResponse.of(accessToken, rawRefreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }
}
