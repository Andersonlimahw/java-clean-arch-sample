package com.muse.payments.notifications.infrastructure;

import com.muse.payments.notifications.domain.NotificationRecordedEvent;
import com.muse.payments.shared.application.EventTypeRegistration;
import com.muse.payments.shared.application.PersonalDataFields;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class NotificationEventRegistryConfiguration {

    public static final String STREAM_TYPE = "Notification";

    @Bean
    EventTypeRegistration notificationRecordedRegistration() {
        return new EventTypeRegistration("notifications.notification-recorded", 1, NotificationRecordedEvent.class);
    }

    /** A cancellation message echoes the customer's free-text reason. */
    @Bean
    PersonalDataFields notificationRecordedPersonalData() {
        return new PersonalDataFields("notifications.notification-recorded", "userId", Set.of("message"));
    }
}
