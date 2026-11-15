package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.EventStream;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.infrastructure.EventMetadataProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventSourcedPaymentRepository implements PaymentRepository {

    private final EventStore eventStore;
    private final PaymentProjector paymentProjector;
    private final EventMetadataProvider metadataProvider;

    public EventSourcedPaymentRepository(
            EventStore eventStore,
            PaymentProjector paymentProjector,
            EventMetadataProvider metadataProvider
    ) {
        this.eventStore = Objects.requireNonNull(eventStore);
        this.paymentProjector = Objects.requireNonNull(paymentProjector);
        this.metadataProvider = Objects.requireNonNull(metadataProvider);
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        Objects.requireNonNull(payment, "payment must not be null");
        List<DomainEvent> uncommitted = payment.peekDomainEvents();
        if (uncommitted.isEmpty()) {
            return payment;
        }
        StreamRef stream = new StreamRef(PaymentEventRegistryConfiguration.STREAM_TYPE, payment.getId());
        long expectedVersion = payment.version() - uncommitted.size();
        EventMetadata metadata = metadataProvider.current();
        eventStore.append(stream, expectedVersion, uncommitted, metadata);
        long streamVersion = expectedVersion;
        for (DomainEvent event : uncommitted) {
            streamVersion = Math.incrementExact(streamVersion);
            paymentProjector.project(stream, event, streamVersion, metadata);
        }
        return payment;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        EventStream stream = eventStore.load(new StreamRef(PaymentEventRegistryConfiguration.STREAM_TYPE, id));
        if (stream.events().isEmpty()) {
            return Optional.empty();
        }
        List<DomainEvent> history = stream.events().stream()
                .map(StoredEvent::event)
                .toList();
        return Optional.of(Payment.rehydrate(history));
    }
}
