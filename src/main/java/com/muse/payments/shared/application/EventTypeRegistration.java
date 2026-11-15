package com.muse.payments.shared.application;

import com.muse.payments.shared.domain.DomainEvent;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable event name, schema version, and event-record mapping supplied by a module. */
public record EventTypeRegistration(
        String name,
        int schemaVersion,
        Class<? extends DomainEvent> eventClass
) {

    private static final Pattern STABLE_NAME = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");

    public EventTypeRegistration {
        Objects.requireNonNull(name, "Event type name is required");
        Objects.requireNonNull(eventClass, "Event class is required");
        if (!STABLE_NAME.matcher(name).matches() || name.length() > 150) {
            throw new IllegalArgumentException("Event type must be a stable, namespaced name");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Event schema version must be positive");
        }
        if (name.equals(eventClass.getSimpleName()) || name.equals(eventClass.getName())) {
            throw new IllegalArgumentException("Event type must not use a Java class name");
        }
    }
}
