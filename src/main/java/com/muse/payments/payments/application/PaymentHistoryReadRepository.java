package com.muse.payments.payments.application;

import java.util.List;
import java.util.UUID;

public interface PaymentHistoryReadRepository {

    List<PaymentHistoryItemResponse> findByPaymentId(UUID paymentId, int page, int size);

    long countByPaymentId(UUID paymentId);
}
