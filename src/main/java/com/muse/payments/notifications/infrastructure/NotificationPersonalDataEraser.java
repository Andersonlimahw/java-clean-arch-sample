package com.muse.payments.notifications.infrastructure;

import com.muse.payments.shared.application.PersonalDataErasureParticipant;
import com.muse.payments.shared.domain.PersonalData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Notifications part of an erasure: messages can echo the customer's cancellation reason. */
@Component
public class NotificationPersonalDataEraser implements PersonalDataErasureParticipant {

    private final JdbcTemplate jdbcTemplate;

    public NotificationPersonalDataEraser(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID subjectId) {
        jdbcTemplate.update("UPDATE notifications SET message = ? WHERE user_id = ?",
                PersonalData.REDACTED, Objects.requireNonNull(subjectId, "subjectId is required"));
    }
}
