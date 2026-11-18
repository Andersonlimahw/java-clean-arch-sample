package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.payments.domain.psp.ProviderIntent;
import com.muse.payments.payments.domain.psp.ProviderIntentRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Opens the provider intent after the create transaction has committed, so the outbound
 * HTTP call never holds a database connection. A provider failure leaves the payment
 * PENDING without a reference and is counted, never silent; reconciliation is out of
 * scope for this sample.
 */
@Service
public class InitiateProviderPaymentUseCase implements ProviderPaymentInitiator {

    private static final Logger log = LoggerFactory.getLogger(InitiateProviderPaymentUseCase.class);
    static final String FAILURE_METRIC = "payments.psp.intent.failed";

    private final PaymentRepository paymentRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderIntentRecorder recorder;
    private final Counter failures;
    private final String defaultProvider;

    public InitiateProviderPaymentUseCase(
            PaymentRepository paymentRepository,
            PaymentProviderRegistry providerRegistry,
            ProviderIntentRecorder recorder,
            MeterRegistry meterRegistry,
            @Value("${app.psp.default-provider:stripe}") String defaultProvider
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.recorder = Objects.requireNonNull(recorder);
        this.failures = Counter.builder(FAILURE_METRIC)
                .description("Provider intents that could not be opened after the payment was committed")
                .register(meterRegistry);
        this.defaultProvider = Objects.requireNonNull(defaultProvider);
    }

    public void initiate(UUID paymentId) {
        if (!providerRegistry.isRegistered(defaultProvider)) {
            return;
        }
        try {
            Payment payment = paymentRepository.findById(paymentId).orElse(null);
            if (payment == null
                    || payment.getStatus() != PaymentStatus.PENDING
                    || payment.getProviderReference() != null) {
                return;
            }
            ProviderIntent intent = providerRegistry.require(defaultProvider).createIntent(
                    new ProviderIntentRequest(
                            payment.getId(),
                            payment.getUserId(),
                            payment.getMoney(),
                            payment.getDescription()));
            recorder.record(paymentId, intent);
        } catch (RuntimeException ex) {
            failures.increment();
            log.warn("Could not open a provider intent for payment {}: {}", paymentId, ex.toString());
        }
    }
}
