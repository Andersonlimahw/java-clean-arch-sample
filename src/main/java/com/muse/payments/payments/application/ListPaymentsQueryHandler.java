package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.QueryHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ListPaymentsQueryHandler implements QueryHandler<ListPaymentsQuery, PageResponse<PaymentResponse>> {

    private final PaymentRepository paymentRepository;

    public ListPaymentsQueryHandler(PaymentRepository paymentRepository) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> handle(ListPaymentsQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        int page = Math.max(query.page(), 0);
        int size = query.size() <= 0 ? 20 : Math.min(query.size(), 100);

        List<Payment> payments = paymentRepository.findByUserId(query.userId(), page, size);
        long total = paymentRepository.countByUserId(query.userId());

        List<PaymentResponse> dtos = payments.stream()
                .map(PaymentResponse::from)
                .toList();

        return PageResponse.of(dtos, page, size, total);
    }
}
