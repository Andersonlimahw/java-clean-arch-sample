package com.muse.payments.shared.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
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
import java.util.Base64;
import java.util.List;

@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);
    private static final String DETERMINISTIC_KEY_ID = "payments-jwt-key-v1";

    @Bean
    public RSAKey rsaKey(JwtProperties properties, Environment env) {
        if (StringUtils.hasText(properties.privateKeyPem()) && StringUtils.hasText(properties.publicKeyPem())) {
            try {
                RSAPublicKey publicKey = parsePublicKey(properties.publicKeyPem());
                RSAPrivateKey privateKey = parsePrivateKey(properties.privateKeyPem());
                return new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(DETERMINISTIC_KEY_ID)
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse configured RSA JWT keys", e);
            }
        }

        boolean isLocalOrTest = env.acceptsProfiles(Profiles.of("local", "test", "default"));
        if (!isLocalOrTest) {
            throw new IllegalStateException("Production environment must define JWT_PRIVATE_KEY and JWT_PUBLIC_KEY. Ephemeral keys are forbidden in production.");
        }

        log.warn("No JWT keys provided. Generating an ephemeral 2048-bit RSA key pair for local/test use.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(DETERMINISTIC_KEY_ID)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate ephemeral RSA key pair", e);
        }
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
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();

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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure JwtDecoder", e);
        }
    }

    private RSAPublicKey parsePublicKey(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\n", "")
                .replace("\\r", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(clean);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
    }

    private RSAPrivateKey parsePrivateKey(String pem) throws Exception {
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
