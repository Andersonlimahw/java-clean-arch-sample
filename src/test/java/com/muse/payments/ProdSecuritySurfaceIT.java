package com.muse.payments;

import com.muse.payments.shared.security.JwtTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles({"test", "prod"})
class ProdSecuritySurfaceIT extends TestcontainersBase {

    private static final KeyPair PROD_RSA = generateKeyPair();

    @DynamicPropertySource
    static void prodRequiredProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.private-key-pem", () -> "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(PROD_RSA.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----");
        registry.add("app.jwt.public-key-pem", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(PROD_RSA.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----");
        registry.add("app.fraud-screening.base-url", () -> "https://fraud.internal.example.com");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Test
    @DisplayName("Prod keeps health probes public while locking down Prometheus, metrics, OpenAPI, and Swagger UI")
    void prodLocksDownDocsAndMetricsWhileKeepingProbesPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        for (String restricted : List.of("/actuator/prometheus", "/actuator/metrics", "/v3/api-docs", "/swagger-ui.html")) {
            mockMvc.perform(get(restricted))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        String userToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        String adminToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("ADMIN"));
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
