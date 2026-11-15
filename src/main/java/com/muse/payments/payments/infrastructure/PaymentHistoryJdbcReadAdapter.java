package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.application.PaymentHistoryItemResponse;
import com.muse.payments.payments.application.PaymentHistoryReadRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class PaymentHistoryJdbcReadAdapter implements PaymentHistoryReadRepository {

    private static final String SELECT_PAGE = """
            SELECT event_type, occurred_at, status_after, actor_id, reason
            FROM payment_history
            WHERE payment_id = ?
            ORDER BY stream_version ASC
            LIMIT ? OFFSET ?
            """;

    private static final String COUNT_ALL = """
            SELECT COUNT(*)
            FROM payment_history
            WHERE payment_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PaymentHistoryJdbcReadAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public List<PaymentHistoryItemResponse> findByPaymentId(UUID paymentId, int page, int size) {
        long offset = (long) page * size;
        return jdbcTemplate.query(
                SELECT_PAGE,
                (rs, rowNum) -> new PaymentHistoryItemResponse(
                        rs.getString("event_type"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        PaymentStatus.valueOf(rs.getString("status_after")),
                        rs.getString("actor_id"),
                        rs.getString("reason")
                ),
                paymentId,
                size,
                offset
        );
    }

    @Override
    public long countByPaymentId(UUID paymentId) {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL, Long.class, paymentId);
        return count == null ? 0L : count;
    }
}
