package com.muse.payments.payments.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muse.payments.payments.domain.PaymentStatus;

import java.time.Instant;

public record PaymentHistoryItemResponse(
        String eventType,
        Instant occurredAt,
        PaymentStatus status,
        String actor,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reason
) {
}
