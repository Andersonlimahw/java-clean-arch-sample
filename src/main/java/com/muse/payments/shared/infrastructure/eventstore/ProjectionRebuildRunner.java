package com.muse.payments.shared.infrastructure.eventstore;

import com.muse.payments.shared.application.ProjectionRebuildPort;
import com.muse.payments.shared.application.RebuildableProjection;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProjectionRebuildRunner implements ProjectionRebuildPort {

    private static final int BATCH_SIZE = 500;
    private static final String SELECT_EVENTS = """
            SELECT global_position, stream_type, stream_id, stream_version, event_id, event_type,
                   schema_version, payload::text AS payload_json, metadata::text AS metadata_json,
                   occurred_at, recorded_at, transaction_id::text AS transaction_id
            FROM event_store
            WHERE stream_type = ? AND global_position > ?
            ORDER BY global_position
            LIMIT ?
            """;
    private static final String RESET_CHECKPOINT = """
            INSERT INTO projection_checkpoints (projection, last_position, last_transaction_id, updated_at)
            VALUES (?, 0, NULL, NOW())
            ON CONFLICT (projection) DO UPDATE SET
                last_position = 0,
                last_transaction_id = NULL,
                updated_at = NOW()
            """;
    private static final String UPDATE_CHECKPOINT = """
            UPDATE projection_checkpoints
            SET last_position = ?, last_transaction_id = ?::xid8, updated_at = NOW()
            WHERE projection = ?
            """;

    private final Map<String, RebuildableProjection> projectionsByName;
    private final JdbcTemplate jdbcTemplate;
    private final EventSerializer serializer;

    public ProjectionRebuildRunner(
            List<RebuildableProjection> projections,
            JdbcTemplate jdbcTemplate,
            EventSerializer serializer
    ) {
        this.projectionsByName = Objects.requireNonNull(projections).stream()
                .collect(Collectors.toUnmodifiableMap(RebuildableProjection::projectionName, Function.identity()));
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.serializer = Objects.requireNonNull(serializer);
    }

    @Override
    @Transactional
    public void rebuild(String projectionName) {
        Objects.requireNonNull(projectionName, "projectionName is required");
        RebuildableProjection projection = projectionsByName.get(projectionName.trim());
        if (projection == null) {
            throw new IllegalArgumentException("Unknown projection: " + projectionName
                    + ". Known projections: " + projectionsByName.keySet());
        }
        // Same lock order as the live poller: checkpoint first, projection second.
        jdbcTemplate.update(RESET_CHECKPOINT, projection.projectionName());
        projection.truncate();

        long lastPosition = 0;
        String lastTxId = null;
        while (true) {
            List<StoredEvent> batch = jdbcTemplate.query(
                    SELECT_EVENTS,
                    eventRowMapper,
                    projection.streamType(),
                    lastPosition,
                    BATCH_SIZE
            );
            if (batch.isEmpty()) {
                break;
            }
            for (StoredEvent event : batch) {
                projection.apply(event);
            }
            StoredEvent last = batch.getLast();
            lastPosition = last.globalPosition();
            lastTxId = last.transactionId();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        if (lastPosition > 0) {
            jdbcTemplate.update(UPDATE_CHECKPOINT, lastPosition, lastTxId, projection.projectionName());
        }
    }

    private final RowMapper<StoredEvent> eventRowMapper = this::mapEvent;

    private StoredEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        String eventType = resultSet.getString("event_type");
        int schemaVersion = resultSet.getInt("schema_version");
        return new StoredEvent(
                resultSet.getLong("global_position"),
                new StreamRef(resultSet.getString("stream_type"), resultSet.getObject("stream_id", UUID.class)),
                resultSet.getLong("stream_version"),
                resultSet.getObject("event_id", UUID.class),
                eventType,
                schemaVersion,
                serializer.deserialize(eventType, schemaVersion, resultSet.getString("payload_json")),
                serializer.deserializeMetadata(resultSet.getString("metadata_json")),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("transaction_id")
        );
    }
}
