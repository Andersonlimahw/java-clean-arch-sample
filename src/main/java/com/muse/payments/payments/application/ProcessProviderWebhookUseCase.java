package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.psp.PaymentProviderStrategy;
import com.muse.payments.payments.domain.psp.ProviderWebhookEvent;
import com.muse.payments.payments.domain.psp.ProviderWebhookRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * Verifies a callback outside any transaction, then hands the verified event to the
 * settlement service. A provider retry after a bad local write is therefore verified
 * again and can still be processed.
 */
@Service
public class ProcessProviderWebhookUseCase {

    private final PaymentProviderRegistry providerRegistry;
    private final PaymentProviderSettlementService settlementService;

    public ProcessProviderWebhookUseCase(
            PaymentProviderRegistry providerRegistry,
            PaymentProviderSettlementService settlementService
    ) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.settlementService = Objects.requireNonNull(settlementService);
    }

    public PaymentWebhookResponse process(String provider, String rawBody, Map<String, String> headers) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        PaymentProviderStrategy strategy = providerRegistry.require(provider);
        ProviderWebhookEvent event = strategy.verifyAndParse(
                new ProviderWebhookRequest(strategy.providerId(), rawBody, headers));
        return settlementService.settle(event);
    }
}
