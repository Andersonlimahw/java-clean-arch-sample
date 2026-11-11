package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Command;

import java.util.UUID;

public record CancelPaymentCommand(
        UUID paymentId,
        UUID userId,
        String reason
) implements Command<PaymentResponse> {
}
