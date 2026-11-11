package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Query;

import java.util.UUID;

public record ListPaymentsQuery(
        UUID userId,
        int page,
        int size
) implements Query<PageResponse<PaymentResponse>> {
}
