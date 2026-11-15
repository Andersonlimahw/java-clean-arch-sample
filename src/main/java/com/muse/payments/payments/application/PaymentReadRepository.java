package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentSort;
import com.muse.payments.payments.domain.PaymentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentReadRepository {

    Optional<Payment> findById(UUID id);

    List<Payment> findByUserId(UUID userId, int page, int size, PaymentSort sort);

    long countByUserId(UUID userId);

    List<Payment> findByStatus(PaymentStatus status, int page, int size, PaymentSort sort);

    long countByStatus(PaymentStatus status);
}
