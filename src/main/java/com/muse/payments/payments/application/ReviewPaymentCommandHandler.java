package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ReviewPaymentCommandHandler implements CommandHandler<ReviewPaymentCommand, PaymentResponse> {

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;
    private final PaymentCachePort paymentCachePort;

    public ReviewPaymentCommandHandler(
            PaymentRepository paymentRepository,
            OutboxPort outboxPort,
            PaymentCachePort paymentCachePort
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
        this.paymentCachePort = Objects.requireNonNull(paymentCachePort);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FRAUD_REVIEWER')")
    public PaymentResponse handle(ReviewPaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Payment payment = paymentRepository.findById(command.paymentId())
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + command.paymentId()));
        if (payment.isOwnedBy(command.userId())) {
            throw new AccessDeniedException("The payment owner cannot review their own payment");
        }
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
        return PaymentResponse.from(saved);
    }
}
