package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentSort;
import com.muse.payments.shared.application.QueryHandler;
import com.muse.payments.shared.domain.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ListPaymentsQueryHandler implements QueryHandler<ListPaymentsQuery, PageResponse<PaymentResponse>> {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_OFFSET = 10_000;

    private final PaymentReadRepository paymentRepository;

    public ListPaymentsQueryHandler(PaymentReadRepository paymentRepository) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> handle(ListPaymentsQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        int page = query.page();
        int size = query.size();
        PaymentSort sort = validated(page, size, query.sort());

        List<Payment> payments = paymentRepository.findByUserId(query.userId(), page, size, sort);
        long total = paymentRepository.countByUserId(query.userId());

        List<PaymentResponse> dtos = payments.stream()
                .map(PaymentResponse::from)
                .toList();

        return PageResponse.of(dtos, page, size, total, PaymentSorts.canonical(sort));
    }

    static PaymentSort validated(int page, int size, String sort) {
        if (page < 0) {
            throw new ValidationException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and 100");
        }
        if ((long) page * (long) size > MAX_OFFSET) {
            throw new ValidationException("page is too large; page * size must be <= " + MAX_OFFSET);
        }
        return PaymentSorts.parse(sort);
    }
}
