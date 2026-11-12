package com.muse.payments.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import com.muse.payments.shared.security.JwtTokenIssuer;
import com.muse.payments.support.ControllableFraudScreening;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=1")
class FraudScreeningHttpIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private ControllableFraudScreening fraudScreening;

    @Test
    @DisplayName("A blocked screening does not hold the only database connection")
    void slowScreeningLeavesTheConnectionFree() throws Exception {
        fraudScreening.blockNextCall();
        String token = token(UUID.randomUUID(), "USER");
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("12.00"), "BRL", "Held connection");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<MvcResult> response = executor.submit(() -> create(token, "conn-" + UUID.randomUUID(), request));
            assertThat(fraudScreening.awaitEntered(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.queryForObject("select 1", Integer.class)).isEqualTo(1);
            fraudScreening.release();
            assertThat(response.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            fraudScreening.release();
            executor.close();
        }
    }

    @Test
    @DisplayName("The owner cannot review. Another admin can approve, then the owner confirms. An admin cannot review their own payment. Reject cancels.")
    void reviewIsNotAvailableToTheOwner() throws Exception {
        fraudScreening.setResult(FraudScreeningResult.underReview("provider down"));
        UUID ownerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String owner = token(ownerId, "USER");
        String admin = token(adminId, "ADMIN");
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("18.00"), "BRL", "Review me");
        String key = "held-" + UUID.randomUUID();
        String created = create(owner, key, request).getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/payments/" + id + "/review")
                        .header("Authorization", "Bearer " + owner)
                        .param("decision", "APPROVE"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/payments/" + id + "/confirm")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/payments/" + id + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .param("decision", "APPROVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mockMvc.perform(post("/api/v1/payments/" + id + "/confirm")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        String ownId = objectMapper.readTree(create(admin, "own-" + UUID.randomUUID(), request)
                .getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/payments/" + ownId + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .param("decision", "APPROVE"))
                .andExpect(status().isForbidden());

        String rejectId = objectMapper.readTree(create(owner, "reject-" + UUID.randomUUID(), request)
                .getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/payments/" + rejectId + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .param("decision", "REJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("FRAUD_REVIEWER can read a held payment by id, list held payments, and approve one they do not own")
    void fraudReviewerCanSeeAndReleaseAHeldPayment() throws Exception {
        fraudScreening.setResult(FraudScreeningResult.underReview("provider down"));
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        String owner = token(ownerId, "USER");
        String reviewer = token(reviewerId, "FRAUD_REVIEWER");
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("14.00"), "BRL", "Queue");
        String id = objectMapper.readTree(create(owner, "queue-" + UUID.randomUUID(), request)
                .getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/payments/" + id)
                        .header("Authorization", "Bearer " + reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        mockMvc.perform(get("/api/v1/payments/under-review")
                        .header("Authorization", "Bearer " + reviewer)
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(id)));

        mockMvc.perform(post("/api/v1/payments/" + id + "/review")
                        .header("Authorization", "Bearer " + reviewer)
                        .param("decision", "APPROVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        String heldReason = jdbcTemplate.queryForObject(
                "select held_reason from payments where id = ?",
                String.class,
                UUID.fromString(id));
        assertThat(heldReason).isEqualTo("provider down");
    }

    @Test
    @DisplayName("An explicit decline is 422 without the provider reason and the same key replays that response")
    void declineIsGenericAndIdempotent() throws Exception {
        fraudScreening.setResult(FraudScreeningResult.declined("stolen card"));
        String token = token(UUID.randomUUID(), "USER");
        String key = "declined-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("21.00"), "BRL", "No");

        MvcResult declined = create(token, key, request);
        assertThat(declined.getResponse().getStatus()).isEqualTo(422);
        assertThat(declined.getResponse().getContentAsString()).doesNotContain("stolen card");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Payment was declined by fraud screening."));
    }

    @Test
    @DisplayName("An expired processing lease can be used again")
    void expiredProcessingLeaseIsReclaimed() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = "orphan-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO idempotency_keys (key, user_id, request_hash, status, created_at, expires_at, lease_id)
                VALUES (?, ?, ?, 'PROCESSING', now() - interval '5 minutes', now() - interval '1 minute', ?)
                """, key, userId, "deadbeef", UUID.randomUUID());
        fraudScreening.setResult(FraudScreeningResult.clear());

        MvcResult created = create(
                token(userId, "USER"),
                key,
                new CreatePaymentRequest(new BigDecimal("9.00"), "BRL", "retry"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
    }

    private String token(UUID userId, String role) {
        return jwtTokenIssuer.issueAccessToken(userId, List.of(role));
    }

    private MvcResult create(String token, String key, CreatePaymentRequest request) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }
}
