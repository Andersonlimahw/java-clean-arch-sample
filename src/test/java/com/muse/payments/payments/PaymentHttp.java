package com.muse.payments.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.payments.application.CreatePaymentRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class PaymentHttp {

    private PaymentHttp() {
    }

    static String registerAndLogin(MockMvc mockMvc, ObjectMapper objectMapper, String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, "Test User"))))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(login.getResponse().getContentAsString(), AuthTokensResponse.class).accessToken();
    }

    static ResultActions postPayment(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String token,
            String idempotencyKey,
            CreatePaymentRequest request
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    static String createdPaymentId(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String token,
            String idempotencyKey,
            CreatePaymentRequest request
    ) throws Exception {
        MvcResult result = postPayment(mockMvc, objectMapper, token, idempotencyKey, request)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    static UUID createdPaymentUuid(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String token,
            CreatePaymentRequest request
    ) throws Exception {
        return UUID.fromString(createdPaymentId(mockMvc, objectMapper, token, UUID.randomUUID().toString(), request));
    }
}
