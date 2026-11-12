package com.muse.payments.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import com.muse.payments.shared.security.JwtTokenIssuer;
import com.muse.payments.support.ControllableFraudScreening;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FraudScreeningHttpIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private ControllableFraudScreening fraudScreening;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("The idempotency lease is committed before screening, so a slow provider holds no database connection")
    void slowScreeningDoesNotHoldAConnection() throws Exception {
        fraudScreening.setResult(FraudScreeningResult.clear());
        fraudScreening.blockNextCall();
        double before = activeConnections();
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("12.00"), "BRL", "Held connection");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<MvcResult> response = executor.submit(() -> mockMvc.perform(post("/api/v1/payments")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "conn-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn());
            assertThat(fraudScreening.awaitEntered(10, TimeUnit.SECONDS)).isTrue();
            assertThat(activeConnections()).isLessThanOrEqualTo(before);
            fraudScreening.release();
            response.get(10, TimeUnit.SECONDS);
            assertThat(response.get().getResponse().getStatus()).isEqualTo(201);
        } finally {
            fraudScreening.release();
            executor.close();
        }
    }

    @Test
    @DisplayName("An unavailable provider stores UNDER_REVIEW, replays it, and confirm is 409 until review approves")
    void underReviewIsNotConfirmableUntilReleased() throws Exception {
        fraudScreening.setResult(FraudScreeningResult.underReview("provider down"));
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        String key = "held-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("18.00"), "BRL", "Review me");

        MvcResult created = create(token, key, request);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        assertThat(body).contains("UNDER_REVIEW");
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        mockMvc.perform(post("/api/v1/payments/" + id + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/payments/" + id + "/review")
                        .header("Authorization", "Bearer " + token)
                        .param("decision", "APPROVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/payments/" + id + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("An explicit decline is 422 without the provider reason and the same key replays that response")
    void declineIsGenericAndIdempotent() throws Exception {
        fraudScreening.setResult(FraudScreeningResult.declined("stolen card"));
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));
        String key = "declined-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("21.00"), "BRL", "No");

        MvcResult declined = create(token, key, request);
        assertThat(declined.getResponse().getStatus()).isEqualTo(422);
        assertThat(declined.getResponse().getContentAsString()).doesNotContain("stolen card");
        assertThat(declined.getResponse().getContentAsString()).contains("declined by fraud screening");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Payment was declined by fraud screening."));
    }

    private MvcResult create(String token, String key, CreatePaymentRequest request) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    private double activeConnections() {
        return meterRegistry.find("hikaricp.connections.active").gauges().stream()
                .mapToDouble(gauge -> gauge.value())
                .sum();
    }
}
