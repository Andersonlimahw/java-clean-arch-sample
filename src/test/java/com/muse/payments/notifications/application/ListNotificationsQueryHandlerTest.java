package com.muse.payments.notifications.application;

import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ListNotificationsQueryHandlerTest {

    @Test
    void maps_only_the_requested_user_notifications_to_api_responses() {
        UUID userId = UUID.randomUUID();
        Notification notification = new Notification(
                UUID.randomUUID(), userId, UUID.randomUUID(), "PAYMENT_CREATED",
                "Payment Received", "A payment was created.", Instant.parse("2026-01-01T00:00:00Z")
        );
        NotificationRepository repository = new NotificationRepository() {
            @Override
            public Notification save(Notification value) {
                return value;
            }

            @Override
            public List<Notification> findByUserId(UUID requestedUserId) {
                assertThat(requestedUserId).isEqualTo(userId);
                return List.of(notification);
            }

            @Override
            public long countByPaymentId(UUID paymentId) {
                return 0;
            }
        };

        List<NotificationResponse> result = new ListNotificationsQueryHandler(repository)
                .handle(new ListNotificationsQuery(userId));

        assertThat(result).containsExactly(NotificationResponse.from(notification));
    }
}
