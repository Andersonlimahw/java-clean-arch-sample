package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentCancelledEvent;
import com.muse.payments.payments.domain.PaymentCreatedEvent;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
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
    public PaymentResponse handle(ReviewPaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Payment payment = paymentRepository.findById(command.paymentId())
                .filter(found -> found.isOwnedBy(command.userId()))
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + command.paymentId()));
        if (command.decision() == ReviewDecision.APPROVE) {
            payment.releaseFromReview();
        } else {
            payment.rejectFromReview();
        }
        Payment saved = paymentRepository.save(payment);
        for (var domainEvent : payment.pullDomainEvents()) {
            if (domainEvent instanceof PaymentCreatedEvent event) {
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
            } else if (domainEvent instanceof PaymentCancelledEvent event) {
                outboxPort.append(IntegrationEvent.create(
                        "Payment",
                        event.paymentId(),
                        "PaymentCancelled",
                        Map.of(
                                "paymentId", event.paymentId(),
                                "userId", event.userId(),
                                "reason", event.reason() != null ? event.reason() : ""
                        ),
                        Map.of("aggregateType", "Payment")
                ));
            }
        }
        paymentCachePort.evictAfterCommit(saved.getId());
        return PaymentResponse.from(saved);
    }
}
