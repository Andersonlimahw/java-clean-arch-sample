package com.muse.payments.shared.application;

import com.muse.payments.shared.domain.DomainEvent;

import java.util.List;

public interface EventStore {

    /**
     * Appends all events atomically. The caller's transaction also owns any synchronous projection
     * and outbox writes. A stale expected version is reported as a domain conflict.
     */
    long append(StreamRef stream, long expectedVersion, List<? extends DomainEvent> events, EventMetadata metadata);

    EventStream load(StreamRef stream);

    List<StoredEvent> loadAfter(StreamRef stream, long streamVersion);
}
