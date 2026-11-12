package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxJpaRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Duration confirmTimeout;

    public OutboxRelayService(
            OutboxJpaRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${app.messaging.confirm-timeout:5s}") Duration confirmTimeout
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.confirmTimeout = confirmTimeout != null ? confirmTimeout : Duration.ofSeconds(5);
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
                CorrelationData correlation = new CorrelationData(event.getId().toString());
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
                        },
                        correlation
                );
                awaitPublisherConfirm(correlation);
                event.setPublishedAt(Instant.now());
                publishedCount++;
                log.debug("Published outbox event {} of type {} with routing key {}", event.getId(), event.getEventType(), routingKey);
            } catch (Exception ex) {
                scheduleRetry(event, ex);
            }
        }

        return publishedCount;
    }

    private void awaitPublisherConfirm(CorrelationData correlation) throws Exception {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Publisher confirm timed out for " + correlation.getId(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for publisher confirm " + correlation.getId(), ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new IllegalStateException("Publisher confirm failed for " + correlation.getId(), cause);
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Broker returned unroutable event " + correlation.getId()
                    + ": " + correlation.getReturned().getReplyText());
        }
        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "missing confirm" : confirm.getReason();
            throw new IllegalStateException("Broker nacked event " + correlation.getId() + ": " + reason);
        }
    }

    private void scheduleRetry(OutboxEventEntity event, Exception ex) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        event.setLastError(message.length() > 500 ? message.substring(0, 500) : message);

        int jitterSeconds = ThreadLocalRandom.current().nextInt(1, 4);
        Duration delay = PublishRetryBackoff.delay(attempts, jitterSeconds);
        event.setNextAttemptAt(Instant.now().plus(delay));

        if (attempts >= 8) {
            log.error("Outbox event {} still unpublished after {} attempts. Next retry in {}s. Error: {}",
                    event.getId(), attempts, delay.toSeconds(), message);
        } else {
            log.warn("Failed to publish outbox event {}. Attempt {}. Next retry in {}s. Error: {}",
                    event.getId(), attempts, delay.toSeconds(), message);
        }
    }

    private String resolveRoutingKey(String eventType) {
        if (eventType == null) {
            return "payment.unknown";
        }
        return switch (eventType) {
            case "PaymentCreated" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_CREATED;
            case "PaymentCompleted" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_COMPLETED;
            case "PaymentCancelled" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_CANCELLED;
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
