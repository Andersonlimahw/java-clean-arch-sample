package com.muse.payments.payments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    List<Payment> findByUserId(UUID userId, int page, int size);

    long countByUserId(UUID userId);
}
