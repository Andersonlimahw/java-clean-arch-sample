package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.payments.application.CreatePaymentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PaymentsIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String authenticateUser(String email, String password) throws Exception {
        RegisterRequest register = new RegisterRequest(email, password, "Test User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest(email, password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        AuthTokensResponse tokens = objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokensResponse.class);
        return tokens.accessToken();
    }

    @Test
    @DisplayName("Complete Payments Lifecycle: Creation, Outbox, Idempotent Replay, State Transitions, and Ownership Isolation")
    void fullPaymentLifecycle() throws Exception {
        String tokenUserA = authenticateUser("payments_user_a@example.com", "SecurePassword123!");
        String tokenUserB = authenticateUser("payments_user_b@example.com", "SecurePassword123!");

        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest createRequest = new CreatePaymentRequest(
                new BigDecimal("250.75"),
                "BRL",
                "Annual Subscription"
        );

        // 1. Missing Idempotency-Key header returns 400 Bad Request
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));

        // 2. Successful creation with Idempotency-Key returns 201 Created
        MvcResult createResult = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.amount").value(250.75))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String paymentId = responseNode.get("id").asText();

        // 3. Verify Transactional Outbox record exists for PaymentCreated
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentCreated'",
                Integer.class,
                UUID.fromString(paymentId)
        );
        assertThat(outboxCount).isEqualTo(1);

        // 4. Idempotent Replay: same key + same payload -> returns 201 with Idempotent-Replayed: true header
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.amount").value(250.75));

        // 5. Idempotency Conflict: same key + DIFFERENT payload -> returns 409 Conflict
        CreatePaymentRequest modifiedRequest = new CreatePaymentRequest(
                new BigDecimal("999.00"),
                "USD",
                "Different Description"
        );
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modifiedRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Idempotency key payload mismatch for key: " + idempotencyKey));

        // 6. Ownership isolation (anti-IDOR): User B attempting to read User A's payment receives 404
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));

        // 7. User A reads own payment -> 200 OK
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 8. User A lists own payments -> 200 OK
        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));

        // 9. Confirm Payment -> 200 OK with COMPLETED status
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/confirm")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(1));

        // Verify Outbox record for PaymentCompleted
        Integer completedOutboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentCompleted'",
                Integer.class,
                UUID.fromString(paymentId)
        );
        assertThat(completedOutboxCount).isEqualTo(1);

        // 10. Cannot cancel payment once COMPLETED -> 409 Conflict
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/cancel")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot cancel payment in status: COMPLETED"));
    }
}
