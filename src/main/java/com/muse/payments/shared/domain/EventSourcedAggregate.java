package com.muse.payments.shared.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Base for aggregates whose state is reconstructed by applying an ordered event stream. */
public abstract class EventSourcedAggregate<ID> extends AggregateRoot<ID> {

    private long version;

    public final long version() {
        return version;
    }

    protected final void raise(DomainEvent event) {
        DomainEvent requiredEvent = Objects.requireNonNull(event, "Domain event cannot be null");
        apply(requiredEvent);
        registerEvent(requiredEvent);
        version = Math.incrementExact(version);
    }

    protected abstract void apply(DomainEvent event);

    public static <ID, A extends EventSourcedAggregate<ID>> A rehydrate(
            Supplier<A> factory,
            List<? extends DomainEvent> history
    ) {
        A aggregate = Objects.requireNonNull(factory, "Aggregate factory is required").get();
        Objects.requireNonNull(aggregate, "Aggregate factory returned null");
        List<? extends DomainEvent> events = List.copyOf(Objects.requireNonNull(history, "History is required"));
        EventSourcedAggregate<ID> target = aggregate;
        if (target.version != 0 || !target.peekDomainEvents().isEmpty()) {
            throw new IllegalStateException("Rehydration requires a new aggregate");
        }
        target.replay(events);
        return aggregate;
    }

    private void replay(List<? extends DomainEvent> events) {
        for (DomainEvent event : events) {
            apply(event);
            version = Math.incrementExact(version);
        }
    }
}
