package com.muse.payments.payments.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Page<PaymentJpaEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);
}
