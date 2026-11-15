package com.muse.payments.payments;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.PaymentReadRepository;
import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PaymentReplayProjectionIT extends TestcontainersBase {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentReadRepository paymentReadRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Replaying event stream matches payments projection across all six payment event types")
    void replayMatchesProjectionForAllSixEventTypes() {
        UUID userId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();

        // 1. Initiated -> Completed
        Payment p1 = Payment.create(userId, Money.of(new BigDecimal("120.00"), "BRL"), "Initiated then completed");
        paymentRepository.save(p1);
        Payment p1Loaded = paymentRepository.findById(p1.getId()).orElseThrow();
        p1Loaded.complete();
        paymentRepository.save(p1Loaded);
        assertReplayEqualsProjection(p1.getId(), 2L, PaymentStatus.COMPLETED);

        // 2. Initiated -> Cancelled
        Payment p2 = Payment.create(userId, Money.of(new BigDecimal("50.00"), "USD"), "Initiated then cancelled");
        paymentRepository.save(p2);
        Payment p2Loaded = paymentRepository.findById(p2.getId()).orElseThrow();
        p2Loaded.cancel("Customer cancelled");
        paymentRepository.save(p2Loaded);
        assertReplayEqualsProjection(p2.getId(), 2L, PaymentStatus.CANCELLED);

        // 3. Held -> Released -> Completed
        Payment p3 = Payment.createUnderReview(
                UUID.randomUUID(), userId, Money.of(new BigDecimal("200.00"), "EUR"), "Held then released", "provider timeout");
        paymentRepository.save(p3);
        Payment p3Loaded = paymentRepository.findById(p3.getId()).orElseThrow();
        p3Loaded.releaseFromReview(reviewerId, Instant.now());
        paymentRepository.save(p3Loaded);
        Payment p3Released = paymentRepository.findById(p3.getId()).orElseThrow();
        p3Released.complete();
        paymentRepository.save(p3Released);
        assertReplayEqualsProjection(p3.getId(), 3L, PaymentStatus.COMPLETED);

        // 4. Held -> Rejected
        Payment p4 = Payment.createUnderReview(
                UUID.randomUUID(), userId, Money.of(new BigDecimal("300.00"), "BRL"), "Held then rejected", "manual review");
        paymentRepository.save(p4);
        Payment p4Loaded = paymentRepository.findById(p4.getId()).orElseThrow();
        p4Loaded.rejectFromReview(reviewerId, Instant.now(), "Suspicious");
        paymentRepository.save(p4Loaded);
        assertReplayEqualsProjection(p4.getId(), 2L, PaymentStatus.CANCELLED);
    }

    private void assertReplayEqualsProjection(UUID paymentId, long expectedEvents, PaymentStatus expectedStatus) {
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT stream_version FROM event_store WHERE stream_type = 'Payment' AND stream_id = ? ORDER BY stream_version",
                Long.class,
                paymentId
        );
        assertThat(versions).hasSize((int) expectedEvents);
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).isEqualTo(i + 1L);
        }

        Payment replayed = paymentRepository.findById(paymentId).orElseThrow();
        Payment projected = paymentReadRepository.findById(paymentId).orElseThrow();

        assertThat(replayed.getStatus()).isEqualTo(expectedStatus);
        assertThat(projected.getStatus()).isEqualTo(replayed.getStatus());
        assertThat(projected.getUserId()).isEqualTo(replayed.getUserId());
        assertThat(projected.getMoney()).isEqualTo(replayed.getMoney());
        assertThat(projected.getDescription()).isEqualTo(replayed.getDescription());
        assertThat(projected.getScreeningReason()).isEqualTo(replayed.getScreeningReason());
        assertThat(projected.getHeldReason()).isEqualTo(replayed.getHeldReason());
        assertThat(projected.getReviewerId()).isEqualTo(replayed.getReviewerId());
        assertThat(projected.getVersion()).isEqualTo(expectedEvents - 1);
        assertThat(replayed.getVersion()).isEqualTo(expectedEvents - 1);
    }
}
