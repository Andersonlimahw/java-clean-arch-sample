package com.muse.payments.notifications.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.shared.infrastructure.ProcessedEventStore;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import com.muse.payments.shared.web.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class PaymentNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotificationConsumer.class);
    private static final String CONSUMER_NAME = "PaymentNotificationConsumer";

    private final NotificationRepository notificationRepository;
    private final ProcessedEventStore processedEventStore;
    private final ObjectMapper objectMapper;

    public PaymentNotificationConsumer(
            NotificationRepository notificationRepository,
            ProcessedEventStore processedEventStore,
            ObjectMapper objectMapper
    ) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository);
        this.processedEventStore = Objects.requireNonNull(processedEventStore);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_PAYMENT_NOTIFICATIONS)
    @Transactional
    public void onPaymentEvent(
            @Payload String payloadJson,
            @Header(name = "X-Event-Id", required = false) String eventIdHeader,
            @Header(name = "X-Event-Type", required = false) String eventTypeHeader,
            @Header(name = "X-Correlation-Id", required = false) String correlationId
    ) {
        if (correlationId != null && !correlationId.isBlank()) {
            CorrelationContext.set(correlationId);
        }

        try {
            Map<String, Object> data = readPayload(payloadJson);
            UUID eventId = resolveEventId(eventIdHeader, data);
            if (eventTypeHeader == null || eventTypeHeader.isBlank()) {
                throw new AmqpRejectAndDontRequeueException("Permanent failure: missing event type");
            }
            Notification notification = toNotification(eventTypeHeader, data, eventId);

            boolean isNewEvent = processedEventStore.tryMarkProcessed(CONSUMER_NAME, eventId);
            if (!isNewEvent) {
                log.info("Duplicate event {} already processed by {}. Discarding duplicate.", eventId, CONSUMER_NAME);
                return;
            }

            notificationRepository.save(notification);

            log.info("Created {} notification {} for payment {} and user {}",
                    notification.getType(), notification.getId(), notification.getPaymentId(), notification.getUserId());
        } catch (AmqpRejectAndDontRequeueException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to process payment notification message: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error processing payment event", ex);
        } finally {
            CorrelationContext.clear();
        }
    }

    private Map<String, Object> readPayload(String payloadJson) {
        try {
            Map<String, Object> data = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            if (data == null) {
                throw new AmqpRejectAndDontRequeueException("Permanent failure: payload is not a JSON object");
            }
            return data;
        } catch (AmqpRejectAndDontRequeueException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AmqpRejectAndDontRequeueException("Permanent failure: payload is not a JSON object", ex);
        }
    }

    private Notification toNotification(String eventType, Map<String, Object> data, UUID sourceEventId) {
        UUID paymentId = requiredUuid(data, "paymentId");
        UUID userId = requiredUuid(data, "userId");
        return switch (eventType) {
            case "PaymentCreated" -> Notification.paymentCreated(
                    userId,
                    paymentId,
                    requiredDecimal(data, "amount"),
                    requiredText(data, "currency"),
                    sourceEventId
            );
            case "PaymentHeldForReview" -> Notification.paymentHeldForReview(
                    userId,
                    paymentId,
                    requiredDecimal(data, "amount"),
                    requiredText(data, "currency"),
                    sourceEventId
            );
            case "PaymentCompleted" -> Notification.paymentCompleted(userId, paymentId, sourceEventId);
            case "PaymentCancelled" -> Notification.paymentCancelled(
                    userId,
                    paymentId,
                    data.get("reason") == null ? "" : data.get("reason").toString(),
                    sourceEventId
            );
            default -> throw new AmqpRejectAndDontRequeueException("Permanent failure: unsupported event type " + eventType);
        };
    }

    private UUID requiredUuid(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Permanent failure: missing " + field);
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            throw new AmqpRejectAndDontRequeueException("Permanent failure: invalid " + field, ex);
        }
    }

    private BigDecimal requiredDecimal(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Permanent failure: missing " + field);
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            throw new AmqpRejectAndDontRequeueException("Permanent failure: invalid " + field, ex);
        }
    }

    private String requiredText(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Permanent failure: missing " + field);
        }
        return value.toString();
    }

    private UUID resolveEventId(String eventIdHeader, Map<String, Object> data) {
        UUID fromHeader = parseUuidOrNull(eventIdHeader);
        if (fromHeader != null) {
            return fromHeader;
        }
        Object eventIdObj = data.get("eventId");
        if (eventIdObj != null) {
            UUID fromBody = parseUuidOrNull(eventIdObj.toString());
            if (fromBody != null) {
                return fromBody;
            }
        }
        throw new AmqpRejectAndDontRequeueException("Permanent failure: event id is missing or invalid");
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
