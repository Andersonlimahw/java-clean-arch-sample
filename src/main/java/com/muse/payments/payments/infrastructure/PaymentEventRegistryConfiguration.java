package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.domain.PaymentCancelledEvent;
import com.muse.payments.payments.domain.PaymentCompletedEvent;
import com.muse.payments.payments.domain.PaymentHeldForReviewEvent;
import com.muse.payments.payments.domain.PaymentInitiatedEvent;
import com.muse.payments.payments.domain.PaymentRejectedInReviewEvent;
import com.muse.payments.payments.domain.PaymentReleasedFromReviewEvent;
import com.muse.payments.shared.application.EventTypeRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
