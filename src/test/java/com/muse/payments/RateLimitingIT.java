package com.muse.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.auth-account-max-requests-per-minute=3",
        "app.payments.write-max-requests-per-minute=2"
})
class RateLimitingIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Distributed IPs attacking one account trigger 429 without storing raw email PII in Redis")
    void perAccountLoginRateLimitBlocksDistributedBruteForceWithoutRawPii() throws Exception {
        String email = "Target." + UUID.randomUUID() + "@Example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.1");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, "SecurePassword123!", "Target"))))
                .andExpect(status().isCreated());

        for (int i = 1; i <= 3; i++) {
            int ipSuffix = 10 + i;
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr("198.51.100." + ipSuffix);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(email, "WrongPassword!"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.99");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email.toLowerCase(), "WrongPassword!"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        Set<String> accountKeys = redisTemplate.keys("ratelimit:auth:account:*");
        assertThat(accountKeys).isNotNull().hasSize(1);
        String accountKey = accountKeys.iterator().next();
        assertThat(accountKey).doesNotContainIgnoringCase("target").doesNotContain("@");
        assertThat(redisTemplate.getExpire(accountKey, TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    @DisplayName("Payment write rate limit throttles POST mutations per user while keeping GET queries unthrottled")
    void paymentWriteRateLimitProtectsMutationsAndSparesReads() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenIssuer.issueAccessToken(userId, List.of("USER"));

        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(post("/api/v1/payments")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "rl-write-" + i + "-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("12.50"), "BRL", "Allowed"))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "rl-write-blocked-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("12.50"), "BRL", "Blocked"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        String writeKey = "ratelimit:payments:write:" + userId;
        assertThat(redisTemplate.getExpire(writeKey, TimeUnit.MILLISECONDS)).isPositive();

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
