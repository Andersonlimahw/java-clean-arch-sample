package com.muse.payments.shared.infrastructure.eventstore;

import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.application.EventTypeRegistration;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class EventTypeRegistry {

    private final Map<String, EventTypeRegistration> byName;
    private final Map<Class<? extends DomainEvent>, EventTypeRegistration> byClass;

    public EventTypeRegistry(List<EventTypeRegistration> registrations) {
        Objects.requireNonNull(registrations, "Event type registrations are required");
        Map<String, EventTypeRegistration> names = new HashMap<>();
        Map<Class<? extends DomainEvent>, EventTypeRegistration> classes = new HashMap<>();
        for (EventTypeRegistration registration : registrations) {
            Objects.requireNonNull(registration, "Event type registration cannot be null");
            if (names.putIfAbsent(registration.name(), registration) != null) {
                throw new IllegalArgumentException("Duplicate event type name: " + registration.name());
            }
            if (classes.putIfAbsent(registration.eventClass(), registration) != null) {
                throw new IllegalArgumentException("Event class is registered more than once: "
                        + registration.eventClass().getName());
            }
        }
        byName = Map.copyOf(names);
        byClass = Map.copyOf(classes);
    }

    public EventTypeRegistration forEvent(DomainEvent event) {
        Objects.requireNonNull(event, "Event is required");
        EventTypeRegistration registration = byClass.get(event.getClass());
        if (registration == null) {
            throw new IllegalArgumentException("No stable event type is registered for "
                    + event.getClass().getName());
        }
        return registration;
    }

    public EventTypeRegistration forName(String eventType) {
        EventTypeRegistration registration = byName.get(Objects.requireNonNull(eventType));
        if (registration == null) {
            throw new IllegalStateException("Unknown event type: " + eventType);
        }
        return registration;
    }
}
