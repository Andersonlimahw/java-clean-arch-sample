package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentCancelledEvent;
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
public class CancelPaymentCommandHandler implements CommandHandler<CancelPaymentCommand, PaymentResponse> {

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;

    public CancelPaymentCommandHandler(PaymentRepository paymentRepository, OutboxPort outboxPort) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
    }

    @Override
    @Transactional
    public PaymentResponse handle(CancelPaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Payment payment = paymentRepository.findById(command.paymentId())
                .filter(p -> p.isOwnedBy(command.userId()))
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + command.paymentId()));

        payment.cancel(command.reason());
        Payment saved = paymentRepository.save(payment);

        for (var domainEvent : payment.pullDomainEvents()) {
            if (domainEvent instanceof PaymentCancelledEvent event) {
                IntegrationEvent integrationEvent = IntegrationEvent.create(
                        "Payment",
                        event.paymentId(),
                        "PaymentCancelled",
                        Map.of(
                                "paymentId", event.paymentId(),
                                "userId", event.userId(),
                                "reason", event.reason() != null ? event.reason() : ""
                        ),
                        Map.of("aggregateType", "Payment")
                );
                outboxPort.append(integrationEvent);
            }
        }

        return PaymentResponse.from(saved);
    }
}
