package com.muse.payments.shared.application;

/** Trace and actor context stored with an internal domain event. */
public record EventMetadata(
        String correlationId,
        String causationId,
        String traceparent,
        String actorId
) {

    public static EventMetadata empty() {
        return new EventMetadata(null, null, null, null);
    }
}
