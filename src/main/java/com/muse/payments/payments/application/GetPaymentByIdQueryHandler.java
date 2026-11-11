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

    public GetPaymentByIdQueryHandler(PaymentRepository paymentRepository) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse handle(GetPaymentByIdQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        Payment payment = paymentRepository.findById(query.paymentId())
                .filter(p -> p.isOwnedBy(query.userId()))
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + query.paymentId()));

        return PaymentResponse.from(payment);
    }
}
