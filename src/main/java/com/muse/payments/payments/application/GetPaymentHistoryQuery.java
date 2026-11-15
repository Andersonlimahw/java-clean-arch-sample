package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Query;

import java.util.Objects;
import java.util.UUID;

public record GetPaymentHistoryQuery(
        UUID paymentId,
        UUID userId,
        boolean readAny,
        int page,
        int size
) implements Query<PageResponse<PaymentHistoryItemResponse>> {

    public GetPaymentHistoryQuery {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
