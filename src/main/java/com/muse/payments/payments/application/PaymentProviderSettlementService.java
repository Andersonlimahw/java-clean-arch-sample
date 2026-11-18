package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.payments.domain.psp.ProviderEventOutcome;
import com.muse.payments.payments.domain.psp.ProviderWebhookEvent;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.ConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Claims the delivery and settles the payment in one transaction. A duplicate delivery
 * short-circuits. Anything the platform cannot map (unknown type, missing payment,
 * reference mismatch, held payment) is acknowledged as IGNORED with a counter, because a
 * provider retry cannot change it. A local write failure rolls the claim back and the
 * caller returns 500, so the provider retries and the event is processed once.
 */
@Service
public class PaymentProviderSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderSettlementService.class);
    static final String IGNORED_METRIC = "payments.psp.webhook.ignored";
    static final String DUPLICATE_METRIC = "payments.psp.webhook.duplicate";

    private final PaymentRepository paymentRepository;
    private final OutboxPort outboxPort;
    private final PaymentCachePort paymentCachePort;
    private final PaymentProviderEventStorePort eventStore;
    private final Counter ignored;
    private final Counter duplicates;

    public PaymentProviderSettlementService(
            PaymentRepository paymentRepository,
            OutboxPort outboxPort,
            PaymentCachePort paymentCachePort,
            PaymentProviderEventStorePort eventStore,
            MeterRegistry meterRegistry
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.outboxPort = Objects.requireNonNull(outboxPort);
        this.paymentCachePort = Objects.requireNonNull(paymentCachePort);
        this.eventStore = Objects.requireNonNull(eventStore);
        this.ignored = Counter.builder(IGNORED_METRIC)
                .description("Provider callbacks acknowledged without a state change")
                .register(meterRegistry);
        this.duplicates = Counter.builder(DUPLICATE_METRIC)
                .description("Provider callbacks rejected as duplicate deliveries")
                .register(meterRegistry);
    }

    @Transactional
    public PaymentWebhookResponse settle(ProviderWebhookEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!eventStore.claim(event.provider(), event.eventId(), event.eventType(), event.paymentId())) {
            duplicates.increment();
            return new PaymentWebhookResponse(event.provider(), event.eventId(), PaymentWebhookResponse.DUPLICATE);
        }
        String outcome;
        try {
            outcome = apply(event);
        } catch (RuntimeException ex) {
            eventStore.resolve(event.provider(), event.eventId(), "FAILED");
            throw ex;
        }
        eventStore.resolve(event.provider(), event.eventId(), outcome);
        return new PaymentWebhookResponse(event.provider(), event.eventId(), outcome);
    }

    private String apply(ProviderWebhookEvent event) {
        if (event.outcome() == ProviderEventOutcome.UNKNOWN || event.paymentId() == null) {
            ignored.increment();
            return PaymentWebhookResponse.IGNORED;
        }
        Optional<Payment> found = paymentRepository.findById(event.paymentId());
        if (found.isEmpty()) {
            log.warn("Provider event {} references unknown payment {}", event.eventId(), event.paymentId());
            ignored.increment();
            return PaymentWebhookResponse.IGNORED;
        }
        Payment payment = found.get();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            ignored.increment();
            return PaymentWebhookResponse.IGNORED;
        }
        boolean succeeded = event.outcome() == ProviderEventOutcome.SUCCEEDED;
        try {
            payment.settleFromProvider(
                    event.provider(),
                    event.providerReference(),
                    succeeded,
                    reason(event));
        } catch (ConflictException ex) {
            log.info("Provider event {} cannot settle payment {}: {}",
                    event.eventId(), event.paymentId(), ex.getMessage());
            ignored.increment();
            return PaymentWebhookResponse.IGNORED;
        }
        Payment saved = paymentRepository.save(payment);
        for (var domainEvent : payment.pullDomainEvents()) {
            IntegrationEvent integrationEvent = PaymentOutboxMapper.toIntegrationEvent(domainEvent);
            if (integrationEvent != null) {
                outboxPort.append(integrationEvent);
            }
        }
        paymentCachePort.evictAfterCommit(saved.getId());
        return saved.getStatus() == PaymentStatus.COMPLETED
                ? PaymentWebhookResponse.COMPLETED
                : PaymentWebhookResponse.CANCELLED;
    }

    private static String reason(ProviderWebhookEvent event) {
        String reason = event.reason();
        if (reason == null || reason.isBlank()) {
            return "Provider reported " + event.eventType();
        }
        return OutboundText.limit(reason);
    }
}
