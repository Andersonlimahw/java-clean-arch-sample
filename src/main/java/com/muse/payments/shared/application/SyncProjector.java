package com.muse.payments.shared.application;

import com.muse.payments.shared.domain.DomainEvent;

/**
 * A synchronous projector must join the event append transaction (MANDATORY propagation).
 * Implementations belong in infrastructure and must not commit independently.
 */
@FunctionalInterface
public interface SyncProjector<E extends DomainEvent> {

    void project(StreamRef stream, E event, long streamVersion, EventMetadata metadata);
}
