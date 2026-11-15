package com.muse.payments.shared.security;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
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

    private static final JwsHeader RS256_HEADER = JwsHeader.with(SignatureAlgorithm.RS256).build();

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final List<String> audience;

    public JwtTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder);
        this.properties = Objects.requireNonNull(properties);
        this.audience = List.of(properties.audience());
    }

    public String issueAccessToken(UUID userId, Collection<String> roles) {
        Objects.requireNonNull(userId, "userId must not be null");

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        List<String> normalizedRoles = roles == null ? List.of() : List.copyOf(roles);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(audience)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("typ", "access")
                .claim("roles", normalizedRoles)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(RS256_HEADER, claims)).getTokenValue();
    }
}
