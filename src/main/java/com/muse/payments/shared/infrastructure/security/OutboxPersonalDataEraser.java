package com.muse.payments.shared.infrastructure.security;

import com.muse.payments.shared.application.PersonalDataErasureParticipant;
import com.muse.payments.shared.domain.PersonalData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Redacts personal text in integration payloads still held in {@code outbox_events} (published rows
 * are kept for the retention window). Messages already delivered to consumers are out of reach.
 */
@Component
public class OutboxPersonalDataEraser implements PersonalDataErasureParticipant {

    private static final String REDACT = """
            UPDATE outbox_events o
            SET payload = (
                SELECT jsonb_object_agg(
                    e.key,
                    CASE WHEN e.key IN ('email', 'displayName', 'description', 'reason')
                              AND jsonb_typeof(e.value) = 'string'
                         THEN to_jsonb(?::text)
                         ELSE e.value
                    END)
                FROM jsonb_each(o.payload) e)
            WHERE o.payload ->> 'userId' = ?
              AND jsonb_exists_any(o.payload, ARRAY['email', 'displayName', 'description', 'reason'])
            """;

    private final JdbcTemplate jdbcTemplate;

    public OutboxPersonalDataEraser(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID subjectId) {
        jdbcTemplate.update(REDACT, PersonalData.REDACTED,
                Objects.requireNonNull(subjectId, "subjectId is required").toString());
    }
}
