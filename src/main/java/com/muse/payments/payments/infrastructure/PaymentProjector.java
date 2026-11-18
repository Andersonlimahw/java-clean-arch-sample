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
public class PaymentProjector implements SyncProjector<DomainEvent>, RebuildableProjection {

    public static final String PROJECTION_NAME = "payments";

    private static final String UPSERT_INITIATED = """
            INSERT INTO payments (
                id, user_id, amount, currency, description, status,
                created_at, updated_at, version, screening_reason, held_reason, reviewer_id, reviewed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                version = EXCLUDED.version
            """;

    private static final String UPSERT_HELD = """
            INSERT INTO payments (
                id, user_id, amount, currency, description, status,
                created_at, updated_at, version, screening_reason, held_reason, reviewer_id, reviewed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                screening_reason = EXCLUDED.screening_reason,
                held_reason = EXCLUDED.held_reason,
                updated_at = EXCLUDED.updated_at,
                version = EXCLUDED.version
            """;

    private static final String UPDATE_RELEASED = """
            UPDATE payments
            SET status = ?, screening_reason = NULL, reviewer_id = ?, reviewed_at = ?, updated_at = ?, version = ?
            WHERE id = ?
            """;

    private static final String UPDATE_REJECTED = """
            UPDATE payments
            SET status = ?, reviewer_id = ?, reviewed_at = ?, updated_at = ?, version = ?
            WHERE id = ?
            """;

    private static final String UPDATE_STATUS = """
            UPDATE payments
            SET status = ?, updated_at = ?, version = ?
            WHERE id = ?
            """;

    private static final String UPDATE_PROVIDER_INTENT = """
            UPDATE payments
            SET provider = ?, provider_reference = ?, updated_at = ?, version = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PaymentProjector(JdbcTemplate jdbcTemplate) {
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
        jdbcTemplate.execute("TRUNCATE TABLE payments");
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
            case PaymentInitiatedEvent e -> jdbcTemplate.update(
                    UPSERT_INITIATED,
                    e.paymentId(),
                    e.userId(),
                    e.amount(),
                    e.currency(),
                    e.description(),
                    PaymentStatus.PENDING.name(),
                    Timestamp.from(e.occurredAt()),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion
            );
            case PaymentHeldForReviewEvent e -> {
                String limitedReason = OutboundText.limit(e.reason());
                jdbcTemplate.update(
                        UPSERT_HELD,
                        e.paymentId(),
                        e.userId(),
                        e.amount(),
                        e.currency(),
                        e.description(),
                        PaymentStatus.UNDER_REVIEW.name(),
                        Timestamp.from(e.occurredAt()),
                        Timestamp.from(e.occurredAt()),
                        projectionVersion,
                        limitedReason,
                        limitedReason
                );
            }
            case PaymentReleasedFromReviewEvent e -> jdbcTemplate.update(
                    UPDATE_RELEASED,
                    PaymentStatus.PENDING.name(),
                    e.reviewerId(),
                    Timestamp.from(e.occurredAt()),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion,
                    e.paymentId()
            );
            case PaymentRejectedInReviewEvent e -> jdbcTemplate.update(
                    UPDATE_REJECTED,
                    PaymentStatus.CANCELLED.name(),
                    e.reviewerId(),
                    Timestamp.from(e.occurredAt()),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion,
                    e.paymentId()
            );
            case PaymentCompletedEvent e -> jdbcTemplate.update(
                    UPDATE_STATUS,
                    PaymentStatus.COMPLETED.name(),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion,
                    e.paymentId()
            );
            case PaymentCancelledEvent e -> jdbcTemplate.update(
                    UPDATE_STATUS,
                    PaymentStatus.CANCELLED.name(),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion,
                    e.paymentId()
            );
            case PaymentProviderIntentCreatedEvent e -> jdbcTemplate.update(
                    UPDATE_PROVIDER_INTENT,
                    e.provider(),
                    e.providerReference(),
                    Timestamp.from(e.occurredAt()),
                    projectionVersion,
                    e.paymentId()
            );
            default -> throw new IllegalArgumentException("Unsupported payment event for projection: " + event.getClass().getName());
        }
    }
}
