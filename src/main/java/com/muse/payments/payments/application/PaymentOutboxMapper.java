package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.PaymentCancelledEvent;
import com.muse.payments.payments.domain.PaymentCompletedEvent;
import com.muse.payments.payments.domain.PaymentHeldForReviewEvent;
import com.muse.payments.payments.domain.PaymentInitiatedEvent;
import com.muse.payments.payments.domain.PaymentProviderIntentCreatedEvent;
import com.muse.payments.payments.domain.PaymentRejectedInReviewEvent;
import com.muse.payments.payments.domain.PaymentReleasedFromReviewEvent;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.domain.DomainEvent;

import java.util.Map;

/**
 * One mapping from payment domain events to outbox rows. Reasons stored on the wire are truncated
 * the same way the payment row truncates them.
 */
public final class PaymentOutboxMapper {

    private PaymentOutboxMapper() {
    }

    public static IntegrationEvent toIntegrationEvent(DomainEvent event) {
        if (event instanceof PaymentInitiatedEvent initiated) {
            return IntegrationEvent.create(
                    "Payment",
                    initiated.paymentId(),
                    "PaymentCreated",
                    Map.of(
                            "paymentId", initiated.paymentId(),
                            "userId", initiated.userId(),
                            "amount", initiated.amount(),
                            "currency", initiated.currency(),
                            "status", "PENDING",
                            "description", text(initiated.description())
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        if (event instanceof PaymentReleasedFromReviewEvent released) {
            return IntegrationEvent.create(
                    "Payment",
                    released.paymentId(),
                    "PaymentCreated",
                    Map.of(
                            "paymentId", released.paymentId(),
                            "userId", released.userId(),
                            "amount", released.amount(),
                            "currency", released.currency(),
                            "status", "PENDING",
                            "description", text(released.description())
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        if (event instanceof PaymentHeldForReviewEvent held) {
            return IntegrationEvent.create(
                    "Payment",
                    held.paymentId(),
                    "PaymentHeldForReview",
                    Map.of(
                            "paymentId", held.paymentId(),
                            "userId", held.userId(),
                            "amount", held.amount(),
                            "currency", held.currency(),
                            "status", "UNDER_REVIEW",
                            "description", text(held.description()),
                            "reason", text(OutboundText.limit(held.reason()))
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        if (event instanceof PaymentCompletedEvent completed) {
            return IntegrationEvent.create(
                    "Payment",
                    completed.paymentId(),
                    "PaymentCompleted",
                    Map.of(
                            "paymentId", completed.paymentId(),
                            "userId", completed.userId()
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        if (event instanceof PaymentCancelledEvent cancelled) {
            return IntegrationEvent.create(
                    "Payment",
                    cancelled.paymentId(),
                    "PaymentCancelled",
                    Map.of(
                            "paymentId", cancelled.paymentId(),
                            "userId", cancelled.userId(),
                            "reason", text(OutboundText.limit(cancelled.reason()))
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        if (event instanceof PaymentRejectedInReviewEvent rejected) {
            return IntegrationEvent.create(
                    "Payment",
                    rejected.paymentId(),
                    "PaymentCancelled",
                    Map.of(
                            "paymentId", rejected.paymentId(),
                            "userId", rejected.userId(),
                            "reason", text(OutboundText.limit(rejected.reason()))
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        if (event instanceof PaymentProviderIntentCreatedEvent intent) {
            return IntegrationEvent.create(
                    "Payment",
                    intent.paymentId(),
                    "PaymentProviderIntentCreated",
                    Map.of(
                            "paymentId", intent.paymentId(),
                            "provider", intent.provider(),
                            "providerReference", intent.providerReference()
                    ),
                    Map.of("aggregateType", "Payment")
            );
        }
        return null;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
