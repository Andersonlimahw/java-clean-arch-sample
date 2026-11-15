package com.muse.payments.shared.application;

import java.util.List;
import java.util.Objects;

public record EventStream(StreamRef stream, long currentVersion, List<StoredEvent> events) {

    public EventStream {
        Objects.requireNonNull(stream, "Stream is required");
        if (currentVersion < 0) {
            throw new IllegalArgumentException("Current stream version cannot be negative");
        }
        events = List.copyOf(Objects.requireNonNull(events, "Events are required"));
        if (!events.isEmpty() && events.getLast().streamVersion() != currentVersion) {
            throw new IllegalArgumentException("Current version must match the last event");
        }
        if (events.isEmpty() && currentVersion != 0) {
            throw new IllegalArgumentException("An empty stream must have version zero");
        }
    }
}
