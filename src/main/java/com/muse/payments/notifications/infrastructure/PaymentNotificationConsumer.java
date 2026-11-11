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

    @RabbitListener(queues = RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS)
    @Transactional
    public void onPaymentCreated(
            @Payload String payloadJson,
            @Header(name = "X-Event-Id", required = false) String eventIdHeader,
            @Header(name = "X-Correlation-Id", required = false) String correlationId
    ) {
        if (correlationId != null && !correlationId.isBlank()) {
            CorrelationContext.set(correlationId);
        }

        try {
            Map<String, Object> data = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            UUID eventId = resolveEventId(eventIdHeader, data);

            boolean isNewEvent = processedEventStore.tryMarkProcessed(CONSUMER_NAME, eventId);
            if (!isNewEvent) {
                log.info("Duplicate event {} already processed by {}. Discarding duplicate.", eventId, CONSUMER_NAME);
                return;
            }

            Object paymentIdObj = data.get("paymentId");
            Object userIdObj = data.get("userId");
            Object amountObj = data.get("amount");
            Object currencyObj = data.get("currency");

            if (paymentIdObj == null || userIdObj == null || amountObj == null || currencyObj == null) {
                log.warn("Invalid PaymentCreated payload missing required attributes: {}. Discarding message.", payloadJson);
                return;
            }

            UUID paymentId = UUID.fromString(paymentIdObj.toString());
            UUID userId = UUID.fromString(userIdObj.toString());
            BigDecimal amount = new BigDecimal(amountObj.toString());
            String currency = currencyObj.toString();

            Notification notification = Notification.paymentCreated(userId, paymentId, amount, currency);
            notificationRepository.save(notification);

            log.info("Created notification {} for payment {} and user {}", notification.getId(), paymentId, userId);
        } catch (Exception ex) {
            log.error("Failed to process PaymentCreated notification message: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error processing PaymentCreated event", ex);
        } finally {
            CorrelationContext.clear();
        }
    }

    private UUID resolveEventId(String eventIdHeader, Map<String, Object> data) {
        if (eventIdHeader != null && !eventIdHeader.isBlank()) {
            try {
                return UUID.fromString(eventIdHeader);
            } catch (IllegalArgumentException ignored) {
            }
        }
        Object eventIdObj = data.get("eventId");
        if (eventIdObj != null) {
            return UUID.fromString(eventIdObj.toString());
        }
        return UUID.fromString(data.get("paymentId").toString());
    }
}
