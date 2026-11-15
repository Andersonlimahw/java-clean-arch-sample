package com.muse.payments.shared.infrastructure.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.application.Snapshot;
import com.muse.payments.shared.application.SnapshotStore;
import com.muse.payments.shared.application.StreamRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcSnapshotStore implements SnapshotStore {

    private static final String SELECT_CURRENT = """
            SELECT stream_type, stream_id, stream_version, schema_version, state::text AS state_json, taken_at
            FROM snapshots
            WHERE stream_type = ? AND stream_id = ? AND schema_version = ?
            """;
    private static final String UPSERT_NEWER = """
            INSERT INTO snapshots (stream_type, stream_id, stream_version, schema_version, state, taken_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (stream_type, stream_id) DO UPDATE SET
                stream_version = EXCLUDED.stream_version,
                schema_version = EXCLUDED.schema_version,
                state = EXCLUDED.state,
                taken_at = EXCLUDED.taken_at
            WHERE snapshots.stream_version < EXCLUDED.stream_version
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcSnapshotStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> load(StreamRef stream, int expectedSchemaVersion) {
        Objects.requireNonNull(stream, "Stream is required");
        if (expectedSchemaVersion < 1) {
            throw new IllegalArgumentException("Expected snapshot schema version must be positive");
        }
        return jdbcTemplate.query(SELECT_CURRENT, (resultSet, rowNumber) -> new Snapshot(
                        new StreamRef(resultSet.getString("stream_type"),
                                resultSet.getObject("stream_id", java.util.UUID.class)),
                        resultSet.getLong("stream_version"),
                        resultSet.getInt("schema_version"),
                        resultSet.getString("state_json"),
                        resultSet.getObject("taken_at", OffsetDateTime.class).toInstant()),
                stream.type(), stream.id(), expectedSchemaVersion).stream().findFirst();
    }

    @Override
    @Transactional
    public void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot is required");
        try {
            JsonNode state = objectMapper.readTree(snapshot.stateJson());
            if (state == null || !state.isObject()) {
                throw new IllegalArgumentException("Snapshot state must be a JSON object");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Snapshot state must be valid JSON", exception);
        }
        jdbcTemplate.update(UPSERT_NEWER,
                snapshot.stream().type(),
                snapshot.stream().id(),
                snapshot.streamVersion(),
                snapshot.schemaVersion(),
                snapshot.stateJson(),
                java.sql.Timestamp.from(snapshot.takenAt()));
    }
}
