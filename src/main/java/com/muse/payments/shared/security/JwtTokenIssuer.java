package com.muse.payments.shared.security;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class JwtTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public JwtTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder);
        this.properties = Objects.requireNonNull(properties);
    }

    public String issueAccessToken(UUID userId, Collection<String> roles) {
        Objects.requireNonNull(userId, "userId must not be null");

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        List<String> normalizedRoles = roles == null ? List.of() : List.copyOf(roles);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("typ", "access")
                .claim("roles", normalizedRoles)
                .build();

        JwsHeader header = JwsHeader.with(() -> "RS256").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
