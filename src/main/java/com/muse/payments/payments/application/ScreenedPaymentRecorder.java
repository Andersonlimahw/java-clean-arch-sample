package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            IntegrationEvent integrationEvent = PaymentOutboxMapper.toIntegrationEvent(domainEvent);
            if (integrationEvent != null) {
                outboxPort.append(integrationEvent);
            }
        }
        return PaymentResponse.from(saved);
    }
}
