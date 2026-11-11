package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxJpaRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelayService(
            OutboxJpaRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEventEntity> pendingEvents = outboxRepository.findPendingForPublishing(Instant.now(), BATCH_SIZE);
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEventEntity event : pendingEvents) {
            String routingKey = resolveRoutingKey(event.getEventType());
            String correlationId = extractCorrelationId(event.getHeaders());

            try {
                rabbitTemplate.convertAndSend(
                        RabbitMqConfig.EXCHANGE_PAYMENTS,
                        routingKey,
                        event.getPayload(),
                        message -> {
                            var props = message.getMessageProperties();
                            props.setContentType("application/json");
                            props.setHeader("X-Correlation-Id", correlationId);
                            props.setHeader("X-Event-Id", event.getId().toString());
                            props.setHeader("X-Event-Type", event.getEventType());
                            props.setHeader("X-Aggregate-Type", event.getAggregateType());
                            props.setHeader("X-Aggregate-Id", event.getAggregateId().toString());
                            return message;
                        }
                );

                event.setPublishedAt(Instant.now());
                publishedCount++;
                log.debug("Published outbox event {} of type {} with routing key {}", event.getId(), event.getEventType(), routingKey);
            } catch (Exception ex) {
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastError(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());

                int jitterSeconds = ThreadLocalRandom.current().nextInt(1, 4);
                int backoffSeconds = (int) Math.min(300, Math.pow(2, attempts) * 2 + jitterSeconds);
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));

                log.warn("Failed to publish outbox event {}. Attempt {}. Next retry in {}s. Error: {}",
                        event.getId(), attempts, backoffSeconds, ex.getMessage());
            }
        }

        return publishedCount;
    }

    private String resolveRoutingKey(String eventType) {
        if (eventType == null) {
            return "payment.unknown";
        }
        return switch (eventType) {
            case "PaymentCreated" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_CREATED;
            case "PaymentCompleted" -> "payment.completed";
            case "PaymentCancelled" -> "payment.cancelled";
            default -> "payment." + eventType.toLowerCase();
        };
    }

    private String extractCorrelationId(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            Map<String, Object> headers = objectMapper.readValue(headersJson, new TypeReference<>() {});
            Object correlationId = headers.get("correlationId");
            if (correlationId != null && !correlationId.toString().isBlank()) {
                return correlationId.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse outbox headers JSON: {}", headersJson);
        }
        return UUID.randomUUID().toString();
    }
}
