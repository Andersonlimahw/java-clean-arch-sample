package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.domain.PaymentCancelledEvent;
import com.muse.payments.payments.domain.PaymentCompletedEvent;
import com.muse.payments.payments.domain.PaymentHeldForReviewEvent;
import com.muse.payments.payments.domain.PaymentInitiatedEvent;
import com.muse.payments.payments.domain.PaymentProviderIntentCreatedEvent;
import com.muse.payments.payments.domain.PaymentRejectedInReviewEvent;
import com.muse.payments.payments.domain.PaymentReleasedFromReviewEvent;
import com.muse.payments.shared.application.EventTypeRegistration;
import com.muse.payments.shared.application.PersonalDataFields;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class PaymentEventRegistryConfiguration {

    public static final String STREAM_TYPE = "Payment";

    @Bean
    EventTypeRegistration paymentInitiatedRegistration() {
        return new EventTypeRegistration("payments.payment-initiated", 1, PaymentInitiatedEvent.class);
    }

    @Bean
    EventTypeRegistration paymentHeldForReviewRegistration() {
        return new EventTypeRegistration("payments.payment-held-for-review", 1, PaymentHeldForReviewEvent.class);
    }

    @Bean
    EventTypeRegistration paymentReleasedFromReviewRegistration() {
        return new EventTypeRegistration("payments.payment-released-from-review", 1, PaymentReleasedFromReviewEvent.class);
    }

    @Bean
    EventTypeRegistration paymentRejectedInReviewRegistration() {
        return new EventTypeRegistration("payments.payment-rejected-in-review", 1, PaymentRejectedInReviewEvent.class);
    }

    @Bean
    EventTypeRegistration paymentCompletedRegistration() {
        return new EventTypeRegistration("payments.payment-completed", 1, PaymentCompletedEvent.class);
    }

    @Bean
    EventTypeRegistration paymentCancelledRegistration() {
        return new EventTypeRegistration("payments.payment-cancelled", 1, PaymentCancelledEvent.class);
    }

    @Bean
    PersonalDataFields paymentInitiatedPersonalData() {
        return new PersonalDataFields("payments.payment-initiated", "userId", Set.of("description"));
    }

    @Bean
    PersonalDataFields paymentHeldForReviewPersonalData() {
        return new PersonalDataFields("payments.payment-held-for-review", "userId", Set.of("description"));
    }

    @Bean
    PersonalDataFields paymentReleasedFromReviewPersonalData() {
        return new PersonalDataFields("payments.payment-released-from-review", "userId", Set.of("description"));
    }

    @Bean
    PersonalDataFields paymentCancelledPersonalData() {
        return new PersonalDataFields("payments.payment-cancelled", "userId", Set.of("reason"));
    }

    @Bean
    PersonalDataFields paymentRejectedInReviewPersonalData() {
        return new PersonalDataFields("payments.payment-rejected-in-review", "userId", Set.of("reason"));
    }

    @Bean
    EventTypeRegistration paymentProviderIntentCreatedRegistration() {
        return new EventTypeRegistration(
                "payments.payment-provider-intent-created", 1, PaymentProviderIntentCreatedEvent.class);
    }
}
