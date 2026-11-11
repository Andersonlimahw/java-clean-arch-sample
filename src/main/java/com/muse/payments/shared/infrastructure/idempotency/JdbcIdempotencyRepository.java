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
                WHERE user_id = ? AND key = ?
                """;
        List<IdempotencyRecord> records = jdbcTemplate.query(sql, rowMapper, userId, key);
        return records.isEmpty() ? Optional.empty() : Optional.of(records.getFirst());
    }

    @Override
    public boolean tryInsertProcessing(UUID userId, String key, String requestHash, Duration ttl) {
        String sql = """
                INSERT INTO idempotency_keys (key, user_id, request_hash, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, key) DO NOTHING
                """;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        try {
            int rowsAffected = jdbcTemplate.update(
                    sql,
                    key,
                    userId,
                    requestHash,
                    IdempotencyRecord.STATUS_PROCESSING,
                    Timestamp.from(now),
                    Timestamp.from(expiresAt)
            );
            return rowsAffected > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void markCompleted(UUID userId, String key, int responseCode, String responseBodyJson) {
        String sql = """
                UPDATE idempotency_keys
                SET status = ?, response_code = ?, response_body = CAST(? AS JSONB)
                WHERE user_id = ? AND key = ?
                """;
        jdbcTemplate.update(
                sql,
                IdempotencyRecord.STATUS_COMPLETED,
                responseCode,
                responseBodyJson,
                userId,
                key
        );
    }
}
