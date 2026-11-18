package com.muse.payments.shared.infrastructure.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Purges aged published outbox rows and aged consumer deduplication rows in bounded
 * batches. Unpublished outbox rows and the immutable {@code event_store} are never touched.
 */
@Service
public class OperationalRetentionCleanup {

    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(OperationalRetentionCleanup.class);

    private static final String DELETE_PUBLISHED_OUTBOX_BATCH = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < ?
                ORDER BY published_at ASC
                LIMIT ?
            )
            """;

    private static final String DELETE_PROCESSED_EVENTS_BATCH = """
            DELETE FROM processed_events
            WHERE ctid IN (
                SELECT ctid FROM processed_events
                WHERE processed_at < ?
                ORDER BY processed_at ASC
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Duration publishedOutboxTtl;
    private final Duration processedEventsTtl;

    public OperationalRetentionCleanup(
            JdbcTemplate jdbcTemplate,
            @Value("${app.retention.published-outbox-ttl:P7D}") Duration publishedOutboxTtl,
            @Value("${app.retention.processed-events-ttl:P30D}") Duration processedEventsTtl
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.publishedOutboxTtl = requirePositive(publishedOutboxTtl, "app.retention.published-outbox-ttl");
        this.processedEventsTtl = requirePositive(processedEventsTtl, "app.retention.processed-events-ttl");
    }

    @Scheduled(initialDelayString = "${app.retention.initial-delay-ms:60000}", fixedDelayString = "${app.retention.cleanup-interval-ms:3600000}")
    public RetentionSweepResult purgeAgedOperationalRows() {
        return purgeAt(Instant.now());
    }

    public RetentionSweepResult purgeAt(Instant referenceNow) {
        Objects.requireNonNull(referenceNow, "referenceNow must not be null");
        int outboxDeleted = deleteInBatches(DELETE_PUBLISHED_OUTBOX_BATCH, referenceNow.minus(publishedOutboxTtl));
        int processedDeleted = deleteInBatches(DELETE_PROCESSED_EVENTS_BATCH, referenceNow.minus(processedEventsTtl));
        if (outboxDeleted > 0 || processedDeleted > 0) {
            log.info("Purged {} published outbox rows and {} processed-event rows", outboxDeleted, processedDeleted);
        }
        return new RetentionSweepResult(outboxDeleted, processedDeleted);
    }

    private int deleteInBatches(String sql, Instant cutoff) {
        int total = 0;
        int removed;
        Timestamp cutoffTs = Timestamp.from(cutoff);
        do {
            removed = jdbcTemplate.update(sql, cutoffTs, BATCH_SIZE);
            total += removed;
        } while (removed == BATCH_SIZE);
        return total;
    }

    private static Duration requirePositive(Duration duration, String property) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return duration;
    }

    public record RetentionSweepResult(int publishedOutboxDeleted, int processedEventsDeleted) {
    }
}
