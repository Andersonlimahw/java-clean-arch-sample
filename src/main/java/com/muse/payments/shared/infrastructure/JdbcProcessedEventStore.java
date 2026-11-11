package com.muse.payments.shared.infrastructure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcProcessedEventStore implements ProcessedEventStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessedEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public boolean tryMarkProcessed(String consumer, UUID eventId) {
        String sql = """
                INSERT INTO processed_events (consumer, event_id, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """;
        try {
            int rows = jdbcTemplate.update(sql, consumer, eventId, Timestamp.from(Instant.now()));
            return rows > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean isProcessed(String consumer, UUID eventId) {
        String sql = """
                SELECT COUNT(*) FROM processed_events
                WHERE consumer = ? AND event_id = ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, consumer, eventId);
        return count != null && count > 0;
    }
}
