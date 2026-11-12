package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.PaymentSort;
import com.muse.payments.shared.domain.ValidationException;

public final class PaymentSorts {

    public static final String CREATED_AT_DESC = "createdAt,desc";
    public static final String CREATED_AT_ASC = "createdAt,asc";

    private PaymentSorts() {
    }

    public static PaymentSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentSort.CREATED_AT_DESC;
        }
        String normalized = raw.trim();
        if (CREATED_AT_DESC.equals(normalized)) {
            return PaymentSort.CREATED_AT_DESC;
        }
        if (CREATED_AT_ASC.equals(normalized)) {
            return PaymentSort.CREATED_AT_ASC;
        }
        throw new ValidationException("sort must be one of: createdAt,desc, createdAt,asc");
    }

    public static String canonical(PaymentSort sort) {
        return switch (sort) {
            case CREATED_AT_ASC -> CREATED_AT_ASC;
            case CREATED_AT_DESC -> CREATED_AT_DESC;
        };
    }
}
