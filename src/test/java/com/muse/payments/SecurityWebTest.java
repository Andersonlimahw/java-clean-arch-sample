package com.muse.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.security.CurrentUser;
import com.muse.payments.shared.security.JwtTokenIssuer;
import io.swagger.v3.oas.annotations.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityWebTest extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Health endpoint is public and returns 200")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    @DisplayName("Protected endpoint without token returns 401 ProblemDetail")
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.title", is("Unauthorized")))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("Echoes incoming X-Correlation-Id")
    void echoesCorrelationId() throws Exception {
        String testCorrelationId = "custom-corr-id-999";

        mockMvc.perform(get("/api/v1/test/protected")
                        .header("X-Correlation-Id", testCorrelationId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", testCorrelationId))
                .andExpect(jsonPath("$.correlationId", is(testCorrelationId)));
    }

    @Test
    @DisplayName("Protected endpoint with valid JWT returns 200 and resolves CurrentUser")
    void protectedEndpointWithValidJwtReturns200() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenIssuer.issueAccessToken(userId, List.of("USER"));

        mockMvc.perform(get("/api/v1/test/protected")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(userId.toString())))
                .andExpect(jsonPath("$.roles[0]", is("USER")));
    }

    @Test
    @DisplayName("Payment list resolves CurrentUser from bearer token without a request body")
    void paymentListUsesBearerTokenWithoutCurrentUserBody() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenIssuer.issueAccessToken(userId, List.of("USER"));

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("OpenAPI does not describe CurrentUser as request input")
    void openApiDoesNotExposeCurrentUserAsRequestInput() throws Exception {
        String response = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode openApi = objectMapper.readTree(response);
        JsonNode paths = openApi.path("paths");

        var operationsWithCurrentUser = List.of(
                paths.path("/api/v1/payments").path("post"),
                paths.path("/api/v1/payments").path("get"),
                paths.path("/api/v1/payments/{id}").path("get"),
                paths.path("/api/v1/payments/{id}/review").path("post"),
                paths.path("/api/v1/payments/{id}/confirm").path("post"),
                paths.path("/api/v1/payments/{id}/cancel").path("post"),
                paths.path("/api/v1/notifications").path("get")
        );

        assertThat(openApi.toString()).doesNotContain("CurrentUser", "currentUser");
        assertThat(openApi.path("security").toString()).contains("BearerAuth");
        operationsWithCurrentUser.forEach(operation -> {
            assertThat(operation.isMissingNode()).isFalse();
            assertThat(operation.path("parameters").toString())
                    .doesNotContain("\"name\":\"currentUser\"", "\"name\":\"userId\"", "\"name\":\"roles\"");
        });
        assertThat(paths.path("/api/v1/payments").path("post")
                .path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/CreatePaymentRequest");
    }

    @TestConfiguration
    static class TestControllerConfig {
        @RestController
        @RequestMapping("/api/v1/test")
        static class TestSecurityController {
            @GetMapping("/protected")
            public ResponseEntity<Map<String, Object>> getProtected(@Parameter(hidden = true) CurrentUser currentUser) {
                return ResponseEntity.ok(Map.of(
                        "userId", currentUser.userId().toString(),
                        "roles", currentUser.roles()
                ));
            }
        }
    }
}
