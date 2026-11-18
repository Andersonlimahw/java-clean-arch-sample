package com.muse.payments.payments.infrastructure.psp.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.domain.psp.InvalidProviderPayloadException;
import com.muse.payments.payments.domain.psp.InvalidProviderSignatureException;
import com.muse.payments.payments.domain.psp.ProviderEventOutcome;
import com.muse.payments.payments.domain.psp.ProviderWebhookEvent;
import com.muse.payments.payments.domain.psp.ProviderWebhookRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret";
    private static final long NOW = 1_700_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final UUID PAYMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final StripePaymentProviderAdapter adapter = new StripePaymentProviderAdapter(
            RestClient.builder().baseUrl("http://localhost:1").build(),
            new ObjectMapper(),
            "sk_test",
            SECRET,
            Duration.ofMinutes(5),
            CLOCK);

    @Test
    @DisplayName("A correctly signed payment_intent.succeeded is accepted and translated")
    void validSignatureIsAccepted() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);

        ProviderWebhookEvent event = adapter.verifyAndParse(request(body, sign(SECRET, NOW, body)));

        assertThat(event.provider()).isEqualTo("stripe");
        assertThat(event.eventId()).isEqualTo("evt_test_1");
        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(event.providerReference()).isEqualTo("pi_test_1");
        assertThat(event.outcome()).isEqualTo(ProviderEventOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("A tampered body invalidates the signature")
    void tamperedBodyIsRejected() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);
        String signature = sign(SECRET, NOW, body + " ");

        assertThatThrownBy(() -> adapter.verifyAndParse(request(body, signature)))
                .isInstanceOf(InvalidProviderSignatureException.class);
    }

    @Test
    @DisplayName("A signature from a different secret is rejected")
    void wrongSecretIsRejected() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);

        assertThatThrownBy(() -> adapter.verifyAndParse(request(body, sign("whsec_other", NOW, body))))
                .isInstanceOf(InvalidProviderSignatureException.class);
    }

    @Test
    @DisplayName("A signature older than the tolerance window is rejected as a replay")
    void staleTimestampIsRejected() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);
        long stale = NOW - Duration.ofMinutes(6).toSeconds();

        assertThatThrownBy(() -> adapter.verifyAndParse(request(body, sign(SECRET, stale, body))))
                .isInstanceOf(InvalidProviderSignatureException.class);
    }

    @Test
    @DisplayName("A header without a v1 entry is rejected even when v0 is present")
    void v0OnlyIsRejected() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);
        String header = "t=" + NOW + ",v0=" + hexHmac(SECRET, NOW + "." + body);

        assertThatThrownBy(() -> adapter.verifyAndParse(request(body, header)))
                .isInstanceOf(InvalidProviderSignatureException.class);
    }

    @Test
    @DisplayName("During a secret roll one valid v1 is enough")
    void anyValidV1Wins() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);
        String header = "t=" + NOW
                + ",v1=" + hexHmac("whsec_old", NOW + "." + body)
                + ",v1=" + hexHmac(SECRET, NOW + "." + body);

        assertThat(adapter.verifyAndParse(request(body, header)).eventId()).isEqualTo("evt_test_1");
    }

    @Test
    @DisplayName("An unknown event type is verified and reported as UNKNOWN")
    void unknownTypeIsUnknown() throws Exception {
        String body = body("charge.refunded", PAYMENT_ID);

        assertThat(adapter.verifyAndParse(request(body, sign(SECRET, NOW, body))).outcome())
                .isEqualTo(ProviderEventOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("A signed body that is not a Stripe event is a payload error, not a signature error")
    void signedButMalformedPayloadIsRejected() throws Exception {
        String body = "{not json";

        assertThatThrownBy(() -> adapter.verifyAndParse(request(body, sign(SECRET, NOW, body))))
                .isInstanceOf(InvalidProviderPayloadException.class);
    }

    @Test
    @DisplayName("A header without a timestamp is rejected")
    void missingTimestampIsRejected() throws Exception {
        String body = body("payment_intent.succeeded", PAYMENT_ID);
        String header = "v1=" + hexHmac(SECRET, NOW + "." + body);

        assertThatThrownBy(() -> adapter.verifyAndParse(request(body, header)))
                .isInstanceOf(InvalidProviderSignatureException.class);
    }

    private static ProviderWebhookRequest request(String body, String signature) {
        Map<String, String> headers = new HashMap<>();
        headers.put("stripe-signature", signature);
        return new ProviderWebhookRequest("stripe", body, headers);
    }

    private static String sign(String secret, long timestamp, String body) throws Exception {
        return "t=" + timestamp + ",v1=" + hexHmac(secret, timestamp + "." + body);
    }

    private static String hexHmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String body(String type, UUID paymentId) {
        return """
                {"id":"evt_test_1","object":"event","type":"%s","created":%d,
                 "data":{"object":{"id":"pi_test_1","object":"payment_intent","status":"succeeded",
                 "metadata":{"payment_id":"%s"}}}}
                """.formatted(type, NOW, paymentId);
    }
}
