package com.muse.payments.shared.security;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyConfigTest {

    private final JwtKeyConfig config = new JwtKeyConfig();

    @Test
    void ephemeralKeysAreAllowedOutsideProductionIncludingMigration() {
        MockEnvironment migration = new MockEnvironment();
        migration.setActiveProfiles("migration");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(migration)).isTrue();

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(local)).isTrue();

        assertThat(JwtKeyConfig.ephemeralKeysAllowed(new MockEnvironment())).isTrue();
    }

    @Test
    void productionStillRejectsEphemeralKeysWhenMigrationProfileIsAdded() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(production)).isFalse();

        MockEnvironment productionMigration = new MockEnvironment();
        productionMigration.setActiveProfiles("prod", "migration");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(productionMigration)).isFalse();
    }

    @Test
    void decoderAcceptsBothActiveAndPreviousKeyDuringRotationAndRejectsUnknownKey() throws Exception {
        KeyPair previousPair = generateRsa2048();
        KeyPair activePair = generateRsa2048();
        KeyPair unknownPair = generateRsa2048();

        JwtProperties previousProps = new JwtProperties(
                "https://api.payments.muse.com",
                "payments-api",
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                "key-v1",
                privatePem(previousPair),
                publicPem(previousPair),
                "key-v0",
                ""
        );
        RSAKey oldSigningKey = config.rsaKey(previousProps, prodEnv());
        JwtTokenIssuer oldIssuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(oldSigningKey))), previousProps);
        UUID userBeforeRotation = UUID.randomUUID();
        String preRotationToken = oldIssuer.issueAccessToken(userBeforeRotation, List.of("USER"));

        JwtProperties rotatedProps = new JwtProperties(
                "https://api.payments.muse.com",
                "payments-api",
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                "key-v2",
                privatePem(activePair),
                publicPem(activePair),
                "key-v1",
                publicPem(previousPair)
        );
        RSAKey activeSigningKey = config.rsaKey(rotatedProps, prodEnv());
        JwtEncoder rotatedEncoder = config.jwtEncoder(config.jwkSource(activeSigningKey));
        JwtDecoder rotatedDecoder = config.jwtDecoder(activeSigningKey, rotatedProps);
        JwtTokenIssuer rotatedIssuer = new JwtTokenIssuer(rotatedEncoder, rotatedProps);

        UUID userAfterRotation = UUID.randomUUID();
        String postRotationToken = rotatedIssuer.issueAccessToken(userAfterRotation, List.of("ADMIN"));

        Jwt decodedPre = rotatedDecoder.decode(preRotationToken);
        assertThat(decodedPre.getSubject()).isEqualTo(userBeforeRotation.toString());
        assertThat(decodedPre.getHeaders().get("kid")).isEqualTo("key-v1");

        Jwt decodedPost = rotatedDecoder.decode(postRotationToken);
        assertThat(decodedPost.getSubject()).isEqualTo(userAfterRotation.toString());
        assertThat(decodedPost.getHeaders().get("kid")).isEqualTo("key-v2");

        RSAKey rogueKey = new RSAKey.Builder((RSAPublicKey) unknownPair.getPublic())
                .privateKey((RSAPrivateKey) unknownPair.getPrivate())
                .keyID("key-rogue")
                .build();
        JwtTokenIssuer rogueIssuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(rogueKey))), rotatedProps);
        String rogueToken = rogueIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        assertThatThrownBy(() -> rotatedDecoder.decode(rogueToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsDuplicateActiveAndPreviousKeyIds() throws Exception {
        KeyPair pair = generateRsa2048();
        JwtProperties duplicateIds = new JwtProperties(
                "https://api.payments.muse.com",
                "payments-api",
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                "same-kid",
                privatePem(pair),
                publicPem(pair),
                "same-kid",
                publicPem(pair)
        );
        RSAKey active = config.rsaKey(duplicateIds, prodEnv());
        assertThatThrownBy(() -> config.jwtDecoder(active, duplicateIds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PREVIOUS_KEY_ID");
    }

    private static MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static KeyPair generateRsa2048() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    public static String publicPem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    public static String privatePem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }
}
