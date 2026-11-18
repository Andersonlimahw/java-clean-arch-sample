package com.muse.payments.identity.infrastructure;

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
 * Purges expired rows from the {@code refresh_tokens} read model after a retention grace
 * window. Unexpired revoked tokens stay intact so token-reuse detection can still revoke
 * their active family, and authoritative history remains immutable in {@code event_store}.
 */
@Service
public class RefreshTokenCleanup {

    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

    private static final String DELETE_EXPIRED_BATCH = """
            DELETE FROM refresh_tokens
            WHERE id IN (
                SELECT id FROM refresh_tokens
                WHERE expires_at < ?
                ORDER BY expires_at ASC
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Duration expiredRefreshTokensTtl;

    public RefreshTokenCleanup(
            JdbcTemplate jdbcTemplate,
            @Value("${app.retention.expired-refresh-tokens-ttl:P7D}") Duration expiredRefreshTokensTtl
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        if (expiredRefreshTokensTtl == null || expiredRefreshTokensTtl.isNegative()) {
            throw new IllegalArgumentException("app.retention.expired-refresh-tokens-ttl must not be negative");
        }
        this.expiredRefreshTokensTtl = expiredRefreshTokensTtl;
    }

    @Scheduled(initialDelayString = "${app.retention.initial-delay-ms:60000}", fixedDelayString = "${app.retention.cleanup-interval-ms:3600000}")
    public int deleteExpired() {
        return deleteExpiredAt(Instant.now());
    }

    public int deleteExpiredAt(Instant referenceNow) {
        Objects.requireNonNull(referenceNow, "referenceNow must not be null");
        Timestamp cutoff = Timestamp.from(referenceNow.minus(expiredRefreshTokensTtl));
        int total = 0;
        int removed;
        do {
            removed = jdbcTemplate.update(DELETE_EXPIRED_BATCH, cutoff, BATCH_SIZE);
            total += removed;
        } while (removed == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} expired refresh-token projection rows", total);
        }
        return total;
    }
}
