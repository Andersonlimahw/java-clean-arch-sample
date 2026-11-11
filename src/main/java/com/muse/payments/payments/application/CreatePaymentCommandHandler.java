package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentCreatedEvent;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
public class CreatePaymentCommandHandler implements CommandHandler<CreatePaymentCommand, PaymentResponse> {

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;

    public CreatePaymentCommandHandler(PaymentRepository paymentRepository, OutboxPort outboxPort) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
    }

    @Override
    @Transactional
    public PaymentResponse handle(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Money money = Money.of(command.amount(), command.currency());
        Payment payment = Payment.create(command.userId(), money, command.description());

        Payment saved = paymentRepository.save(payment);

        for (var domainEvent : payment.pullDomainEvents()) {
            if (domainEvent instanceof PaymentCreatedEvent event) {
                IntegrationEvent integrationEvent = IntegrationEvent.create(
                        "Payment",
                        event.paymentId(),
                        "PaymentCreated",
                        Map.of(
                                "paymentId", event.paymentId(),
                                "userId", event.userId(),
                                "amount", event.amount(),
                                "currency", event.currency(),
                                "description", event.description() != null ? event.description() : ""
                        ),
                        Map.of("aggregateType", "Payment")
                );
                outboxPort.append(integrationEvent);
            }
        }

        return PaymentResponse.from(saved);
    }
}
