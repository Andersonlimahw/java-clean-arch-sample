package com.muse.payments.shared.infrastructure.retention;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.infrastructure.RefreshTokenCleanup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OperationalRetentionIT extends TestcontainersBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperationalRetentionCleanup operationalCleanup;

    @Autowired
    private RefreshTokenCleanup refreshTokenCleanup;

    @Test
    @DisplayName("Flyway V8 retention indexes exist and sweeps purge only aged published/processed/expired rows")
    void purgesAgedOperationalRowsWithoutTouchingUnpublishedOutboxOrUnexpiredTokens() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname IN ('idx_outbox_published_at', 'idx_processed_events_processed_at', 'idx_refresh_tokens_expires_at')",
                String.class
        );
        assertThat(indexes).containsExactlyInAnyOrder(
                "idx_outbox_published_at",
                "idx_processed_events_processed_at",
                "idx_refresh_tokens_expires_at"
        );

        Instant now = Instant.now();
        UUID agedPublishedOutbox = insertOutboxRow(now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(9)));
        UUID freshPublishedOutbox = insertOutboxRow(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        UUID agedUnpublishedOutbox = insertOutboxRow(now.minus(Duration.ofDays(20)), null);

        UUID agedProcessedEvent = UUID.randomUUID();
        UUID freshProcessedEvent = UUID.randomUUID();
        insertProcessedEvent("notifications", agedProcessedEvent, now.minus(Duration.ofDays(40)));
        insertProcessedEvent("notifications", freshProcessedEvent, now.minus(Duration.ofDays(5)));

        UUID expiredOldToken = insertRefreshToken(true, now.minus(Duration.ofDays(10)));
        UUID revokedButUnexpiredToken = insertRefreshToken(true, now.plus(Duration.ofDays(3)));
        UUID activeToken = insertRefreshToken(false, now.plus(Duration.ofDays(6)));

        OperationalRetentionCleanup.RetentionSweepResult sweep = operationalCleanup.purgeAt(now);
        int deletedTokens = refreshTokenCleanup.deleteExpiredAt(now);

        assertThat(sweep.publishedOutboxDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(sweep.processedEventsDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(deletedTokens).isGreaterThanOrEqualTo(1);

        assertThat(outboxExists(agedPublishedOutbox)).isFalse();
        assertThat(outboxExists(freshPublishedOutbox)).isTrue();
        assertThat(outboxExists(agedUnpublishedOutbox)).isTrue();

        assertThat(processedExists("notifications", agedProcessedEvent)).isFalse();
        assertThat(processedExists("notifications", freshProcessedEvent)).isTrue();

        assertThat(refreshTokenExists(expiredOldToken)).isFalse();
        assertThat(refreshTokenExists(revokedButUnexpiredToken)).isTrue();
        assertThat(refreshTokenExists(activeToken)).isTrue();
    }

    private UUID insertOutboxRow(Instant occurredAt, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, headers, occurred_at, published_at, next_attempt_at)
                VALUES (?, 'Payment', ?, 'PaymentCompleted', '{}'::jsonb, '{}'::jsonb, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                Timestamp.from(occurredAt),
                publishedAt != null ? Timestamp.from(publishedAt) : null,
                Timestamp.from(Instant.now().plus(Duration.ofHours(1)))
        );
        return id;
    }

    private void insertProcessedEvent(String consumer, UUID eventId, Instant processedAt) {
        jdbcTemplate.update(
                "INSERT INTO processed_events (consumer, event_id, processed_at) VALUES (?, ?, ?)",
                consumer, eventId, Timestamp.from(processedAt)
        );
    }

    private UUID insertRefreshToken(boolean revoked, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, is_revoked, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID(),
                revoked,
                Timestamp.from(expiresAt)
        );
        return id;
    }

    private boolean outboxExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private boolean processedExists(String consumer, UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class, consumer, eventId
        );
        return count != null && count > 0;
    }

    private boolean refreshTokenExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}
