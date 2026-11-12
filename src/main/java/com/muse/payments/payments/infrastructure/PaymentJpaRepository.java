package com.muse.payments.payments.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    List<PaymentJpaEntity> findByUserId(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);
}
