package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.infrastructure.messaging.RabbitMqConfig;
import com.muse.payments.shared.infrastructure.observability.W3cTraceContextPropagation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    /** Rows claimed by one transaction and published as one pipelined batch. */
    static final int BATCH_SIZE = 50;
    /** Upper bound of batches per poll, so one poll stays bounded while a backlog drains. */
    static final int MAX_BATCHES_PER_POLL = 20;
    /**
     * Extra time the claim stays invisible to other relays after the confirm wait ends,
     * so a slow commit cannot overlap the next claim of the same row.
     */
    private static final Duration CLAIM_MARGIN = Duration.ofSeconds(10);

    private final OutboxJpaRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final W3cTraceContextPropagation traceContext;
    private final Timer publishLag;
    private final Duration confirmTimeout;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelayService(
            OutboxJpaRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            W3cTraceContextPropagation traceContext,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            @Value("${app.messaging.confirm-timeout:5s}") Duration confirmTimeout
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
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException("app.messaging.confirm-timeout must be positive");
        }
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * Drains due outbox rows in batches. One transaction claims a batch, the batch is sent
     * to the broker without waiting between messages, the publisher confirms are awaited
     * together, and one transaction marks every confirmed row. A failed row is rescheduled
     * on its own, so one bad event does not hold back the rest of the batch.
     *
     * <p>A batch holds at most one event per aggregate. A later event of the same aggregate
     * is left unclaimed and goes out in a following batch, after the earlier one was confirmed.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public int relayPendingEvents() {
        int publishedCount = 0;
        for (int i = 0; i < MAX_BATCHES_PER_POLL; i++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            ClaimedBatch batch = transactionTemplate.execute(status -> claimBatch());
            if (batch == null || batch.candidates().isEmpty()) {
                break;
            }
            BatchResult result = publishBatch(batch.candidates());
            publishedCount += result.published();
            if (result.interrupted() || !batch.moreDue()) {
                break;
            }
        }
        return publishedCount;
    }

    private ClaimedBatch claimBatch() {
        List<OutboxEventEntity> pending = outboxRepository.findPendingForPublishing(Instant.now(), BATCH_SIZE);
        if (pending.isEmpty()) {
            return new ClaimedBatch(List.of(), false);
        }
        Set<UUID> aggregates = new HashSet<>();
        List<PublishCandidate> candidates = new ArrayList<>(pending.size());
        List<UUID> ids = new ArrayList<>(pending.size());
        for (OutboxEventEntity event : pending) {
            if (!aggregates.add(event.getAggregateId())) {
                continue;
            }
            ids.add(event.getId());
            candidates.add(new PublishCandidate(
                    event.getId(),
                    event.getEventType(),
                    event.getPayload(),
                    event.getHeaders(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getOccurredAt(),
                    resolveRoutingKey(event.getEventType())
            ));
        }
        Instant leaseUntil = Instant.now().plus(confirmTimeout).plus(CLAIM_MARGIN);
        int claimed = outboxRepository.claimAllUntil(ids, leaseUntil);
        if (claimed != ids.size()) {
            throw new IllegalStateException(
                    "Claimed " + claimed + " of " + ids.size() + " locked outbox events");
        }
        boolean moreDue = pending.size() == BATCH_SIZE || candidates.size() < pending.size();
        return new ClaimedBatch(candidates, moreDue);
    }

    private BatchResult publishBatch(List<PublishCandidate> batch) {
        List<InFlight> inFlight = new ArrayList<>(batch.size());
        boolean interrupted = false;
        for (PublishCandidate candidate : batch) {
            if (Thread.currentThread().isInterrupted()) {
                // Unsent rows keep their claim and become due again when the lease expires.
                interrupted = true;
                break;
            }
            try {
                inFlight.add(new InFlight(candidate, send(candidate), System.nanoTime()));
            } catch (Exception ex) {
                scheduleRetry(candidate.id(), ex);
            }
        }

        List<PublishCandidate> confirmed = new ArrayList<>(inFlight.size());
        for (InFlight sent : inFlight) {
            if (interrupted && !sent.correlation().getFuture().isDone()) {
                // No confirm was read for this row. It stays claimed until the lease expires.
                continue;
            }
            try {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - sent.sentAtNanos());
                Duration remaining = confirmTimeout.minus(elapsed);
                awaitPublisherConfirm(
                        sent.correlation(),
                        sent.candidate().id(),
                        remaining.isNegative() ? Duration.ZERO : remaining);
                confirmed.add(sent.candidate());
            } catch (Exception ex) {
                scheduleRetry(sent.candidate().id(), ex);
                if (Thread.currentThread().isInterrupted() || causedByInterrupt(ex)) {
                    interrupted = true;
                }
            }
        }

        if (!confirmed.isEmpty()) {
            markPublished(confirmed);
        }
        return new BatchResult(confirmed.size(), interrupted);
    }

    private CorrelationData send(PublishCandidate event) {
        Map<String, Object> headers = readHeaders(event.headers());
        String correlationId = correlationId(headers);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        traceContext.runWithParent(headers, () -> rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE_PAYMENTS,
                event.routingKey(),
                event.payload(),
                message -> {
                    var props = message.getMessageProperties();
                    props.setContentType("application/json");
                    props.setHeader("X-Correlation-Id", correlationId);
                    props.setHeader("X-Event-Id", event.id().toString());
                    props.setHeader("X-Event-Type", event.eventType());
                    props.setHeader("X-Aggregate-Type", event.aggregateType());
                    props.setHeader("X-Aggregate-Id", event.aggregateId().toString());
                    return message;
                },
                correlation
        ));
        return correlation;
    }

    private void markPublished(List<PublishCandidate> confirmed) {
        Instant publishedAt = Instant.now();
        List<UUID> ids = confirmed.stream().map(PublishCandidate::id).toList();
        transactionTemplate.executeWithoutResult(status -> outboxRepository.markPublished(ids, publishedAt));
        for (PublishCandidate event : confirmed) {
            recordPublishLag(event.occurredAt(), publishedAt);
            log.debug("Published outbox event {} of type {} with routing key {}",
                    event.id(), event.eventType(), event.routingKey());
        }
    }

    void awaitPublisherConfirm(CorrelationData correlation, UUID eventId) throws Exception {
        awaitPublisherConfirm(correlation, eventId, confirmTimeout);
    }

    void awaitPublisherConfirm(CorrelationData correlation, UUID eventId, Duration timeout) throws Exception {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Publisher confirm timed out for outbox event " + eventId, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for publisher confirm of outbox event " + eventId, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new IllegalStateException("Publisher confirm failed for outbox event " + eventId, cause);
        }
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new IllegalStateException("Broker returned unroutable outbox event " + eventId
                    + ": " + returned.getReplyText());
        }
        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "missing confirm" : confirm.getReason();
            throw new IllegalStateException("Broker nacked outbox event " + eventId + ": " + reason);
        }
    }

    private void scheduleRetry(UUID eventId, Exception ex) {
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEventEntity event = outboxRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException("Outbox event disappeared: " + eventId));
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
        });
    }

    private static boolean causedByInterrupt(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String resolveRoutingKey(String eventType) {
        if (eventType == null) {
            return "payment.unknown";
        }
        return switch (eventType) {
            case "PaymentCreated" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_CREATED;
            case "PaymentCompleted" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_COMPLETED;
            case "PaymentCancelled" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_CANCELLED;
            case "PaymentHeldForReview" -> RabbitMqConfig.ROUTING_KEY_PAYMENT_HELD;
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

    private record PublishCandidate(
            UUID id,
            String eventType,
            String payload,
            String headers,
            String aggregateType,
            UUID aggregateId,
            Instant occurredAt,
            String routingKey
    ) {
    }

    /** {@code moreDue} is true when the query was full or a same-aggregate row was left for the next batch. */
    private record ClaimedBatch(List<PublishCandidate> candidates, boolean moreDue) {
    }

    private record InFlight(PublishCandidate candidate, CorrelationData correlation, long sentAtNanos) {
    }

    private record BatchResult(int published, boolean interrupted) {
    }
}
