package com.muse.payments.payments.application;

import com.muse.payments.shared.application.Query;

public record ListHeldPaymentsQuery(int page, int size, String sort) implements Query<PageResponse<PaymentResponse>> {
}
