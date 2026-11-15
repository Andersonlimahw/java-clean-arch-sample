package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.PaymentSort;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.shared.application.QueryHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ListHeldPaymentsQueryHandler implements QueryHandler<ListHeldPaymentsQuery, PageResponse<PaymentResponse>> {

    private final PaymentReadRepository paymentRepository;

    public ListHeldPaymentsQueryHandler(PaymentReadRepository paymentRepository) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> handle(ListHeldPaymentsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        int page = query.page();
        int size = query.size();
        PaymentSort sort = ListPaymentsQueryHandler.validated(page, size, query.sort());
        List<PaymentResponse> items = paymentRepository.findByStatus(PaymentStatus.UNDER_REVIEW, page, size, sort)
                .stream()
                .map(PaymentResponse::from)
                .toList();
        long total = paymentRepository.countByStatus(PaymentStatus.UNDER_REVIEW);
        return PageResponse.of(items, page, size, total, PaymentSorts.canonical(sort));
    }
}
