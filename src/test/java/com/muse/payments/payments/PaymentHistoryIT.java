package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.domain.PaymentInitiatedEvent;
import com.muse.payments.shared.application.ProjectionRebuildPort;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("test")
class PaymentHistoryIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProjectionRebuildPort projectionRebuildPort;

    @Test
    @DisplayName("GET /api/v1/payments/{id}/history returns ordered events for owner, ADMIN, and FRAUD_REVIEWER, and 404 for another USER")
    void historyAuthorizationAndOrdering() throws Exception {
        String ownerToken = PaymentHttp.registerAndLogin(mockMvc, objectMapper, "hist_owner@example.com", "Password123!");
        String otherToken = PaymentHttp.registerAndLogin(mockMvc, objectMapper, "hist_other@example.com", "Password123!");
        String adminToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), Set.of("ADMIN"));
        String reviewerToken = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), Set.of("FRAUD_REVIEWER"));

        UUID paymentId = PaymentHttp.createdPaymentUuid(
                mockMvc, objectMapper, ownerToken, new CreatePaymentRequest(new BigDecimal("42.00"), "BRL", "History test"));

        mockMvc.perform(post("/api/v1/payments/{id}/confirm", paymentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            MvcResult res = mockMvc.perform(get("/api/v1/payments/{id}/history", paymentId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
            assertThat(body.get("items")).hasSize(2);
            assertThat(body.get("items").get(0).get("eventType").asText()).isEqualTo("payments.payment-initiated");
            assertThat(body.get("items").get(0).get("status").asText()).isEqualTo("PENDING");
            assertThat(body.get("items").get(1).get("eventType").asText()).isEqualTo("payments.payment-completed");
            assertThat(body.get("items").get(1).get("status").asText()).isEqualTo("COMPLETED");
        });

        mockMvc.perform(get("/api/v1/payments/{id}/history", paymentId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/v1/payments/{id}/history", paymentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/v1/payments/{id}/history", paymentId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Out-of-order concurrent commits do not skip events in payment_history")
    void outOfOrderCommitsDoNotSkipEvents() throws Exception {
        UUID paymentA = UUID.randomUUID();
        UUID paymentB = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        String sql = """
                INSERT INTO event_store (
                    stream_type, stream_id, stream_version, event_id, event_type, schema_version,
                    payload, metadata, occurred_at
                ) VALUES ('Payment', ?, 1, ?, 'payments.payment-initiated', 1, ?::jsonb, '{}'::jsonb, ?)
                """;
        String payloadA = objectMapper.writeValueAsString(
                new PaymentInitiatedEvent(paymentA, userId, new BigDecimal("10.00"), "BRL", "tx A"));
        String payloadB = objectMapper.writeValueAsString(
                new PaymentInitiatedEvent(paymentB, userId, new BigDecimal("20.00"), "BRL", "tx B"));

        try (Connection connA = dataSource.getConnection();
             Connection connB = dataSource.getConnection()) {
            connA.setAutoCommit(false);
            connB.setAutoCommit(false);

            // Transaction A inserts first (lower global_position) but does NOT commit yet
            try (PreparedStatement psA = connA.prepareStatement(sql)) {
                psA.setObject(1, paymentA);
                psA.setObject(2, UUID.randomUUID());
                psA.setString(3, payloadA);
                psA.setTimestamp(4, Timestamp.from(now));
                psA.executeUpdate();
            }

            // Transaction B inserts second (higher global_position) and commits first
            try (PreparedStatement psB = connB.prepareStatement(sql)) {
                psB.setObject(1, paymentB);
                psB.setObject(2, UUID.randomUUID());
                psB.setString(3, payloadB);
                psB.setTimestamp(4, Timestamp.from(now));
                psB.executeUpdate();
            }
            connB.commit();

            // Wait a couple of poll cycles while A is still open: B must NOT advance checkpoint past A
            Thread.sleep(600);
            Integer countAWhileOpen = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_history WHERE payment_id = ?", Integer.class, paymentA);
            assertThat(countAWhileOpen).isZero();

            // Now commit A
            connA.commit();
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer countA = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_history WHERE payment_id = ?", Integer.class, paymentA);
            Integer countB = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_history WHERE payment_id = ?", Integer.class, paymentB);
            assertThat(countA).isEqualTo(1);
            assertThat(countB).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Projection rebuild produces an identical payment_history table and OpenAPI hides CurrentUser")
    void rebuildAndOpenApiAndPrometheus() throws Exception {
        String token = PaymentHttp.registerAndLogin(mockMvc, objectMapper, "hist_rebuild@example.com", "Password123!");
        UUID paymentId = PaymentHttp.createdPaymentUuid(
                mockMvc, objectMapper, token, new CreatePaymentRequest(new BigDecimal("15.00"), "BRL", "Rebuild test"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_history WHERE payment_id = ?", Integer.class, paymentId);
            assertThat(count).isEqualTo(1);
        });

        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "SELECT payment_id, stream_version, event_type, status_after, actor_id, reason, occurred_at "
                        + "FROM payment_history ORDER BY payment_id, stream_version");

        projectionRebuildPort.rebuild("payment_history");

        List<Map<String, Object>> after = jdbcTemplate.queryForList(
                "SELECT payment_id, stream_version, event_type, status_after, actor_id, reason, occurred_at "
                        + "FROM payment_history ORDER BY payment_id, stream_version");
        assertThat(after).isEqualTo(before);

        MvcResult apiDocs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        String apiDocsJson = apiDocs.getResponse().getContentAsString();
        assertThat(apiDocsJson).contains("/api/v1/payments/{id}/history");
        assertThat(apiDocsJson).doesNotContain("currentUser");
        assertThat(apiDocsJson).doesNotContain("CurrentUser");

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("projection_lag"));
    }
}
