package com.muse.payments.shared.application;

import java.util.Objects;
import java.util.UUID;

public record StreamRef(String type, UUID id) {

    public StreamRef {
        Objects.requireNonNull(type, "Stream type is required");
        Objects.requireNonNull(id, "Stream id is required");
        if (type.isBlank() || type.length() > 100) {
            throw new IllegalArgumentException("Stream type must contain 1 to 100 characters");
        }
    }
}
