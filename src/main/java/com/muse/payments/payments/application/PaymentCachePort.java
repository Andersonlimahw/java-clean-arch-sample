package com.muse.payments.payments.application;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface PaymentCachePort {

    Optional<PaymentResponse> get(UUID paymentId);

    void put(UUID paymentId, PaymentResponse response);

    void evict(UUID paymentId);

    void evictAfterCommit(UUID paymentId);

    PaymentResponse getOrLoad(UUID paymentId, Supplier<PaymentResponse> dbLoader);
}
