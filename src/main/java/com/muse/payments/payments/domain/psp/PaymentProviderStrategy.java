package com.muse.payments.payments.domain.psp;

/**
 * The provider contract the payments core depends on. One implementation per PSP.
 * Implementations own transport, payload shape, and signature scheme; the core only
 * sees intents and verified, provider-neutral events.
 */
public interface PaymentProviderStrategy {

    /** Stable lowercase provider id used in the webhook path. */
    String providerId();

    /** Opens an intent for the payment. Throws {@link ProviderUnavailableException} when the PSP cannot be reached. */
    ProviderIntent createIntent(ProviderIntentRequest request);

    /**
     * Verifies the callback signature and translates the payload.
     * Throws {@link InvalidProviderSignatureException} when the signature, the timestamp,
     * or the header grammar is invalid.
     */
    ProviderWebhookEvent verifyAndParse(ProviderWebhookRequest request);
}
