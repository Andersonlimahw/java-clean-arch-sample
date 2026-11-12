package com.muse.payments.shared.infrastructure.observability;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentCommand;
import com.muse.payments.payments.application.CreatePaymentCommandHandler;
import com.muse.payments.payments.application.PaymentResponse;
import com.muse.payments.shared.infrastructure.OutboxEventEntity;
import com.muse.payments.shared.infrastructure.OutboxJpaRepository;
import com.muse.payments.shared.infrastructure.OutboxRelayService;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "management.tracing.enabled=true",
                "management.tracing.sampling.probability=1.0",
                "management.otlp.tracing.export.enabled=false"
        }
)
@Import(ObservabilityMetricsIT.TraceProbeConfig.class)
class ObservabilityMetricsIT extends TestcontainersBase {

    static final String TRACE_PROBE_QUEUE = "payments.trace.probe";

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OutboxJpaRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CreatePaymentCommandHandler createPaymentCommandHandler;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private Tracer tracer;

    @Autowired
    private TraceProbe traceProbe;

    @Test
    @DisplayName("Outbox and queue gauges are registered and scrape without throwing")
    void exposesOutboxAndQueueGauges() {
        assertThat(meterRegistry.find("payments.outbox.pending").gauge()).isNotNull();
        assertThat(meterRegistry.find("payments.outbox.lag.seconds").gauge()).isNotNull();
        assertThat(meterRegistry.find("payments.outbox.attempts").gauge()).isNotNull();
        assertThat(meterRegistry.find("payments.outbox.publish.lag").timer()).isNotNull();

        var dlq = queueGauge("dlq", RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ);
        var main = queueGauge("main", RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS);

        assertThat(dlq).isNotNull();
        assertThat(main).isNotNull();
        assertThat(gauge("payments.outbox.pending")).isGreaterThanOrEqualTo(0d);
        assertThat(dlq.value()).isNotNaN();
        assertThat(main.value()).isNotNaN();
    }

    @Test
    @DisplayName("Outbox gauges follow unpublished rows")
    void outboxGaugesFollowUnpublishedRows() {
        double pendingBefore = gauge("payments.outbox.pending");
        double attemptsBefore = gauge("payments.outbox.attempts");

        OutboxEventEntity row = new OutboxEventEntity(
                UUID.randomUUID(),
                "Payment",
                UUID.randomUUID(),
                "PaymentCreated",
                "{}",
                "{}",
                Instant.now().minusSeconds(5)
        );
        row.setAttempts(2);
        row.setNextAttemptAt(Instant.now().plus(1, ChronoUnit.HOURS));
        outboxRepository.saveAndFlush(row);

        assertThat(gauge("payments.outbox.pending")).isEqualTo(pendingBefore + 1d);
        assertThat(gauge("payments.outbox.attempts")).isEqualTo(attemptsBefore + 2d);
        assertThat(gauge("payments.outbox.lag.seconds")).isGreaterThanOrEqualTo(4d);

        row.setPublishedAt(Instant.now());
        outboxRepository.saveAndFlush(row);

        assertThat(gauge("payments.outbox.pending")).isEqualTo(pendingBefore);
    }

    @Test
    @DisplayName("The DLQ gauge increases when a message is queued")
    void dlqGaugeFollowsQueuedMessages() {
        var dlq = queueGauge("dlq", RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ);
        double before = dlq.value();

        try {
            rabbitTemplate.convertAndSend("", RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ, "probe");
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dlq.value()).isGreaterThanOrEqualTo(before + 1d));
        } finally {
            rabbitTemplate.execute(channel -> {
                channel.queuePurge(RabbitMqConfig.QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ);
                return null;
            });
        }
    }

    @Test
    @DisplayName("The request trace reaches the consumer after the relay waits for a publisher confirm")
    void requestTraceContinuesAcrossTheOutbox() {
        Span span = tracer.nextSpan().name("http-post-payment").start();
        String traceId = span.context().traceId();
        String spanId = span.context().spanId();
        assertThat(traceId).matches("[0-9a-f]{32}");
        traceProbe.expect(traceId);

        PaymentResponse response;
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            response = createPaymentCommandHandler.handle(new CreatePaymentCommand(
                    UUID.randomUUID(),
                    new BigDecimal("10.00"),
                    "BRL",
                    "trace probe"
            ));
        } finally {
            span.end();
        }

        String headersJson = jdbcTemplate.query(
                "SELECT headers FROM outbox_events WHERE aggregate_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                response.id()
        );
        assertThat(headersJson).contains("traceparent").contains(traceId).contains(spanId);

        Timer publishLag = meterRegistry.get("payments.outbox.publish.lag").timer();
        long recordedBefore = publishLag.count();
        assertThat(outboxRelayService.relayPendingEvents()).isGreaterThanOrEqualTo(1);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(traceProbe.traceparent())
                            .matches("00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}");
                    assertThat(traceProbe.listenerTraceId()).isEqualTo(traceId);
                });
        assertThat(publishLag.count()).isGreaterThan(recordedBefore);
        assertThat(publishLag.takeSnapshot().histogramCounts()).isNotEmpty();
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private io.micrometer.core.instrument.Gauge queueGauge(String role, String queue) {
        return meterRegistry.find("payments.queue.messages")
                .tag("queue", queue)
                .tag("role", role)
                .gauge();
    }

    @TestConfiguration
    static class TraceProbeConfig {

        @Bean
        Queue traceProbeQueue() {
            return QueueBuilder.nonDurable(TRACE_PROBE_QUEUE).autoDelete().build();
        }

        @Bean
        Binding traceProbeBinding(Queue traceProbeQueue, TopicExchange paymentsExchange) {
            return BindingBuilder.bind(traceProbeQueue)
                    .to(paymentsExchange)
                    .with(RabbitMqConfig.ROUTING_KEY_PAYMENT_CREATED);
        }

        @Bean
        TraceProbe traceProbe(Tracer tracer) {
            return new TraceProbe(tracer);
        }
    }

    static class TraceProbe {

        private final Tracer tracer;
        private volatile String expectedTraceId;
        private volatile String traceparent;
        private volatile String listenerTraceId;

        TraceProbe(Tracer tracer) {
            this.tracer = tracer;
        }

        void expect(String traceId) {
            this.expectedTraceId = traceId;
            this.traceparent = null;
            this.listenerTraceId = null;
        }

        String traceparent() {
            return traceparent;
        }

        String listenerTraceId() {
            return listenerTraceId;
        }

        @RabbitListener(queues = TRACE_PROBE_QUEUE)
        public void onMessage(Message message) {
            String parent = header(message, "traceparent");
            if (parent == null || expectedTraceId == null || !parent.contains(expectedTraceId)) {
                return;
            }
            this.traceparent = parent;
            Span current = tracer.currentSpan();
            this.listenerTraceId = current == null || current.context() == null ? null : current.context().traceId();
        }

        private static String header(Message message, String name) {
            Object value = message.getMessageProperties().getHeader(name);
            if (value instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return value == null ? null : value.toString();
        }
    }
}
