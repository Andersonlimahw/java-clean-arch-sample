package com.muse.payments.payments.infrastructure.psp.stripe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.domain.psp.InvalidProviderPayloadException;
import com.muse.payments.payments.domain.psp.InvalidProviderSignatureException;
import com.muse.payments.payments.domain.psp.PaymentProviderStrategy;
import com.muse.payments.payments.domain.psp.ProviderEventOutcome;
import com.muse.payments.payments.domain.psp.ProviderIntent;
import com.muse.payments.payments.domain.psp.ProviderIntentRequest;
import com.muse.payments.payments.domain.psp.ProviderUnavailableException;
import com.muse.payments.payments.domain.psp.ProviderWebhookEvent;
import com.muse.payments.payments.domain.psp.ProviderWebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stripe adapter. Creates PaymentIntents and verifies snapshot webhooks exactly as
 * documented: {@code Stripe-Signature} carries {@code t} and one or more {@code v1}
 * values, the signed payload is {@code "{t}.{rawBody}"}, and the signature is an
 * HMAC-SHA256 with the endpoint secret compared in constant time. {@code v0} and the
 * debug entries are ignored.
 */
public class StripePaymentProviderAdapter implements PaymentProviderStrategy {

    static final String PROVIDER_ID = "stripe";
    static final String SIGNATURE_HEADER = "Stripe-Signature";
    static final String INTENTS_PATH = "/v1/payment_intents";
    static final String AUTHORIZATION_HEADER = "Authorization";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProviderAdapter.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "bif", "clp", "djf", "gnf", "jpy", "kmf", "krw", "mga", "pyg",
            "rwf", "ugx", "vnd", "vuv", "xaf", "xof", "xpf");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final byte[] webhookSecret;
    private final Duration tolerance;
    private final Clock clock;

    public StripePaymentProviderAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            String apiKey,
            String webhookSecret,
            Duration tolerance,
            Clock clock
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.apiKey = Objects.requireNonNull(apiKey);
        this.webhookSecret = Objects.requireNonNull(webhookSecret).getBytes(StandardCharsets.UTF_8);
        this.tolerance = Objects.requireNonNull(tolerance);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderIntent createIntent(ProviderIntentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Long.toString(minorUnits(request)));
        form.add("currency", request.money().currency().toLowerCase(Locale.ROOT));
        if (request.description() != null && !request.description().isBlank()) {
            form.add("description", request.description());
        }
        form.add("metadata[payment_id]", request.paymentId().toString());
        form.add("automatic_payment_methods[enabled]", "true");
        try {
            PaymentIntentResponse response = restClient.post()
                    .uri(INTENTS_PATH)
                    .header(AUTHORIZATION_HEADER, "Bearer " + apiKey)
                    .header(IDEMPOTENCY_HEADER, request.paymentId().toString())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(PaymentIntentResponse.class);
            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new ProviderUnavailableException("Stripe returned no payment intent id");
            }
            return new ProviderIntent(PROVIDER_ID, response.id(), response.status());
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("Stripe payment intent creation failed", ex);
        }
    }

    @Override
    public ProviderWebhookEvent verifyAndParse(ProviderWebhookRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String header = header(request.headers(), SIGNATURE_HEADER);
        if (header == null || header.isBlank()) {
            throw new InvalidProviderSignatureException("Missing " + SIGNATURE_HEADER + " header");
        }
        SignatureParts parts = parseHeader(header);
        verifyTolerance(parts.timestamp());
        String signedPayload = parts.timestamp() + "." + request.rawBody();
        byte[] expected = hmac(signedPayload);
        boolean matched = parts.signatures().stream()
                .anyMatch(candidate -> matches(expected, candidate));
        if (!matched) {
            throw new InvalidProviderSignatureException("Signature verification failed");
        }
        return toEvent(request.rawBody());
    }

    private ProviderWebhookEvent toEvent(String rawBody) {
        StripeEvent event;
        try {
            event = objectMapper.readValue(rawBody, StripeEvent.class);
        } catch (JsonProcessingException ex) {
            throw new InvalidProviderPayloadException("Webhook body is not a Stripe event", ex);
        }
        if (event.id() == null || event.id().isBlank() || event.type() == null) {
            throw new InvalidProviderPayloadException("Webhook body has no event id or type", null);
        }
        PaymentIntent intent = event.data() == null ? null : event.data().object();
        UUID paymentId = paymentId(intent);
        String reference = intent == null ? null : intent.id();
        ProviderEventOutcome outcome = switch (event.type()) {
            case "payment_intent.succeeded" -> ProviderEventOutcome.SUCCEEDED;
            case "payment_intent.payment_failed", "payment_intent.canceled" -> ProviderEventOutcome.FAILED;
            default -> ProviderEventOutcome.UNKNOWN;
        };
        Instant occurredAt = event.created() == null
                ? clock.instant()
                : Instant.ofEpochSecond(event.created());
        return new ProviderWebhookEvent(
                PROVIDER_ID,
                event.id(),
                event.type(),
                paymentId,
                reference,
                outcome,
                reason(event.type()),
                occurredAt
        );
    }

    private static String reason(String type) {
        return switch (type) {
            case "payment_intent.succeeded" -> "Stripe reported payment success";
            case "payment_intent.payment_failed" -> "Stripe reported payment failure";
            case "payment_intent.canceled" -> "Stripe reported payment cancellation";
            default -> "Stripe reported " + type;
        };
    }

    private static UUID paymentId(PaymentIntent intent) {
        if (intent == null || intent.metadata() == null || intent.metadata().paymentId() == null) {
            return null;
        }
        try {
            return UUID.fromString(intent.metadata().paymentId());
        } catch (IllegalArgumentException ex) {
            log.warn("Stripe event metadata payment_id is not a UUID");
            return null;
        }
    }

    private void verifyTolerance(long timestamp) {
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > tolerance.toSeconds()) {
            throw new InvalidProviderSignatureException("Signature timestamp is outside the tolerance window");
        }
    }

    private byte[] hmac(String signedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret, HMAC_ALGORITHM));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }

    private static boolean matches(byte[] expected, String candidate) {
        byte[] candidateBytes = candidate.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedHex = HexFormat.of().formatHex(expected).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedHex, candidateBytes);
    }

    private static SignatureParts parseHeader(String header) {
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String element : header.split(",")) {
            int separator = element.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = element.substring(0, separator).trim();
            String value = element.substring(separator + 1).trim();
            if (key.equals("t")) {
                if (timestamp != null) {
                    throw new InvalidProviderSignatureException("Signature header has more than one timestamp");
                }
                timestamp = parseTimestamp(value);
            } else if (key.equals("v1") && !value.isEmpty()) {
                signatures.add(value);
            }
        }
        if (timestamp == null) {
            throw new InvalidProviderSignatureException("Signature header has no timestamp");
        }
        if (signatures.isEmpty()) {
            throw new InvalidProviderSignatureException("Signature header has no v1 signature");
        }
        return new SignatureParts(timestamp, signatures);
    }

    private static long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new InvalidProviderSignatureException("Signature timestamp is not a number");
        }
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static long minorUnits(ProviderIntentRequest request) {
        String currency = request.money().currency().toLowerCase(Locale.ROOT);
        int exponent = ZERO_DECIMAL_CURRENCIES.contains(currency) ? 0 : 2;
        try {
            return request.money().amount().movePointRight(exponent).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Amount cannot be represented in " + currency + " minor units", ex);
        }
    }

    private record SignatureParts(long timestamp, List<String> signatures) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StripeEvent(String id, String type, Long created, Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(PaymentIntent object) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentIntent(String id, String status, Metadata metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(@JsonProperty("payment_id") String paymentId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentIntentResponse(String id, String status) {
    }
}
