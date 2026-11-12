package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.QueryHandler;
import com.muse.payments.shared.domain.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class GetPaymentByIdQueryHandler implements QueryHandler<GetPaymentByIdQuery, PaymentResponse> {

    private final PaymentRepository paymentRepository;
    private final PaymentCachePort paymentCachePort;

    public GetPaymentByIdQueryHandler(PaymentRepository paymentRepository, PaymentCachePort paymentCachePort) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.paymentCachePort = Objects.requireNonNull(paymentCachePort);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse handle(GetPaymentByIdQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        PaymentResponse response = paymentCachePort.getOrLoad(query.paymentId(), () -> {
            Payment payment = paymentRepository.findById(query.paymentId())
                    .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + query.paymentId()));
            return PaymentResponse.from(payment);
        });

        if (!query.adminRead() && !response.userId().equals(query.userId())) {
            throw new EntityNotFoundException("Payment not found with id: " + query.paymentId());
        }

        return response;
    }
}
