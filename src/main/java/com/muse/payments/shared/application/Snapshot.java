package com.muse.payments.shared.application;

import java.time.Instant;
import java.util.Objects;

/** A disposable JSON state cache; the event stream remains the source of truth. */
public record Snapshot(
        StreamRef stream,
        long streamVersion,
        int schemaVersion,
        String stateJson,
        Instant takenAt
) {

    public Snapshot {
        Objects.requireNonNull(stream, "Stream is required");
        Objects.requireNonNull(stateJson, "Snapshot state is required");
        Objects.requireNonNull(takenAt, "Snapshot time is required");
        if (streamVersion < 1 || schemaVersion < 1 || stateJson.isBlank()) {
            throw new IllegalArgumentException("Snapshot versions and state must be valid");
        }
    }
}
