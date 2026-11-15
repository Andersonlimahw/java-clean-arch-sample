package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.shared.application.QueryHandler;
import com.muse.payments.shared.domain.EntityNotFoundException;
import com.muse.payments.shared.domain.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class GetPaymentHistoryQueryHandler
        implements QueryHandler<GetPaymentHistoryQuery, PageResponse<PaymentHistoryItemResponse>> {

    private final PaymentReadRepository paymentReadRepository;
    private final PaymentHistoryReadRepository historyReadRepository;

    public GetPaymentHistoryQueryHandler(
            PaymentReadRepository paymentReadRepository,
            PaymentHistoryReadRepository historyReadRepository
    ) {
        this.paymentReadRepository = Objects.requireNonNull(paymentReadRepository);
        this.historyReadRepository = Objects.requireNonNull(historyReadRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentHistoryItemResponse> handle(GetPaymentHistoryQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.page() < 0) {
            throw new ValidationException("page must be greater than or equal to 0");
        }
        if (query.size() < 1 || query.size() > ListPaymentsQueryHandler.MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and 100");
        }
        if ((long) query.page() * (long) query.size() > ListPaymentsQueryHandler.MAX_OFFSET) {
            throw new ValidationException("page is too large; page * size must be <= " + ListPaymentsQueryHandler.MAX_OFFSET);
        }

        Payment payment = paymentReadRepository.findById(query.paymentId())
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + query.paymentId()));
        if (!query.readAny() && !payment.isOwnedBy(query.userId())) {
            throw new EntityNotFoundException("Payment not found with id: " + query.paymentId());
        }

        List<PaymentHistoryItemResponse> items = historyReadRepository.findByPaymentId(
                query.paymentId(), query.page(), query.size());
        long total = historyReadRepository.countByPaymentId(query.paymentId());
        return PageResponse.of(items, query.page(), query.size(), total, "streamVersion,asc");
    }
}
