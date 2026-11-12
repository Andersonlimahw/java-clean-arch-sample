package com.muse.payments.shared.infrastructure.observability;

import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Queue depth for the notifications queue and its DLQ.
 * RabbitMQ remains the transport; these gauges are for alerts, not for business decisions.
 */
@Component
public class QueueDepthMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthMetrics.class);

    public QueueDepthMetrics(MeterRegistry meterRegistry, AmqpAdmin amqpAdmin) {
        register(meterRegistry, amqpAdmin, RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS, "main");
        register(meterRegistry, amqpAdmin, RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ, "dlq");
    }

    private static void register(MeterRegistry meterRegistry, AmqpAdmin amqpAdmin, String queue, String role) {
        Gauge.builder("payments.queue.messages", amqpAdmin, admin -> depth(admin, queue))
                .description("Ready messages on a payments RabbitMQ queue")
                .tag("queue", queue)
                .tag("role", role)
                .register(meterRegistry);
    }

    private static double depth(AmqpAdmin amqpAdmin, String queue) {
        try {
            Properties properties = amqpAdmin.getQueueProperties(queue);
            if (properties == null) {
                log.warn("Queue depth unavailable for {}: queue is missing or the broker returned no properties", queue);
                return Double.NaN;
            }
            Object count = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            if (count instanceof Number number) {
                return number.doubleValue();
            }
            return Double.NaN;
        } catch (RuntimeException ex) {
            log.warn("Queue depth query failed for {}: {}", queue, ex.getMessage());
            return Double.NaN;
        }
    }
}
