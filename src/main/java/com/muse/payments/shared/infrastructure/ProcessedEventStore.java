package com.muse.payments.shared.infrastructure;

import java.util.UUID;

public interface ProcessedEventStore {

    boolean tryMarkProcessed(String consumer, UUID eventId);

    boolean isProcessed(String consumer, UUID eventId);
}
