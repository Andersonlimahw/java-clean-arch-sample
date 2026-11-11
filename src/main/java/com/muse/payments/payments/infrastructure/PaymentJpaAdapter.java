package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentJpaAdapter implements PaymentRepository {

    private final PaymentJpaRepository paymentJpaRepository;

    public PaymentJpaAdapter(PaymentJpaRepository paymentJpaRepository) {
        this.paymentJpaRepository = Objects.requireNonNull(paymentJpaRepository);
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = PaymentJpaEntity.fromDomain(payment);
        PaymentJpaEntity saved = paymentJpaRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return paymentJpaRepository.findById(id).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public List<Payment> findByUserId(UUID userId, int page, int size) {
        return paymentJpaRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .getContent()
                .stream()
                .map(PaymentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(UUID userId) {
        return paymentJpaRepository.countByUserId(userId);
    }
}
