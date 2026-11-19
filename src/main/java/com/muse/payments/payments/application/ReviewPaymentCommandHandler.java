package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ReviewPaymentCommandHandler implements CommandHandler<ReviewPaymentCommand, PaymentResponse> {

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;
    private final PaymentCachePort paymentCachePort;
    private final ProviderPaymentInitiator initiateProviderPayment;

    public ReviewPaymentCommandHandler(
            PaymentRepository paymentRepository,
            OutboxPort outboxPort,
            PaymentCachePort paymentCachePort,
            ProviderPaymentInitiator initiateProviderPayment
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
        this.paymentCachePort = Objects.requireNonNull(paymentCachePort);
        this.initiateProviderPayment = Objects.requireNonNull(initiateProviderPayment);
    }

    @Override
    @Transactional
    public PaymentResponse handle(ReviewPaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Payment payment = paymentRepository.findById(command.paymentId())
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + command.paymentId()));
        Instant reviewedAt = Instant.now();
        if (command.decision() == ReviewDecision.APPROVE) {
            payment.releaseFromReview(command.userId(), reviewedAt);
        } else {
            payment.rejectFromReview(command.userId(), reviewedAt, "Rejected in fraud review");
        }
        Payment saved = paymentRepository.save(payment);
        for (var domainEvent : payment.pullDomainEvents()) {
            IntegrationEvent integrationEvent = PaymentOutboxMapper.toIntegrationEvent(domainEvent);
            if (integrationEvent != null) {
                outboxPort.append(integrationEvent);
            }
        }
        paymentCachePort.evictAfterCommit(saved.getId());
        if (command.decision() == ReviewDecision.APPROVE) {
            // The payment becomes PENDING here; the provider call runs after this commit.
            initiateProviderPayment.initiateAfterCommit(saved.getId());
        }
        return PaymentResponse.from(saved);
    }
}
