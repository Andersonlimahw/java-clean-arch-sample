package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Command;

import java.util.UUID;

public record ConfirmPaymentCommand(
        UUID paymentId,
        UUID userId
) implements Command<PaymentResponse> {
}
