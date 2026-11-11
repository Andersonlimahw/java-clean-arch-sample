package com.muse.payments.shared.application;

import java.util.List;

public interface OutboxPort {

    void append(IntegrationEvent event);

    default void appendAll(List<IntegrationEvent> events) {
        if (events != null) {
            events.forEach(this::append);
        }
    }
}
