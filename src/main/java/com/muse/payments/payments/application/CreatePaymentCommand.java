package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Command;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentCommand(
        UUID userId,
        BigDecimal amount,
        String currency,
        String description
) implements Command<PaymentResponse> {
}
