package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.shared.infrastructure.idempotency.IdempotencyCleanup;
import com.muse.payments.shared.security.JwtTokenIssuer;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class IdempotencyAndAccessIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private IdempotencyCleanup idempotencyCleanup;

    @Test
    @DisplayName("a live idempotency key replays and an expired key starts a new payment")
    void liveKeyReplaysAndExpiredKeyIsNotReplayed() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenIssuer.issueAccessToken(userId, List.of("USER"));
        String key = "idem-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("10.00"), "BRL", "First charge");

        String firstId = createPayment(token, key, request);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstId));

        jdbcTemplate.update(
                "UPDATE idempotency_keys SET expires_at = ? WHERE user_id = ? AND key = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                userId,
                key
        );

        MvcResult expiredReplay = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();

        String secondId = objectMapper.readTree(expiredReplay.getResponse().getContentAsString()).get("id").asText();
        assertThat(secondId).isNotEqualTo(firstId);

        Integer payments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE user_id = ?",
                Integer.class,
                userId
        );
        assertThat(payments).isEqualTo(2);
    }

    @Test
    @DisplayName("cleanup deletes expired idempotency rows and leaves live rows in place")
    void cleanupRemovesExpiredRowsOnly() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenIssuer.issueAccessToken(userId, List.of("USER"));
        String liveKey = "live-" + UUID.randomUUID();
        String expiredKey = "expired-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("12.00"), "BRL", "Cleanup");

        createPayment(token, liveKey, request);
        createPayment(token, expiredKey, request);
        expireKey(userId, expiredKey);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(idempotencyCleanup::deleteExpired);
            Future<Integer> second = pool.submit(idempotencyCleanup::deleteExpired);
            int removed = first.get() + second.get();
            assertThat(removed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countKeys(userId, expiredKey)).isZero();
        assertThat(countKeys(userId, liveKey)).isEqualTo(1);
    }

    @Test
    @DisplayName("a forbidden role receives 403 and an allowed role can create and read its payment")
    void forbiddenRoleGets403AndAllowedRoleSucceeds() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String userToken = jwtTokenIssuer.issueAccessToken(ownerId, List.of("USER"));
        String auditorToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("AUDITOR"));
        String otherUserToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        String adminToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("ADMIN"));
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("15.50"), "BRL", "Role check");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + auditorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk());

        String paymentId = createPayment(userToken, "role-" + UUID.randomUUID(), request);

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId));

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId));

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/confirm")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    private String createPayment(String token, String key, CreatePaymentRequest request) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private void expireKey(UUID userId, String key) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET expires_at = ? WHERE user_id = ? AND key = ?",
                Timestamp.from(Instant.now().minusSeconds(120)),
                userId,
                key
        );
    }

    private int countKeys(UUID userId, String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND key = ?",
                Integer.class,
                userId,
                key
        );
        return count == null ? 0 : count;
    }
}
