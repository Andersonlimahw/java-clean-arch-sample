package com.muse.payments.notifications.infrastructure;

import com.muse.payments.notifications.domain.NotificationRecordedEvent;
import com.muse.payments.shared.application.AsyncProjector;
import com.muse.payments.shared.application.StoredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Objects;

@Component
public class NotificationProjector implements AsyncProjector {

    public static final String PROJECTION_NAME = "notifications";

    private static final String INSERT = """
            INSERT INTO notifications (
                id, user_id, payment_id, type, title, message, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public NotificationProjector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public String streamType() {
        return NotificationEventRegistryConfiguration.STREAM_TYPE;
    }

    @Override
    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE notifications");
    }

    @Override
    public void apply(StoredEvent storedEvent) {
        Objects.requireNonNull(storedEvent, "storedEvent is required");
        if (!(storedEvent.event() instanceof NotificationRecordedEvent e)) {
            throw new IllegalArgumentException(
                    "Unsupported notification event for projection: " + storedEvent.event().getClass().getName());
        }
        jdbcTemplate.update(
                INSERT,
                e.notificationId(),
                e.userId(),
                e.paymentId(),
                e.type(),
                e.title(),
                e.message(),
                Timestamp.from(e.occurredAt())
        );
    }
}
