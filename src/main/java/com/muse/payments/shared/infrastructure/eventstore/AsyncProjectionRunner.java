package com.muse.payments.shared.infrastructure.eventstore;

import com.muse.payments.shared.application.AsyncProjector;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "app.projections.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AsyncProjectionRunner {

    private static final String ENSURE_CHECKPOINT = """
            INSERT INTO projection_checkpoints (projection, last_position)
            VALUES (?, 0)
            ON CONFLICT (projection) DO NOTHING
            """;
    private static final String LOCK_CHECKPOINT = """
            SELECT last_position
            FROM projection_checkpoints
            WHERE projection = ?
            FOR UPDATE SKIP LOCKED
            """;
    private static final String SELECT_BATCH = """
            SELECT global_position, stream_type, stream_id, stream_version, event_id, event_type,
                   schema_version, payload::text AS payload_json, metadata::text AS metadata_json,
                   occurred_at, recorded_at, transaction_id::text AS transaction_id
            FROM event_store
            WHERE stream_type = ?
              AND global_position > ?
              AND global_position <= ?
              AND transaction_id < pg_snapshot_xmin(pg_current_snapshot())
            ORDER BY global_position
            LIMIT ?
            """;
    private static final String UPDATE_CHECKPOINT = """
            UPDATE projection_checkpoints
            SET last_position = ?, last_transaction_id = ?::xid8, updated_at = NOW()
            WHERE projection = ?
            """;
    private static final String COMPUTE_LAG = """
            SELECT GREATEST(
                COALESCE((SELECT MAX(global_position) FROM event_store WHERE stream_type = ?), 0)
                - COALESCE((SELECT last_position FROM projection_checkpoints WHERE projection = ?), 0),
                0
            )
            """;

    private final List<AsyncProjector> projectors;
    private final JdbcTemplate jdbcTemplate;
    private final EventSerializer serializer;
    private final TransactionTemplate txTemplate;
    private final AsyncProjectionProperties properties;
    private final ConcurrentHashMap<String, Horizon> horizons = new ConcurrentHashMap<>();

    private record Horizon(long position, String transactionBoundary) { }

    // XIDs may be allocated BEFORE a transaction appends. A bare xmin filter can
    // therefore skip an invisible lower position while processing an older XID.
    // Fence sequence allocation first, then wait for every transaction in that
    // snapshot to finish before advancing a positional checkpoint past the fence.
    private Horizon captureHorizon() {
        long position = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM event_store_global_position_seq", Long.class);
        // Allocate our own XID after reading the sequence: snapshot.xmax alone
        // can equal an active newer XID when only an older transaction committed.
        String boundary = jdbcTemplate.queryForObject(
                "SELECT pg_current_xact_id()::text", String.class);
        return new Horizon(position, boundary);
    }

    public AsyncProjectionRunner(
            List<AsyncProjector> projectors,
            JdbcTemplate jdbcTemplate,
            EventSerializer serializer,
            PlatformTransactionManager transactionManager,
            AsyncProjectionProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.projectors = List.copyOf(Objects.requireNonNull(projectors));
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.serializer = Objects.requireNonNull(serializer);
        this.txTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.properties = Objects.requireNonNull(properties);

        for (AsyncProjector projector : this.projectors) {
            Gauge.builder("projection.lag", () -> lagFor(projector))
                    .description("Number of unprojected events for the projection")
                    .tag("projection", projector.projectionName())
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${app.projections.poll-interval-ms:250}")
    public void pollAll() {
        for (AsyncProjector projector : projectors) {
            poll(projector);
        }
    }

    public int poll(AsyncProjector projector) {
        Horizon horizon = horizons.computeIfAbsent(projector.projectionName(), ignored -> captureHorizon());
        Boolean settled = jdbcTemplate.queryForObject(
                "SELECT pg_snapshot_xmin(pg_current_snapshot()) >= ?::xid8", Boolean.class,
                horizon.transactionBoundary());
        if (!Boolean.TRUE.equals(settled)) {
            return 0;
        }
        int totalProcessed = 0;
        int batchSize = properties.getBatchSize();
        int batchCount;
        do {
            Integer processed = txTemplate.execute(status -> pollBatch(projector, batchSize, horizon.position()));
            batchCount = processed == null ? 0 : processed;
            totalProcessed += batchCount;
        } while (batchCount == batchSize);
        horizons.replace(projector.projectionName(), horizon, captureHorizon());
        return totalProcessed;
    }

    private int pollBatch(AsyncProjector projector, int batchSize, long safePosition) {
        jdbcTemplate.update(ENSURE_CHECKPOINT, projector.projectionName());
        List<Long> positions = jdbcTemplate.query(
                LOCK_CHECKPOINT,
                (rs, rowNum) -> rs.getLong("last_position"),
                projector.projectionName()
        );
        if (positions.isEmpty()) {
            return 0;
        }
        long lastPosition = positions.getFirst();
        List<StoredEvent> events = jdbcTemplate.query(
                SELECT_BATCH,
                eventRowMapper,
                projector.streamType(),
                lastPosition,
                safePosition,
                batchSize
        );
        if (events.isEmpty()) {
            return 0;
        }
        for (StoredEvent event : events) {
            projector.apply(event);
        }
        StoredEvent last = events.getLast();
        jdbcTemplate.update(
                UPDATE_CHECKPOINT,
                last.globalPosition(),
                last.transactionId(),
                projector.projectionName()
        );
        return events.size();
    }

    private double lagFor(AsyncProjector projector) {
        try {
            Long lag = jdbcTemplate.queryForObject(
                    COMPUTE_LAG,
                    Long.class,
                    projector.streamType(),
                    projector.projectionName()
            );
            return lag == null ? 0.0 : lag.doubleValue();
        } catch (RuntimeException ex) {
            return Double.NaN;
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
