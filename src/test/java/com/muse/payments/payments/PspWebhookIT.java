package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PspWebhookIT extends TestcontainersBase {

    private static final String SECRET = "whsec_it_secret";
    private static final String PROVIDER = "stripe";
    private static final WireMockServer PSP = new WireMockServer(options().dynamicPort());

    static {
        PSP.start();
    }

    @DynamicPropertySource
    static void pspProperties(DynamicPropertyRegistry registry) {
        registry.add("app.psp.stripe.enabled", () -> "true");
        registry.add("app.psp.stripe.base-url", PSP::baseUrl);
        registry.add("app.psp.stripe.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stopPsp() {
        PSP.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    private String providerReference;

    @BeforeEach
    void resetPsp() {
        PSP.resetAll();
    }

    @Test
    @DisplayName("Create opens an intent and a signed success webhook completes the payment exactly once")
    void signedSuccessCompletesOnce() throws Exception {
        String paymentId = createPayment();
        assertThat(providerRow(paymentId))
                .containsEntry("provider", PROVIDER)
                .containsEntry("provider_reference", providerReference);

        String body = event("evt_success_1", "payment_intent.succeeded", providerReference, paymentId);
        MvcResult first = webhook(PROVIDER, body, now());
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(first).get("outcome").asText()).isEqualTo("COMPLETED");

        MvcResult duplicate = webhook(PROVIDER, body, now());
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(duplicate).get("outcome").asText()).isEqualTo("DUPLICATE");

        assertThat(paymentStatus(paymentId)).isEqualTo("COMPLETED");
        assertThat(completedOutboxRows(paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("A failed intent cancels the payment")
    void failedIntentCancels() throws Exception {
        String paymentId = createPayment();

        MvcResult result = webhook(PROVIDER,
                event("evt_failed_1", "payment_intent.payment_failed", providerReference, paymentId), now());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result).get("outcome").asText()).isEqualTo("CANCELLED");
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("A bad signature is rejected and leaves the payment pending")
    void badSignatureIsRejected() throws Exception {
        String paymentId = createPayment();
        String body = event("evt_bad_1", "payment_intent.succeeded", providerReference, paymentId);
        String signature = "t=" + now() + ",v1=" + hmac("whsec_wrong", now() + "." + body);

        mockMvc.perform(post("/api/v1/webhooks/payments/" + PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:provider-signature"));

        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("A stale timestamp is rejected as a replay")
    void staleTimestampIsRejected() throws Exception {
        String paymentId = createPayment();

        MvcResult result = webhook(PROVIDER,
                event("evt_stale_1", "payment_intent.succeeded", providerReference, paymentId),
                Instant.now().minusSeconds(3600).getEpochSecond());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("An unregistered provider is 404 and never falls back to the default")
    void unknownProviderIs404() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/payments/adyen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("An unknown event type is acknowledged and ignored")
    void unknownEventTypeIsIgnored() throws Exception {
        String paymentId = createPayment();

        MvcResult result = webhook(PROVIDER,
                event("evt_other_1", "payment_intent.created", providerReference, paymentId), now());

        assertThat(json(result).get("outcome").asText()).isEqualTo("IGNORED");
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("A signed event for another intent reference is ignored")
    void referenceMismatchIsIgnored() throws Exception {
        String paymentId = createPayment();

        MvcResult result = webhook(PROVIDER,
                event("evt_mismatch_1", "payment_intent.succeeded", "pi_other", paymentId), now());

        assertThat(json(result).get("outcome").asText()).isEqualTo("IGNORED");
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
    }

    private String createPayment() throws Exception {
        providerReference = "pi_" + UUID.randomUUID().toString().replace("-", "");
        PSP.stubFor(WireMock.post(urlEqualTo("/v1/payment_intents"))
                .willReturn(okJson("{\"id\":\"" + providerReference + "\",\"status\":\"requires_payment_method\"}")));
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        String request = """
                {"amount":42.00,"currency":"BRL","description":"PSP integration"}
                """;
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "psp-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("id").asText();
    }

    private MvcResult webhook(String provider, String body, long timestamp) throws Exception {
        String signature = "t=" + timestamp + ",v1=" + hmac(SECRET, timestamp + "." + body);
        return mockMvc.perform(post("/api/v1/webhooks/payments/" + provider)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature)
                        .content(body))
                .andReturn();
    }

    private String event(String eventId, String type, String reference, String paymentId) {
        return """
                {"id":"%s","object":"event","type":"%s","created":%d,
                 "data":{"object":{"id":"%s","object":"payment_intent","metadata":{"payment_id":"%s"}}}}
                """.formatted(eventId, type, Instant.now().getEpochSecond(), reference, paymentId);
    }

    private Map<String, Object> providerRow(String paymentId) {
        return jdbcTemplate.queryForMap(
                "select provider, provider_reference from payments where id = ?::uuid", paymentId);
    }

    private String paymentStatus(String paymentId) {
        return jdbcTemplate.queryForObject(
                "select status from payments where id = ?::uuid", String.class, paymentId);
    }

    private int completedOutboxRows(String paymentId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'PaymentCompleted'",
                Integer.class,
                paymentId);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
