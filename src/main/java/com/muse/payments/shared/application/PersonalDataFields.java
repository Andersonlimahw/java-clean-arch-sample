package com.muse.payments.shared.application;

import java.util.Objects;
import java.util.Set;

/**
 * Top-level payload fields of one stable event type that hold personal data of the subject
 * named by {@code subjectField}. The event store encrypts them with that subject's data key.
 */
public record PersonalDataFields(
        String eventType,
        String subjectField,
        Set<String> fields
) {

    public PersonalDataFields {
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(subjectField, "subjectField is required");
        Objects.requireNonNull(fields, "fields are required");
        if (eventType.isBlank() || subjectField.isBlank()) {
            throw new IllegalArgumentException("eventType and subjectField must not be blank");
        }
        fields = Set.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("At least one personal data field is required");
        }
        if (fields.contains(subjectField)) {
            throw new IllegalArgumentException("The subject field cannot itself be encrypted");
        }
    }
}
