package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentCreatedEvent;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
public class CreatePaymentCommandHandler implements CommandHandler<CreatePaymentCommand, PaymentResponse> {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentCommandHandler.class);

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;
    private final FraudScreeningService fraudScreeningService;

    public CreatePaymentCommandHandler(
            PaymentRepository paymentRepository,
            OutboxPort outboxPort,
            FraudScreeningService fraudScreeningService
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
        this.fraudScreeningService = Objects.requireNonNull(fraudScreeningService);
    }

    @Override
    @Transactional
    public PaymentResponse handle(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Money money = Money.of(command.amount(), command.currency());
        FraudScreeningResult screening = fraudScreeningService.screen(
                new FraudScreeningRequest(command.userId(), money.amount(), money.currency()));
        if (screening.outcome() == FraudScreeningOutcome.DECLINED) {
            log.info("Fraud screening declined a payment for user {}: {}", command.userId(), screening.reason());
            throw new ValidationException(
                    "Payment was not created because fraud screening declined it: " + screening.reason());
        }

        Payment payment = screening.outcome() == FraudScreeningOutcome.UNDER_REVIEW
                ? held(command, money, screening.reason())
                : Payment.create(command.userId(), money, command.description());

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

    private Payment held(CreatePaymentCommand command, Money money, String reason) {
        log.info("Fraud screening held a payment for review for user {}: {}", command.userId(), reason);
        return Payment.createUnderReview(command.userId(), money, command.description());
    }
}
