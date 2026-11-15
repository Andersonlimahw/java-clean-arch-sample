package com.muse.payments.shared.infrastructure.eventstore;

import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.EventStream;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.domain.DomainEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Repository
public class JdbcEventStore implements EventStore {

    private static final String INSERT = """
            INSERT INTO event_store (
                stream_type, stream_id, stream_version, event_id, event_type, schema_version,
                payload, metadata, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """;
    private static final String SELECT_STREAM = """
            SELECT global_position, stream_type, stream_id, stream_version, event_id, event_type,
                   schema_version, payload::text AS payload_json, metadata::text AS metadata_json,
                   occurred_at, recorded_at, transaction_id::text AS transaction_id
            FROM event_store
            WHERE stream_type = ? AND stream_id = ? AND stream_version > ?
            ORDER BY stream_version
            """;
    private static final String LOCK_STREAM = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String SELECT_CURRENT_VERSION = """
            SELECT COALESCE(MAX(stream_version), 0)
            FROM event_store
            WHERE stream_type = ? AND stream_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EventSerializer serializer;

    public JdbcEventStore(JdbcTemplate jdbcTemplate, EventSerializer serializer) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.serializer = Objects.requireNonNull(serializer);
    }

    @Override
    @Transactional
    public long append(StreamRef stream, long expectedVersion, List<? extends DomainEvent> events,
                       EventMetadata metadata) {
        Objects.requireNonNull(stream, "Stream is required");
        Objects.requireNonNull(events, "Events are required");
        Objects.requireNonNull(metadata, "Event metadata is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected stream version cannot be negative");
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("At least one event is required");
        }

        lockStream(stream);
        long currentVersion = jdbcTemplate.queryForObject(SELECT_CURRENT_VERSION, Long.class,
                stream.type(), stream.id());
        if (currentVersion != expectedVersion) {
            throw new ConflictException("Expected stream version " + expectedVersion + " but found "
                    + currentVersion + " for " + stream.type() + "/" + stream.id());
        }

        long streamVersion = expectedVersion;
        for (DomainEvent event : events) {
            DomainEvent requiredEvent = Objects.requireNonNull(event, "Event cannot be null");
            SerializedEvent serialized = serializer.serialize(requiredEvent);
            long nextVersion = Math.incrementExact(streamVersion);
            try {
                jdbcTemplate.update(INSERT,
                        stream.type(),
                        stream.id(),
                        nextVersion,
                        requiredEvent.eventId(),
                        serialized.eventType(),
                        serialized.schemaVersion(),
                        serialized.payload().toString(),
                        serializer.serializeMetadata(metadata),
                        Timestamp.from(requiredEvent.occurredAt()));
            } catch (DuplicateKeyException exception) {
                throw new ConflictException("Event stream version is already present for " + stream.type()
                        + "/" + stream.id());
            }
            streamVersion = nextVersion;
        }
        return streamVersion;
    }

    private void lockStream(StreamRef stream) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(LOCK_STREAM)) {
                statement.setString(1, stream.type() + ":" + stream.id());
                statement.execute();
            }
            return null;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public EventStream load(StreamRef stream) {
        Objects.requireNonNull(stream, "Stream is required");
        List<StoredEvent> events = loadAfter(stream, 0);
        long version = events.isEmpty() ? 0 : events.getLast().streamVersion();
        return new EventStream(stream, version, events);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredEvent> loadAfter(StreamRef stream, long streamVersion) {
        Objects.requireNonNull(stream, "Stream is required");
        if (streamVersion < 0) {
            throw new IllegalArgumentException("Stream version cannot be negative");
        }
        return List.copyOf(jdbcTemplate.query(SELECT_STREAM, EVENT_ROW_MAPPER, stream.type(), stream.id(),
                streamVersion));
    }

    private final RowMapper<StoredEvent> EVENT_ROW_MAPPER = this::mapEvent;

    private StoredEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        String eventType = resultSet.getString("event_type");
        int schemaVersion = resultSet.getInt("schema_version");
        return new StoredEvent(
                resultSet.getLong("global_position"),
                new StreamRef(resultSet.getString("stream_type"), resultSet.getObject("stream_id", java.util.UUID.class)),
                resultSet.getLong("stream_version"),
                resultSet.getObject("event_id", java.util.UUID.class),
                eventType,
                schemaVersion,
                serializer.deserialize(eventType, schemaVersion, resultSet.getString("payload_json")),
                serializer.deserializeMetadata(resultSet.getString("metadata_json")),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "recorded_at"),
                resultSet.getString("transaction_id"));
    }

    private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
