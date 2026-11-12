package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Query;

import java.util.UUID;

public record ListPaymentsQuery(
        UUID userId,
        int page,
        int size,
        String sort
) implements Query<PageResponse<PaymentResponse>> {

    public ListPaymentsQuery(UUID userId, int page, int size) {
        this(userId, page, size, null);
    }
}
