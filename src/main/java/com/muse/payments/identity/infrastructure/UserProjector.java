package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.domain.UserDisabledEvent;
import com.muse.payments.identity.domain.UserRegisteredEvent;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.RebuildableProjection;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.application.SyncProjector;
import com.muse.payments.shared.domain.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Objects;

@Component
public class UserProjector implements SyncProjector<DomainEvent>, RebuildableProjection {

    public static final String PROJECTION_NAME = "users";

    private static final String UPSERT_REGISTERED = """
            INSERT INTO users (
                id, email, password_hash, display_name, roles, enabled, created_at, updated_at, version
            ) VALUES (?, ?, NULL, ?, ?, true, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                email = EXCLUDED.email,
                display_name = EXCLUDED.display_name,
                roles = EXCLUDED.roles,
                enabled = EXCLUDED.enabled,
                updated_at = EXCLUDED.updated_at,
                version = EXCLUDED.version
            """;

    private static final String UPDATE_DISABLED = """
            UPDATE users
            SET enabled = false, updated_at = ?, version = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public UserProjector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public String streamType() {
        return IdentityEventRegistryConfiguration.USER_STREAM_TYPE;
    }

    @Override
    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE users");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(StoredEvent event) {
        Objects.requireNonNull(event, "event is required");
        project(event.stream(), event.event(), event.streamVersion(), event.metadata());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void project(StreamRef stream, DomainEvent event, long streamVersion, EventMetadata metadata) {
        Objects.requireNonNull(stream, "stream is required");
        Objects.requireNonNull(event, "event is required");
        long projectionVersion = streamVersion - 1;

        switch (event) {
            case UserRegisteredEvent e -> jdbcTemplate.update(
                    UPSERT_REGISTERED,
                    e.userId(),
                    e.email(),
                    e.displayName(),
                    String.join(",", e.roles()),
                    Timestamp.from(e.occurredAt()),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion
            );
            case UserDisabledEvent e -> jdbcTemplate.update(
                    UPDATE_DISABLED,
                    Timestamp.from(e.occurredAt()),
                    projectionVersion,
                    e.userId()
            );
            default -> throw new IllegalArgumentException("Unsupported user event for projection: " + event.getClass().getName());
        }
    }
}
