package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.application.PaymentCachePort;
import com.muse.payments.shared.application.PersonalDataErasureParticipant;
import com.muse.payments.shared.domain.PersonalData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Payments part of an erasure. Redacts the free-text columns that a rebuild would also read as
 * redacted once the user's key is gone, and evicts the cached payment responses after commit.
 * {@code user_id} stays: it is a pseudonymous reference kept for financial record retention.
 */
@Component
public class PaymentPersonalDataEraser implements PersonalDataErasureParticipant {

    private static final String SELECT_PAYMENT_IDS = "SELECT id FROM payments WHERE user_id = ?";
    private static final String REDACT_DESCRIPTIONS = """
            UPDATE payments
            SET description = ?
            WHERE user_id = ? AND description IS NOT NULL
            """;
    private static final String REDACT_HISTORY_REASONS = """
            UPDATE payment_history
            SET reason = ?
            WHERE reason IS NOT NULL
              AND event_type IN ('payments.payment-cancelled', 'payments.payment-rejected-in-review')
              AND payment_id IN (SELECT id FROM payments WHERE user_id = ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PaymentCachePort paymentCache;
    private final PaymentWriteRateLimiter writeRateLimiter;

    public PaymentPersonalDataEraser(
            JdbcTemplate jdbcTemplate,
            PaymentCachePort paymentCache,
            PaymentWriteRateLimiter writeRateLimiter
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.paymentCache = Objects.requireNonNull(paymentCache);
        this.writeRateLimiter = Objects.requireNonNull(writeRateLimiter);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID subjectId) {
        Objects.requireNonNull(subjectId, "subjectId is required");
        List<UUID> paymentIds = jdbcTemplate.queryForList(SELECT_PAYMENT_IDS, UUID.class, subjectId);
        jdbcTemplate.update(REDACT_DESCRIPTIONS, PersonalData.REDACTED, subjectId);
        jdbcTemplate.update(REDACT_HISTORY_REASONS, PersonalData.REDACTED, subjectId);
        paymentIds.forEach(paymentCache::evictAfterCommit);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                writeRateLimiter.forget(subjectId);
            }
        });
    }
}
