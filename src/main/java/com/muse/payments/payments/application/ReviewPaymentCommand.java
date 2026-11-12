package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Command;

import java.util.UUID;

public record ReviewPaymentCommand(
        UUID paymentId,
        UUID userId,
        ReviewDecision decision
) implements Command<PaymentResponse> {
}
