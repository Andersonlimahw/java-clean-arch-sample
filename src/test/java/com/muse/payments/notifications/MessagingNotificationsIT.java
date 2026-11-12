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
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import org.awaitility.Awaitility;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import java.nio.charset.StandardCharsets;
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

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

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

    @Test
    @DisplayName("PaymentCompleted and PaymentCancelled are routed and stored as notifications")
    void testCompletedAndCancelledNotifications() throws Exception {
        String token = authenticateUser("lifecycle_" + UUID.randomUUID() + "@example.com", "Password123!");

        UUID completedPaymentId = createPayment(token, "42.00", "Completion flow");
        assertThat(outboxRelayService.relayPendingEvents()).isGreaterThanOrEqualTo(1);
        awaitNotificationType(completedPaymentId, "PAYMENT_CREATED");

        mockMvc.perform(post("/api/v1/payments/" + completedPaymentId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(outboxRelayService.relayPendingEvents()).isGreaterThanOrEqualTo(1);
        awaitNotificationType(completedPaymentId, "PAYMENT_COMPLETED");

        UUID cancelledPaymentId = createPayment(token, "18.50", "Cancellation flow");
        assertThat(outboxRelayService.relayPendingEvents()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(post("/api/v1/payments/" + cancelledPaymentId + "/cancel")
                        .param("reason", "customer request")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(outboxRelayService.relayPendingEvents()).isGreaterThanOrEqualTo(1);
        awaitNotificationType(cancelledPaymentId, "PAYMENT_CANCELLED");
    }

    @Test
    @DisplayName("A permanently invalid message is dead-lettered and not retried forever")
    void testPermanentFailureGoesToDlq() {
        rabbitTemplate.send(
                "",
                RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS,
                MessageBuilder.withBody("{bad".getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setHeader("X-Event-Id", UUID.randomUUID().toString())
                        .setHeader("X-Event-Type", "PaymentCreated")
                        .build()
        );

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var properties = amqpAdmin.getQueueProperties(RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ);
                    assertThat(properties).isNotNull();
                    Object count = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                    assertThat(count).isInstanceOf(Number.class);
                    assertThat(((Number) count).intValue()).isGreaterThanOrEqualTo(1);
                });
    }

    private UUID createPayment(String token, String amount, String description) throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal(amount), "BRL", description);
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void awaitNotificationType(UUID paymentId, String type) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM notifications WHERE payment_id = ? AND type = ?",
                            Integer.class,
                            paymentId,
                            type
                    );
                    assertThat(count).isEqualTo(1);
                });
    }
}
