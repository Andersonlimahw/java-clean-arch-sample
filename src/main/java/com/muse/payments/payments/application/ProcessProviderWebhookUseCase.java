package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.psp.InvalidProviderPayloadException;
import com.muse.payments.payments.domain.psp.PaymentProviderStrategy;
import com.muse.payments.payments.domain.psp.ProviderWebhookEvent;
import com.muse.payments.payments.domain.psp.ProviderWebhookRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies a callback outside any transaction, then hands the verified event to the
 * settlement service. A provider retry after a bad local write is therefore verified
 * again and can still be processed. The body is bounded before verification so an
 * unauthenticated caller cannot make the platform buffer an arbitrary payload.
 */
@Service
public class ProcessProviderWebhookUseCase {

    static final int MAX_WEBHOOK_BYTES = 512 * 1024;

    private final PaymentProviderRegistry providerRegistry;
    private final PaymentProviderSettlementService settlementService;

    public ProcessProviderWebhookUseCase(
            PaymentProviderRegistry providerRegistry,
            PaymentProviderSettlementService settlementService
    ) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.settlementService = Objects.requireNonNull(settlementService);
    }

    public PaymentWebhookResponse process(String provider, byte[] rawBody, Map<String, String> headers) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (rawBody.length > MAX_WEBHOOK_BYTES) {
            throw new InvalidProviderPayloadException(
                    "Webhook payload exceeds " + MAX_WEBHOOK_BYTES + " bytes", null);
        }
        PaymentProviderStrategy strategy = providerRegistry.require(provider);
        ProviderWebhookEvent event = strategy.verifyAndParse(new ProviderWebhookRequest(
                strategy.providerId(), new String(rawBody, StandardCharsets.UTF_8), headers));
        return settlementService.settle(event);
    }
}
