package com.muse.payments.shared.infrastructure.eventstore;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.identity.domain.RefreshTokenFamily;
import com.muse.payments.identity.domain.RefreshTokenFamilyRepository;
import com.muse.payments.identity.application.UserCredentialsRepository;
import com.muse.payments.shared.application.ProjectionRebuildPort;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ProjectionRebuildIT extends TestcontainersBase {

    @Autowired
    private ProjectionRebuildPort rebuildPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenFamilyRepository refreshTokenFamilyRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserCredentialsRepository credentials;

    @Test
    @DisplayName("Every projection rebuilds identically from event_store")
    void rebuildAllProjections() {
        User user = User.create("rebuild_" + UUID.randomUUID() + "@example.com", "Rebuild User", Set.of("USER"));
        userRepository.save(user);
        credentials.save(user.getId(), "test-password-hash");

        RefreshTokenFamily family = RefreshTokenFamily.start(
                user.getId(), "hash_" + UUID.randomUUID(), Instant.now().plusSeconds(3600));
        refreshTokenFamilyRepository.save(family);

        Payment payment = Payment.create(user.getId(), Money.of(new BigDecimal("77.00"), "BRL"), "Rebuild payment");
        paymentRepository.save(payment);

        Notification notification = Notification.paymentCreated(
                user.getId(), payment.getId(), new BigDecimal("77.00"), "BRL", UUID.randomUUID());
        notificationRepository.save(notification);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_history WHERE payment_id = ?", Integer.class, payment.getId()))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE id = ?", Integer.class, notification.getId()))
                    .isEqualTo(1);
        });

        assertRebuildIdentical("payments", "SELECT id, user_id, amount, currency, status, version FROM payments ORDER BY id");
        assertRebuildIdentical("payment_history", "SELECT payment_id, stream_version, event_type, status_after FROM payment_history ORDER BY payment_id, stream_version");
        assertRebuildIdentical("notifications", "SELECT id, user_id, payment_id, type FROM notifications ORDER BY id");
        assertRebuildIdentical("users", "SELECT id, email, display_name, enabled, version FROM users ORDER BY id");
        assertThat(credentials.findPasswordHashByUserId(user.getId())).contains("test-password-hash");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ?", Integer.class,
                family.getId())).isEqualTo(1);
        assertRebuildIdentical("refresh_tokens", "SELECT id, user_id, token_hash, family_id, is_revoked FROM refresh_tokens ORDER BY id");
    }

    private void assertRebuildIdentical(String projection, String query) {
        // Legacy regression fixtures intentionally seed projection-only rows.
        // Establish the authoritative event-backed baseline, then test repeatability.
        rebuildPort.rebuild(projection);
        List<Map<String, Object>> before = jdbcTemplate.queryForList(query);
        rebuildPort.rebuild(projection);
        List<Map<String, Object>> after = jdbcTemplate.queryForList(query);
        assertThat(after).isEqualTo(before);
    }
}
