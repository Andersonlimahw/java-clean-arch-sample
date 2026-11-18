package com.muse.payments.shared.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    public RSAKey rsaKey(JwtProperties properties, Environment env) {
        String activeKeyId = properties.keyId();
        if (StringUtils.hasText(properties.privateKeyPem()) && StringUtils.hasText(properties.publicKeyPem())) {
            try {
                RSAPublicKey publicKey = parsePublicKey(properties.publicKeyPem());
                RSAPrivateKey privateKey = parsePrivateKey(properties.privateKeyPem());
                return new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(activeKeyId)
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse configured RSA JWT keys", e);
            }
        }

        if (!ephemeralKeysAllowed(env)) {
            throw new IllegalStateException("Production environment must define JWT_PRIVATE_KEY and JWT_PUBLIC_KEY. Ephemeral keys are forbidden in production.");
        }

        log.warn("No JWT keys provided. Generating an ephemeral 2048-bit RSA key pair for local, test, or non-production migration use.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(activeKeyId)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate ephemeral RSA key pair", e);
        }
    }

    /**
     * Ephemeral keys are allowed for local/test/default and for a migration process that
     * is not also on the {@code prod} profile. {@code prod} stays fail-closed even when the
     * migration profile is added on top, so a production pod never signs with a generated key.
     */
    static boolean ephemeralKeysAllowed(Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            return false;
        }
        return env.acceptsProfiles(Profiles.of("local", "test", "default", "migration"));
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey, JwtProperties properties) {
        try {
            List<JWK> verificationKeys = new ArrayList<>(2);
            verificationKeys.add(rsaKey.toPublicJWK());
            if (StringUtils.hasText(properties.previousPublicKeyPem())) {
                if (properties.keyId().equals(properties.previousKeyId())) {
                    throw new IllegalStateException("JWT_PREVIOUS_KEY_ID must differ from JWT_KEY_ID when JWT_PREVIOUS_PUBLIC_KEY is configured");
                }
                RSAPublicKey previousPublicKey = parsePublicKey(properties.previousPublicKeyPem());
                verificationKeys.add(new RSAKey.Builder(previousPublicKey)
                        .keyID(properties.previousKeyId())
                        .build());
            }

            DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> verificationSource = new ImmutableJWKSet<>(new JWKSet(verificationKeys));
            jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, verificationSource));
            NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);

            OAuth2TokenValidator<Jwt> timestampValidator = new JwtTimestampValidator();
            OAuth2TokenValidator<Jwt> issuerValidator = new JwtClaimValidator<String>(
                    JwtClaimNames.ISS,
                    iss -> iss != null && iss.equals(properties.issuer())
            );
            OAuth2TokenValidator<Jwt> audienceValidator = token -> {
                List<String> aud = token.getAudience();
                if (aud != null && aud.contains(properties.audience())) {
                    return OAuth2TokenValidatorResult.success();
                }
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience", "Token audience does not match expected audience", null));
            };
            OAuth2TokenValidator<Jwt> tokenTypeValidator = new JwtClaimValidator<String>(
                    "typ",
                    typ -> typ == null || "access".equals(typ)
            );

            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestampValidator, issuerValidator, audienceValidator, tokenTypeValidator));
            return decoder;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure JwtDecoder", e);
        }
    }

    static RSAPublicKey parsePublicKey(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\n", "")
                .replace("\\r", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(clean);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
    }

    static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replace("\\r", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(clean);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }
}
