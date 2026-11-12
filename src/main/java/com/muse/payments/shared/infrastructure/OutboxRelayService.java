package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import com.muse.payments.shared.infrastructure.observability.W3cTraceContextPropagation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final W3cTraceContextPropagation traceContext;
    private final Timer publishLag;

    public OutboxRelayService(
            OutboxJpaRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            W3cTraceContextPropagation traceContext,
            MeterRegistry meterRegistry
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.traceContext = Objects.requireNonNull(traceContext);
        this.publishLag = Timer.builder("payments.outbox.publish.lag")
                .description("Time from occurred_at until the outbox row is published")
                .serviceLevelObjectives(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60))
                .register(meterRegistry);
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
            Map<String, Object> headers = readHeaders(event.getHeaders());
            String correlationId = correlationId(headers);

            try {
                traceContext.runWithParent(headers, () -> rabbitTemplate.convertAndSend(
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
                ));

                Instant publishedAt = Instant.now();
                event.setPublishedAt(publishedAt);
                recordPublishLag(event.getOccurredAt(), publishedAt);
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

    private void recordPublishLag(Instant occurredAt, Instant publishedAt) {
        if (occurredAt == null || publishedAt == null) {
            return;
        }
        Duration lag = Duration.between(occurredAt, publishedAt);
        publishLag.record(lag.isNegative() ? Duration.ZERO : lag);
    }

    private Map<String, Object> readHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse outbox headers JSON: {}", headersJson);
            return Map.of();
        }
    }

    private String correlationId(Map<String, Object> headers) {
        Object correlationId = headers.get("correlationId");
        if (correlationId != null && !correlationId.toString().isBlank()) {
            return correlationId.toString();
        }
        return UUID.randomUUID().toString();
    }
}
