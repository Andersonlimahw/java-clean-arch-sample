package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentSort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    public List<Payment> findByUserId(UUID userId, int page, int size, PaymentSort sort) {
        Objects.requireNonNull(sort, "sort must not be null");
        Sort order = switch (sort) {
            case CREATED_AT_ASC -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
            case CREATED_AT_DESC -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
        return paymentJpaRepository.findByUserId(userId, PageRequest.of(page, size, order))
                .stream()
                .map(PaymentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(UUID userId) {
        return paymentJpaRepository.countByUserId(userId);
    }
}
