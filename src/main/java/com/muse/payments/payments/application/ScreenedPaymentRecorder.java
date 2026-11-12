package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentCreatedEvent;
import com.muse.payments.payments.domain.PaymentHeldForReviewEvent;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
public class ScreenedPaymentRecorder {

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;

    public ScreenedPaymentRecorder(PaymentRepository paymentRepository, OutboxPort outboxPort) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
    }

    @Transactional
    public PaymentResponse persist(ScreenedPayment screened) {
        Objects.requireNonNull(screened, "screened payment must not be null");
        Payment payment = screened.outcome() == FraudScreeningOutcome.UNDER_REVIEW
                ? Payment.createUnderReview(
                        screened.paymentId(), screened.userId(), screened.money(), screened.description(), screened.reason())
                : Payment.create(screened.paymentId(), screened.userId(), screened.money(), screened.description());
        Payment saved = paymentRepository.save(payment);
        for (var domainEvent : payment.pullDomainEvents()) {
            if (domainEvent instanceof PaymentCreatedEvent event) {
                appendCreated(event);
            } else if (domainEvent instanceof PaymentHeldForReviewEvent event) {
                appendHeld(event);
            }
        }
        return PaymentResponse.from(saved);
    }

    private void appendCreated(PaymentCreatedEvent event) {
        outboxPort.append(IntegrationEvent.create(
                "Payment",
                event.paymentId(),
                "PaymentCreated",
                Map.of(
                        "paymentId", event.paymentId(),
                        "userId", event.userId(),
                        "amount", event.amount(),
                        "currency", event.currency(),
                        "status", "PENDING",
                        "description", event.description() != null ? event.description() : ""
                ),
                Map.of("aggregateType", "Payment")
        ));
    }

    private void appendHeld(PaymentHeldForReviewEvent event) {
        outboxPort.append(IntegrationEvent.create(
                "Payment",
                event.paymentId(),
                "PaymentHeldForReview",
                Map.of(
                        "paymentId", event.paymentId(),
                        "userId", event.userId(),
                        "amount", event.amount(),
                        "currency", event.currency(),
                        "status", "UNDER_REVIEW",
                        "description", event.description() != null ? event.description() : "",
                        "reason", event.reason() != null ? event.reason() : ""
                ),
                Map.of("aggregateType", "Payment")
        ));
    }
}
