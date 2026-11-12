package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.PaymentDeclinedException;
import com.muse.payments.shared.application.CommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class CreatePaymentCommandHandler implements CommandHandler<CreatePaymentCommand, PaymentResponse> {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentCommandHandler.class);

    private final FraudScreeningPort fraudScreeningPort;
    private final ScreenedPaymentRecorder recorder;

    public CreatePaymentCommandHandler(FraudScreeningPort fraudScreeningPort, ScreenedPaymentRecorder recorder) {
        this.fraudScreeningPort = Objects.requireNonNull(fraudScreeningPort);
        this.recorder = Objects.requireNonNull(recorder);
    }

    /**
     * Screens outside any transaction. A decline throws {@link PaymentDeclinedException};
     * the provider reason is logged and is not part of the exception message.
     */
    public ScreenedPayment screen(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Money money = Money.of(command.amount(), command.currency());
        UUID paymentId = UUID.randomUUID();
        FraudScreeningResult screening = fraudScreeningPort.screen(
                new FraudScreeningRequest(paymentId, command.userId(), money));
        if (screening.outcome() == FraudScreeningOutcome.DECLINED) {
            log.info("Fraud screening declined payment {} for user {}: {}",
                    paymentId, command.userId(), screening.reason());
            throw new PaymentDeclinedException();
        }
        if (screening.outcome() == FraudScreeningOutcome.UNDER_REVIEW) {
            log.info("Fraud screening held payment {} for user {}: {}",
                    paymentId, command.userId(), screening.reason());
        }
        return new ScreenedPayment(
                paymentId, command.userId(), money, command.description(), screening.outcome(), screening.reason());
    }

    public PaymentResponse persist(ScreenedPayment screened) {
        return recorder.persist(screened);
    }

    @Override
    public PaymentResponse handle(CreatePaymentCommand command) {
        return persist(screen(command));
    }
}
