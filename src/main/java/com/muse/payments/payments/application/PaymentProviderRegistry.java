package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.psp.PaymentProviderStrategy;
import com.muse.payments.shared.domain.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a provider id from the webhook path to its strategy bean. An unknown id is a
 * configuration error (404); there is deliberately no default fallback, so a typo in the
 * path never settles a payment at the wrong provider.
 */
@Component
public class PaymentProviderRegistry {

    private final Map<String, PaymentProviderStrategy> strategies;

    public PaymentProviderRegistry(List<PaymentProviderStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies must not be null");
        Map<String, PaymentProviderStrategy> byId = new LinkedHashMap<>();
        for (PaymentProviderStrategy strategy : strategies) {
            String id = strategy.providerId().trim().toLowerCase(Locale.ROOT);
            PaymentProviderStrategy previous = byId.putIfAbsent(id, strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate payment provider strategy for id: " + id);
            }
        }
        this.strategies = Map.copyOf(byId);
    }

    public PaymentProviderStrategy require(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new EntityNotFoundException("Payment provider is required");
        }
        PaymentProviderStrategy strategy = strategies.get(provider.trim().toLowerCase(Locale.ROOT));
        if (strategy == null) {
            throw new EntityNotFoundException("Payment provider is not registered: " + provider);
        }
        return strategy;
    }

    public boolean isRegistered(String provider) {
        return provider != null && strategies.containsKey(provider.trim().toLowerCase(Locale.ROOT));
    }
}
