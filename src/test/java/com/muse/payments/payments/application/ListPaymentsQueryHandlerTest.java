package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentSort;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.shared.domain.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListPaymentsQueryHandlerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Negative page is rejected")
    void rejectsNegativePage() {
        ListPaymentsQueryHandler handler = new ListPaymentsQueryHandler(new RecordingRepository());

        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, -1, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("page must be greater than or equal to 0");
    }

    @Test
    @DisplayName("Zero, negative, and over-max size are rejected")
    void rejectsInvalidSize() {
        ListPaymentsQueryHandler handler = new ListPaymentsQueryHandler(new RecordingRepository());

        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 0, 0)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("size must be between 1 and 100");
        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 0, -5)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("size must be between 1 and 100");
        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 0, 101)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("size must be between 1 and 100");
    }

    @Test
    @DisplayName("Size 1 and 100 are accepted and echoed in the page metadata")
    void acceptsBoundarySize() {
        RecordingRepository repository = new RecordingRepository();
        ListPaymentsQueryHandler handler = new ListPaymentsQueryHandler(repository);

        PageResponse<PaymentResponse> first = handler.handle(new ListPaymentsQuery(USER_ID, 0, 1));
        PageResponse<PaymentResponse> last = handler.handle(new ListPaymentsQuery(USER_ID, 0, 100));

        assertThat(first.size()).isEqualTo(1);
        assertThat(last.size()).isEqualTo(100);
        assertThat(first.page()).isZero();
        assertThat(repository.requestedSizes).containsExactly(1, 100);
    }

    @Test
    @DisplayName("Sort outside the allowlist is rejected; blank sort defaults to createdAt,desc")
    void validatesSortAllowlist() {
        RecordingRepository repository = new RecordingRepository();
        ListPaymentsQueryHandler handler = new ListPaymentsQueryHandler(repository);

        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 0, 20, "amount,desc")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("sort must be one of: createdAt,desc, createdAt,asc");
        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 0, 20, "createdAt,DESC")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("sort must be one of: createdAt,desc, createdAt,asc");

        PageResponse<PaymentResponse> blank = handler.handle(new ListPaymentsQuery(USER_ID, 0, 20, "  "));
        PageResponse<PaymentResponse> padded = handler.handle(new ListPaymentsQuery(USER_ID, 0, 20, " createdAt,asc "));

        assertThat(blank.sort()).isEqualTo("createdAt,desc");
        assertThat(padded.sort()).isEqualTo("createdAt,asc");
        assertThat(repository.requestedSorts).containsExactly(PaymentSort.CREATED_AT_DESC, PaymentSort.CREATED_AT_ASC);
    }

    @Test
    @DisplayName("Offset above 10000 is rejected, including a product past Integer.MAX_VALUE")
    void rejectsOffsetAboveCap() {
        RecordingRepository repository = new RecordingRepository();
        ListPaymentsQueryHandler handler = new ListPaymentsQueryHandler(repository);

        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 501, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("page is too large; page * size must be <= 10000");
        assertThatThrownBy(() -> handler.handle(new ListPaymentsQuery(USER_ID, 21474837, 100)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("page is too large; page * size must be <= 10000");

        PageResponse<PaymentResponse> edge = handler.handle(new ListPaymentsQuery(USER_ID, 500, 20));
        assertThat(edge.page()).isEqualTo(500);
        assertThat(edge.size()).isEqualTo(20);
        assertThat(repository.requestedSizes).containsExactly(20);
    }

    @Test
    @DisplayName("Page metadata uses the repository total and preserves repository order")
    void returnsStablePageMetadata() {
        RecordingRepository repository = new RecordingRepository();
        Instant shared = Instant.parse("2026-01-01T00:00:00Z");
        Payment higherId = payment(UUID.fromString("00000000-0000-0000-0000-000000000009"), shared);
        Payment lowerId = payment(UUID.fromString("00000000-0000-0000-0000-000000000002"), shared);
        repository.rows = List.of(higherId, lowerId);
        repository.total = 5;
        ListPaymentsQueryHandler handler = new ListPaymentsQueryHandler(repository);

        PageResponse<PaymentResponse> response = handler.handle(
                new ListPaymentsQuery(USER_ID, 1, 2, "createdAt,desc")
        );

        assertThat(response.items()).extracting(PaymentResponse::id)
                .containsExactly(higherId.getId(), lowerId.getId());
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.sort()).isEqualTo("createdAt,desc");
        assertThat(repository.requestedSorts).containsExactly(PaymentSort.CREATED_AT_DESC);
    }

    private static Payment payment(UUID id, Instant createdAt) {
        return Payment.rehydrate(
                id,
                USER_ID,
                Money.of(new BigDecimal("10.00"), "BRL"),
                "seed",
                PaymentStatus.PENDING,
                createdAt,
                createdAt,
                0L,
                null,
                null,
                null,
                null
        );
    }

    private static final class RecordingRepository implements PaymentReadRepository {

        private final List<Integer> requestedSizes = new ArrayList<>();
        private final List<PaymentSort> requestedSorts = new ArrayList<>();
        private List<Payment> rows = List.of();
        private long total;

        @Override
        public Optional<Payment> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<Payment> findByUserId(UUID userId, int page, int size, PaymentSort sort) {
            requestedSizes.add(size);
            requestedSorts.add(sort);
            return rows;
        }

        @Override
        public List<Payment> findByStatus(PaymentStatus status, int page, int size, PaymentSort sort) {
            return List.of();
        }

        public long countByStatus(PaymentStatus status) {
            return 0;
        }

        public long countByUserId(UUID userId) {
            return total;
        }
    }
}
