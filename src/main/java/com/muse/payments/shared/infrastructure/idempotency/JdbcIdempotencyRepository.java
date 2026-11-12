package com.muse.payments.shared.infrastructure.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<IdempotencyRecord> rowMapper = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("key"),
            rs.getObject("user_id", UUID.class),
            rs.getString("request_hash"),
            rs.getString("status"),
            rs.getObject("response_code", Integer.class),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant()
    );

    public JdbcIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID userId, String key) {
        String sql = """
                SELECT key, user_id, request_hash, status, response_code, response_body, created_at, expires_at
                FROM idempotency_keys
                WHERE user_id = ? AND key = ? AND expires_at > ?
                """;
        List<IdempotencyRecord> records = jdbcTemplate.query(
                sql, rowMapper, userId, key, Timestamp.from(Instant.now()));
        return records.isEmpty() ? Optional.empty() : Optional.of(records.getFirst());
    }

    @Override
    public Optional<UUID> tryInsertProcessing(UUID userId, String key, String requestHash, Duration ttl) {
        String sql = """
                INSERT INTO idempotency_keys (key, user_id, request_hash, status, created_at, expires_at, lease_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, key) DO UPDATE
                SET request_hash = EXCLUDED.request_hash,
                    status = EXCLUDED.status,
                    response_code = NULL,
                    response_body = NULL,
                    created_at = EXCLUDED.created_at,
                    expires_at = EXCLUDED.expires_at,
                    lease_id = EXCLUDED.lease_id
                WHERE idempotency_keys.expires_at <= EXCLUDED.created_at
                """;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        UUID leaseId = UUID.randomUUID();
        try {
            int rowsAffected = jdbcTemplate.update(
                    sql,
                    key,
                    userId,
                    requestHash,
                    IdempotencyRecord.STATUS_PROCESSING,
                    Timestamp.from(now),
                    Timestamp.from(expiresAt),
                    leaseId
            );
            return rowsAffected > 0 ? Optional.of(leaseId) : Optional.empty();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public int markCompleted(UUID userId, String key, String requestHash, UUID leaseId, int responseCode, String responseBodyJson) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        String sql = """
                UPDATE idempotency_keys
                SET status = ?, response_code = ?, response_body = CAST(? AS JSONB)
                WHERE user_id = ? AND key = ? AND status = ? AND request_hash = ? AND lease_id = ? AND expires_at > ?
                """;
        return jdbcTemplate.update(
                sql,
                IdempotencyRecord.STATUS_COMPLETED,
                responseCode,
                responseBodyJson,
                userId,
                key,
                IdempotencyRecord.STATUS_PROCESSING,
                requestHash,
                leaseId,
                Timestamp.from(Instant.now())
        );
    }

    @Override
    public int deleteProcessing(UUID userId, String key, String requestHash, UUID leaseId) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        String sql = """
                DELETE FROM idempotency_keys
                WHERE user_id = ? AND key = ? AND status = ? AND request_hash = ? AND lease_id = ?
                """;
        return jdbcTemplate.update(
                sql,
                userId,
                key,
                IdempotencyRecord.STATUS_PROCESSING,
                requestHash,
                leaseId
        );
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        // SKIP LOCKED lets another instance delete a different batch without waiting.
        // The delete repeats the expiry predicate so a row reclaimed after it was selected is kept.
        String sql = """
                WITH expired AS (
                    SELECT user_id, key
                    FROM idempotency_keys
                    WHERE expires_at <= ?
                    ORDER BY expires_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM idempotency_keys AS target
                USING expired
                WHERE target.user_id = expired.user_id
                  AND target.key = expired.key
                  AND target.expires_at <= ?
                """;
        Timestamp cutoff = Timestamp.from(now);
        return jdbcTemplate.update(sql, cutoff, limit, cutoff);
    }
}
