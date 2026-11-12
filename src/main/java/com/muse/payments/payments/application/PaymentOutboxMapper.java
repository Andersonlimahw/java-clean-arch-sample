package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.PaymentCancelledEvent;
import com.muse.payments.payments.domain.PaymentCompletedEvent;
import com.muse.payments.payments.domain.PaymentCreatedEvent;
import com.muse.payments.payments.domain.PaymentHeldForReviewEvent;
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
        if (event instanceof PaymentCreatedEvent created) {
            return IntegrationEvent.create(
                    "Payment",
                    created.paymentId(),
                    "PaymentCreated",
                    Map.of(
                            "paymentId", created.paymentId(),
                            "userId", created.userId(),
                            "amount", created.amount(),
                            "currency", created.currency(),
                            "status", "PENDING",
                            "description", text(created.description())
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
        return null;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
