package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.application.OutboundText;
import com.muse.payments.payments.domain.PaymentCancelledEvent;
import com.muse.payments.payments.domain.PaymentCompletedEvent;
import com.muse.payments.payments.domain.PaymentHeldForReviewEvent;
import com.muse.payments.payments.domain.PaymentInitiatedEvent;
import com.muse.payments.payments.domain.PaymentProviderIntentCreatedEvent;
import com.muse.payments.payments.domain.PaymentRejectedInReviewEvent;
import com.muse.payments.payments.domain.PaymentReleasedFromReviewEvent;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.shared.application.AsyncProjector;
import com.muse.payments.shared.application.StoredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Objects;

@Component
public class PaymentHistoryProjector implements AsyncProjector {

    public static final String PROJECTION_NAME = "payment_history";

    private static final String INSERT = """
            INSERT INTO payment_history (
                payment_id, stream_version, event_type, status_after, actor_id, reason, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (payment_id, stream_version) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public PaymentHistoryProjector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public String streamType() {
        return PaymentEventRegistryConfiguration.STREAM_TYPE;
    }

    @Override
    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE payment_history");
    }

    @Override
    public void apply(StoredEvent storedEvent) {
        Objects.requireNonNull(storedEvent, "storedEvent is required");
        String metadataActor = storedEvent.metadata() != null ? storedEvent.metadata().actorId() : null;
        Entry entry = switch (storedEvent.event()) {
            case PaymentInitiatedEvent e -> new Entry(
                    PaymentStatus.PENDING,
                    actorOrDefault(metadataActor, e.userId().toString()),
                    null
            );
            case PaymentHeldForReviewEvent e -> new Entry(
                    PaymentStatus.UNDER_REVIEW,
                    actorOrDefault(metadataActor, e.userId().toString()),
                    OutboundText.limit(e.reason())
            );
            case PaymentReleasedFromReviewEvent e -> new Entry(
                    PaymentStatus.PENDING,
                    e.reviewerId().toString(),
                    null
            );
            case PaymentRejectedInReviewEvent e -> new Entry(
                    PaymentStatus.CANCELLED,
                    e.reviewerId().toString(),
                    OutboundText.limit(e.reason())
            );
            case PaymentCompletedEvent e -> new Entry(
                    PaymentStatus.COMPLETED,
                    actorOrDefault(metadataActor, e.userId().toString()),
                    null
            );
            case PaymentCancelledEvent e -> new Entry(
                    PaymentStatus.CANCELLED,
                    actorOrDefault(metadataActor, e.userId().toString()),
                    OutboundText.limit(e.reason())
            );
            case PaymentProviderIntentCreatedEvent e -> new Entry(
                    PaymentStatus.PENDING,
                    metadataActor,
                    null
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported payment event for history projection: " + storedEvent.event().getClass().getName());
        };

        jdbcTemplate.update(
                INSERT,
                storedEvent.stream().id(),
                storedEvent.streamVersion(),
                storedEvent.eventType(),
                entry.status().name(),
                entry.actor(),
                entry.reason(),
                Timestamp.from(storedEvent.occurredAt())
        );
    }

    private static String actorOrDefault(String metadataActor, String fallback) {
        return (metadataActor != null && !metadataActor.isBlank()) ? metadataActor : fallback;
    }

    private record Entry(PaymentStatus status, String actor, String reason) {
    }
}
