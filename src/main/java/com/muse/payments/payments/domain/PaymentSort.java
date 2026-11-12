package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.ValidationException;

public enum PaymentSort {
    CREATED_AT_DESC("createdAt,desc"),
    CREATED_AT_ASC("createdAt,asc");

    private final String queryValue;

    PaymentSort(String queryValue) {
        this.queryValue = queryValue;
    }

    public String queryValue() {
        return queryValue;
    }

    public static PaymentSort fromQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return CREATED_AT_DESC;
        }
        String normalized = raw.trim();
        for (PaymentSort candidate : values()) {
            if (candidate.queryValue.equals(normalized)) {
                return candidate;
            }
        }
        throw new ValidationException("sort must be one of: createdAt,desc, createdAt,asc");
    }
}
