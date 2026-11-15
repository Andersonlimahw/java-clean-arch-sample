package com.muse.payments.notifications.infrastructure;

import com.muse.payments.notifications.domain.NotificationRecordedEvent;
import com.muse.payments.shared.application.EventTypeRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationEventRegistryConfiguration {

    public static final String STREAM_TYPE = "Notification";

    @Bean
    EventTypeRegistration notificationRecordedRegistration() {
        return new EventTypeRegistration("notifications.notification-recorded", 1, NotificationRecordedEvent.class);
    }
}
