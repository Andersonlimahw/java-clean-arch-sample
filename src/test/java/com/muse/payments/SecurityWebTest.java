package com.muse.payments;

import com.muse.payments.shared.security.CurrentUser;
import com.muse.payments.shared.security.JwtTokenIssuer;
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

    @TestConfiguration
    static class TestControllerConfig {
        @RestController
        @RequestMapping("/api/v1/test")
        static class TestSecurityController {
            @GetMapping("/protected")
            public ResponseEntity<Map<String, Object>> getProtected(CurrentUser currentUser) {
                return ResponseEntity.ok(Map.of(
                        "userId", currentUser.userId().toString(),
                        "roles", currentUser.roles()
                ));
            }
        }
    }
}
