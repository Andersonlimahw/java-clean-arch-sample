package com.muse.payments.payments.infrastructure.psp.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.psp.ProviderEventOutcome;
import com.muse.payments.payments.domain.psp.ProviderIntent;
import com.muse.payments.payments.domain.psp.ProviderIntentRequest;
import com.muse.payments.payments.domain.psp.ProviderUnavailableException;
import com.muse.payments.payments.domain.psp.ProviderWebhookEvent;
import com.muse.payments.payments.domain.psp.ProviderWebhookRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripePaymentProviderAdapterTest {

    private static final String SECRET = "whsec_test_secret";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension SERVER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private StripePaymentProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StripePaymentProviderAdapter(
                RestClient.builder().baseUrl(SERVER.baseUrl()).build(),
                new ObjectMapper(),
                "sk_test",
                SECRET,
                Duration.ofMinutes(5),
                CLOCK);
    }

    @Test
    @DisplayName("Intent creation sends form fields, the bearer key, and the payment id as idempotency key")
    void createsIntent() {
        SERVER.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_123\",\"status\":\"requires_payment_method\"}")));

        ProviderIntent intent = adapter.createIntent(new ProviderIntentRequest(
                PAYMENT_ID, UUID.randomUUID(), Money.of(new BigDecimal("10.00"), "BRL"), "Order 42"));

        assertThat(intent.provider()).isEqualTo("stripe");
        assertThat(intent.reference()).isEqualTo("pi_123");
        assertThat(intent.providerStatus()).isEqualTo("requires_payment_method");
        SERVER.verify(postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Authorization", equalTo("Bearer sk_test"))
                .withHeader("Idempotency-Key", equalTo(PAYMENT_ID.toString()))
                .withRequestBody(containing("amount=1000"))
                .withRequestBody(containing("currency=brl"))
                .withRequestBody(containing(PAYMENT_ID.toString())));
    }

    @Test
    @DisplayName("A zero-decimal currency is not multiplied by 100")
    void zeroDecimalCurrency() {
        SERVER.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_jpy\",\"status\":\"requires_payment_method\"}")));

        adapter.createIntent(new ProviderIntentRequest(
                PAYMENT_ID, UUID.randomUUID(), Money.of(new BigDecimal("100.00"), "JPY"), null));

        SERVER.verify(postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withRequestBody(containing("amount=100&")));
    }

    @Test
    @DisplayName("A provider error becomes ProviderUnavailableException")
    void providerError() {
        SERVER.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> adapter.createIntent(new ProviderIntentRequest(
                PAYMENT_ID, UUID.randomUUID(), Money.of(new BigDecimal("10.00"), "BRL"), null)))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("payment_intent.payment_failed is a FAILED event")
    void failedEventIsFailed() throws Exception {
        String body = event("payment_intent.payment_failed");
        String header = "t=1700000000,v1=" + hmac(headerPayload(body));

        ProviderWebhookEvent event = adapter.verifyAndParse(
                new ProviderWebhookRequest("stripe", body, Map.of("Stripe-Signature", header)));

        assertThat(event.outcome()).isEqualTo(ProviderEventOutcome.FAILED);
        assertThat(event.reason()).contains("failure");
    }

    private static String event(String type) {
        return """
                {"id":"evt_1","type":"%s","created":1700000000,
                 "data":{"object":{"id":"pi_1","metadata":{"payment_id":"%s"}}}}
                """.formatted(type, PAYMENT_ID);
    }

    private static String hmac(String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(
                mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String headerPayload(String body) {
        return "1700000000." + body;
    }
}
