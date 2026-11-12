package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Query;

import java.util.UUID;

public record GetPaymentByIdQuery(
        UUID paymentId,
        UUID userId,
        boolean adminRead
) implements Query<PaymentResponse> {
}
