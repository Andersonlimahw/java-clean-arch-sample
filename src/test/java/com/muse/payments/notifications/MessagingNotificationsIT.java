package com.muse.payments.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.notifications.infrastructure.PaymentNotificationConsumer;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.shared.infrastructure.OutboxRelayService;
import org.awaitility.Awaitility;
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
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MessagingNotificationsIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PaymentNotificationConsumer notificationConsumer;

    private String authenticateUser(String email, String password) throws Exception {
        RegisterRequest register = new RegisterRequest(email, password, "Messaging Test User");
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
    @DisplayName("End-to-End Event Flow: PaymentCreated -> Outbox -> RabbitMQ -> Consumer -> Deduplicated Notification")
    void testEndToEndMessagingAndIdempotentConsumer() throws Exception {
        String token = authenticateUser("messaging_user@example.com", "Password123!");

        // 1. Create payment
        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(
                new BigDecimal("500.00"),
                "BRL",
                "Conference Ticket"
        );

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID paymentId = UUID.fromString(responseNode.get("id").asText());

        // 2. Trigger Outbox Relay manually to guarantee immediate publication in test
        int relayed = outboxRelayService.relayPendingEvents();
        assertThat(relayed).isGreaterThanOrEqualTo(1);

        // 3. Verify Outbox record is marked published
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NOT NULL",
                Integer.class,
                paymentId
        );
        assertThat(pendingCount).isEqualTo(1);

        // 4. Await consumer processing via RabbitMQ async delivery
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    long notifications = notificationRepository.countByPaymentId(paymentId);
                    assertThat(notifications).isEqualTo(1L);
                });

        // 5. Query user notifications via HTTP API
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$[0].title").value("Payment Received"))
                .andExpect(jsonPath("$[0].type").value("PAYMENT_CREATED"));

        // 6. Idempotency test: Re-deliver the same event to verify deduplication via processed_events
        String rawOutboxPayload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?",
                String.class,
                paymentId
        );
        UUID outboxEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ?",
                UUID.class,
                paymentId
        );

        // Execute consumer again with duplicate eventId
        notificationConsumer.onPaymentCreated(rawOutboxPayload, outboxEventId.toString(), "test-corr-id");

        // Notification count must remain strictly 1
        long countAfterDuplicate = notificationRepository.countByPaymentId(paymentId);
        assertThat(countAfterDuplicate).isEqualTo(1L);

        // Processed events table has exactly 1 entry for this consumer and eventId
        Integer processedEventsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer = 'PaymentNotificationConsumer' AND event_id = ?",
                Integer.class,
                outboxEventId
        );
        assertThat(processedEventsCount).isEqualTo(1);
    }
}
