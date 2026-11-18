package com.muse.payments.payments.infrastructure.psp;

import com.muse.payments.payments.application.PaymentProviderEventStorePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcPaymentProviderEventStore implements PaymentProviderEventStorePort {

    private static final String CLAIM = """
            INSERT INTO payment_provider_events (provider, event_id, event_type, payment_id, outcome)
            VALUES (?, ?, ?, ?, 'PROCESSING')
            ON CONFLICT (provider, event_id) DO NOTHING
            """;

    private static final String RESOLVE = """
            UPDATE payment_provider_events
            SET outcome = ?
            WHERE provider = ? AND event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentProviderEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public boolean claim(String provider, String eventId, String eventType, UUID paymentId) {
        int inserted = jdbcTemplate.update(CLAIM, provider, eventId, eventType, paymentId);
        return inserted == 1;
    }

    @Override
    public void resolve(String provider, String eventId, String outcome) {
        jdbcTemplate.update(RESOLVE, outcome, provider, eventId);
    }
}
