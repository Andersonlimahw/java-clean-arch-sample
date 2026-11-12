package com.muse.payments.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.notifications.infrastructure.NotificationJpaAdapter;
import com.muse.payments.notifications.infrastructure.PaymentNotificationConsumer;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.shared.infrastructure.OutboxRelayService;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MessagingNotificationsIT.SaveFailureConfig.class)
class MessagingNotificationsIT extends TestcontainersBase {

    private static final AtomicInteger SAVE_FAILURES = new AtomicInteger();
    private final Map<String, Message> heldDeadLetters = new HashMap<>();

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
        publishOutbox(paymentId, "PaymentCreated");

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
        notificationConsumer.onPaymentEvent(rawOutboxPayload, outboxEventId.toString(), "PaymentCreated", "test-corr-id");

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
        publishOutbox(completedPaymentId, "PaymentCreated");
        awaitNotificationType(completedPaymentId, "PAYMENT_CREATED");

        mockMvc.perform(post("/api/v1/payments/" + completedPaymentId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        publishOutbox(completedPaymentId, "PaymentCompleted");
        awaitNotificationType(completedPaymentId, "PAYMENT_COMPLETED");

        String completedPayload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentCompleted'",
                String.class,
                completedPaymentId
        );
        UUID completedEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentCompleted'",
                UUID.class,
                completedPaymentId
        );
        notificationConsumer.onPaymentEvent(completedPayload, completedEventId.toString(), "PaymentCompleted", "test-corr-id");
        awaitNotificationType(completedPaymentId, "PAYMENT_COMPLETED");

        UUID cancelledPaymentId = createPayment(token, "18.50", "Cancellation flow");
        publishOutbox(cancelledPaymentId, "PaymentCreated");

        mockMvc.perform(post("/api/v1/payments/" + cancelledPaymentId + "/cancel")
                        .param("reason", "customer request")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        publishOutbox(cancelledPaymentId, "PaymentCancelled");
        awaitNotificationType(cancelledPaymentId, "PAYMENT_CANCELLED");
        String cancelMessage = jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE payment_id = ? AND type = 'PAYMENT_CANCELLED'",
                String.class,
                cancelledPaymentId
        );
        assertThat(cancelMessage).contains("customer request");
        assertConcurrentRelayPublishesOnce(token);
    }

    private void assertConcurrentRelayPublishesOnce(String token) throws Exception {
        String probeName = "payments.probe." + UUID.randomUUID();
        Queue probe = QueueBuilder.durable(probeName).build();
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe)
                .to(new TopicExchange(RabbitMqConfig.EXCHANGE_PAYMENTS))
                .with(RabbitMqConfig.ROUTING_KEY_PAYMENT_CREATED));
        try {
            UUID paymentId = createPayment(token, "11.00", "Single publish");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = pool.submit(outboxRelayService::relayPendingEvents);
                Future<Integer> second = pool.submit(outboxRelayService::relayPendingEvents);
                assertThat(first.get(30, TimeUnit.SECONDS)).isNotNull();
                assertThat(second.get(30, TimeUnit.SECONDS)).isNotNull();
            } finally {
                pool.shutdownNow();
            }

            assertPublishedOnce(paymentId, "PaymentCreated");
            int copies = 0;
            Message message;
            while ((message = rabbitTemplate.receive(probeName, 1000)) != null) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                if (body.contains(paymentId.toString())) {
                    copies++;
                }
            }
            assertThat(copies).isEqualTo(1);
        } finally {
            amqpAdmin.deleteQueue(probeName);
        }
    }

    @Test
    @DisplayName("An unroutable outbox event stays unpublished and backs off")
    void testUnroutableEventIsNotMarkedPublished() {
        UUID id = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload, headers,
                    occurred_at, attempts, next_attempt_at
                ) VALUES (
                    ?, 'Payment', ?, 'NotRoutable', CAST(? AS jsonb), CAST('{}' AS jsonb),
                    NOW(), 0, NOW()
                )
                """,
                id,
                aggregateId,
                "{\"paymentId\":\"" + aggregateId + "\"}"
        );

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    outboxRelayService.relayPendingEvents();
                    assertThat(outboxAttemptsById(id)).isEqualTo(1);
                });

        assertThat(publishedAt(id)).isNull();
        assertThat(lastError(id)).containsIgnoringCase("returned");
        assertThat(nextAttemptAt(id)).isAfter(Instant.now().plusSeconds(4));
    }

    @Test
    @DisplayName("A permanently invalid message is dead-lettered without the retry budget")
    void testPermanentFailureGoesToDlqImmediately() {
        amqpAdmin.purgeQueue(RabbitMqConfig.QUEUE_PAYMENT_NOTIFICATIONS_DLQ);
        UUID eventId = UUID.randomUUID();
        long started = System.nanoTime();
        String marker = sendToNotifications("{bad", eventId.toString(), "PaymentCreated");

        Message dead = awaitDeadLetter(marker, Duration.ofSeconds(5));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(dead).isNotNull();
        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains("{bad");
        assertThat(dead.getMessageProperties().getHeaders()).containsEntry("X-Event-Id", eventId.toString());
        assertThat(processedCount(eventId)).isZero();
    }

    @Test
    @DisplayName("Missing event identity is rejected and does not consume the payment id")
    void testMissingEventIdDoesNotCollapseLifecycleEvents() throws Exception {
        amqpAdmin.purgeQueue(RabbitMqConfig.QUEUE_PAYMENT_NOTIFICATIONS_DLQ);
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String created = objectMapper.writeValueAsString(Map.of(
                "paymentId", paymentId.toString(),
                "userId", userId.toString(),
                "amount", "10.00",
                "currency", "BRL"
        ));
        String completed = objectMapper.writeValueAsString(Map.of(
                "paymentId", paymentId.toString(),
                "userId", userId.toString()
        ));

        String missingCreated = sendToNotifications(created, null, "PaymentCreated");
        String missingCompleted = sendToNotifications(completed, null, "PaymentCompleted");
        UUID typedButMissingId = UUID.randomUUID();
        String missingType = sendToNotifications(completed, typedButMissingId.toString(), null);
        UUID unknownTypeId = UUID.randomUUID();
        String unknownType = sendToNotifications(completed, unknownTypeId.toString(), "PaymentRefunded");

        long started = System.nanoTime();
        assertThat(awaitDeadLetter(missingCreated, Duration.ofSeconds(5))).isNotNull();
        assertThat(awaitDeadLetter(missingCompleted, Duration.ofSeconds(5))).isNotNull();
        assertThat(awaitDeadLetter(missingType, Duration.ofSeconds(5))).isNotNull();
        assertThat(awaitDeadLetter(unknownType, Duration.ofSeconds(5))).isNotNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);

        assertThat(notificationCount(paymentId, "PAYMENT_CREATED")).isZero();
        assertThat(notificationCount(paymentId, "PAYMENT_COMPLETED")).isZero();
        assertThat(processedCount(paymentId)).isZero();
        assertThat(processedCount(typedButMissingId)).isZero();
        assertThat(processedCount(unknownTypeId)).isZero();

        UUID realEventId = UUID.randomUUID();
        sendToNotifications(completed, realEventId.toString(), "PaymentCompleted");
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(notificationCount(paymentId, "PAYMENT_COMPLETED")).isEqualTo(1));
        assertThat(processedCount(realEventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("A transient consumer failure is retried and then dead-lettered")
    void testTransientFailureRetriesThenDeadLetters() throws Exception {
        SAVE_FAILURES.set(20);
        try {
            amqpAdmin.purgeQueue(RabbitMqConfig.QUEUE_PAYMENT_NOTIFICATIONS_DLQ);
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of(
                    "paymentId", paymentId.toString(),
                    "userId", UUID.randomUUID().toString(),
                    "amount", "10.00",
                    "currency", "BRL"
            ));
            long started = System.nanoTime();
            String marker = sendToNotifications(body, eventId.toString(), "PaymentCreated");

            Message dead = awaitDeadLetter(marker, Duration.ofSeconds(40));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(dead).isNotNull();
            assertThat(elapsedMs).isGreaterThan(6_500);
            assertThat(notificationCount(paymentId, "PAYMENT_CREATED")).isZero();
            assertThat(processedCount(eventId)).isZero();

            assertThat(dead.getMessageProperties().getHeaders()).containsEntry("X-Event-Id", eventId.toString());
        } finally {
            SAVE_FAILURES.set(0);
        }
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

    private void publishOutbox(UUID aggregateId, String eventType) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    outboxRelayService.relayPendingEvents();
                    assertPublishedOnce(aggregateId, eventType);
                });
    }

    private void awaitNotificationType(UUID paymentId, String type) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(notificationCount(paymentId, type)).isEqualTo(1));
    }

    private String sendToNotifications(String body, String eventId, String eventType) {
        String marker = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend("", RabbitMqConfig.QUEUE_PAYMENT_NOTIFICATIONS, body, message -> {
            message.getMessageProperties().setHeader("X-Test-Marker", marker);
            if (eventId != null) {
                message.getMessageProperties().setHeader("X-Event-Id", eventId);
            }
            if (eventType != null) {
                message.getMessageProperties().setHeader("X-Event-Type", eventType);
            }
            return message;
        });
        return marker;
    }

    private Message awaitDeadLetter(String marker, Duration timeout) {
        Message held = heldDeadLetters.remove(marker);
        if (held != null) {
            return held;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            Message message = rabbitTemplate.receive(
                    RabbitMqConfig.QUEUE_PAYMENT_NOTIFICATIONS_DLQ,
                    Math.max(1, Math.min(remainingMs, 200))
            );
            if (message == null) {
                continue;
            }
            String found = String.valueOf(message.getMessageProperties().getHeaders().get("X-Test-Marker"));
            if (marker.equals(found)) {
                return message;
            }
            if (!"null".equals(found)) {
                heldDeadLetters.put(found, message);
            }
        }
        return null;
    }

    private int notificationCount(UUID paymentId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE payment_id = ? AND type = ?",
                Integer.class,
                paymentId,
                type
        );
        return count == null ? 0 : count;
    }

    private int processedCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer = 'PaymentNotificationConsumer' AND event_id = ?",
                Integer.class,
                eventId
        );
        return count == null ? 0 : count;
    }

    private int outboxAttempts(UUID aggregateId, String eventType) {
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                aggregateId,
                eventType
        );
        return attempts == null ? -1 : attempts;
    }

    private int outboxAttemptsById(UUID id) {
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_events WHERE id = ?",
                Integer.class,
                id
        );
        return attempts == null ? -1 : attempts;
    }

    private void assertPublishedOnce(UUID aggregateId, String eventType) {
        Integer published = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL",
                Integer.class,
                aggregateId,
                eventType
        );
        assertThat(published).isEqualTo(1);
        assertThat(outboxAttempts(aggregateId, eventType)).isZero();
    }

    private Instant publishedAt(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE id = ?",
                Instant.class,
                id
        );
    }

    private String lastError(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_events WHERE id = ?",
                String.class,
                id
        );
    }

    private Instant nextAttemptAt(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM outbox_events WHERE id = ?",
                Instant.class,
                id
        );
    }

    @TestConfiguration
    static class SaveFailureConfig {

        @Bean
        @Primary
        NotificationRepository flakyNotificationRepository(NotificationJpaAdapter delegate) {
            return new NotificationRepository() {
                @Override
                public Notification save(Notification notification) {
                    if (SAVE_FAILURES.get() > 0) {
                        SAVE_FAILURES.decrementAndGet();
                        throw new IllegalStateException("database unavailable");
                    }
                    return delegate.save(notification);
                }

                @Override
                public java.util.List<Notification> findByUserId(UUID userId) {
                    return delegate.findByUserId(userId);
                }

                @Override
                public long countByPaymentId(UUID paymentId) {
                    return delegate.countByPaymentId(paymentId);
                }
            };
        }
    }
}
