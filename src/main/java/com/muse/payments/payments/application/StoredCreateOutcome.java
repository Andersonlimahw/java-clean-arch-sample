package com.muse.payments.payments.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain result stored on the idempotency row. The web layer turns it into HTTP.
 */
public final class StoredCreateOutcome {

    static final String DECLINED = "DECLINED";
    static final String CREATED = "CREATED";

    private StoredCreateOutcome() {
    }

    public static String declined(ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of("outcome", DECLINED));
    }

    public static String created(ObjectMapper objectMapper, PaymentResponse payment) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", CREATED);
        body.put("payment", payment);
        return objectMapper.writeValueAsString(body);
    }

    public static CreatePaymentResult read(ObjectMapper objectMapper, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String outcome = node.path("outcome").asText();
            return switch (outcome) {
                case DECLINED -> new CreatePaymentResult.Declined();
                case CREATED -> new CreatePaymentResult.Created(
                        objectMapper.treeToValue(node.get("payment"), PaymentResponse.class));
                default -> throw new IllegalStateException("Unknown stored create outcome: " + outcome);
            };
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read the stored create outcome", ex);
        }
    }
}
