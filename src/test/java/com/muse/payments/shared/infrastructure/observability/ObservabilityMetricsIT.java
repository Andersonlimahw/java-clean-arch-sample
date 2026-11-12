package com.muse.payments.shared.infrastructure.observability;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ObservabilityMetricsIT extends TestcontainersBase {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Outbox and queue gauges are registered and scrape without throwing")
    void exposesOutboxAndQueueGauges() {
        assertThat(meterRegistry.find("payments.outbox.pending").gauge()).isNotNull();
        assertThat(meterRegistry.find("payments.outbox.lag.seconds").gauge()).isNotNull();
        assertThat(meterRegistry.find("payments.outbox.attempts").gauge()).isNotNull();

        var dlq = meterRegistry.find("payments.queue.messages")
                .tag("queue", RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ)
                .tag("role", "dlq")
                .gauge();
        var main = meterRegistry.find("payments.queue.messages")
                .tag("queue", RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS)
                .tag("role", "main")
                .gauge();

        assertThat(dlq).isNotNull();
        assertThat(main).isNotNull();
        assertThat(meterRegistry.get("payments.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(0d);
        assertThat(dlq.value()).isNotNaN();
        assertThat(main.value()).isNotNaN();
    }
}
