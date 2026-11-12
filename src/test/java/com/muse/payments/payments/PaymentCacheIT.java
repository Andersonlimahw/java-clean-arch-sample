package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PaymentCacheIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private String authenticateUser(String email, String password) throws Exception {
        return PaymentHttp.registerAndLogin(mockMvc, objectMapper, email, password);
    }

    @Test
    @DisplayName("Cache-Aside Flow: Miss populates Redis, second call hits cache, confirm evicts cache after commit")
    void testCacheAsideAndTransactionalEviction() throws Exception {
        String token = authenticateUser("cache_user@example.com", "SecurePass123!");

        // 1. Create a payment
        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(
                new BigDecimal("750.00"),
                "BRL",
                "Cache Evaluation"
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID paymentId = UUID.fromString(jsonNode.get("id").asText());
        String cacheKey = "payment:cache:" + paymentId;

        // Baseline metrics
        double initialMisses = meterRegistry.counter("payments.cache.misses").count();
        double initialHits = meterRegistry.counter("payments.cache.hits").count();

        // 2. First GET -> Cache MISS -> loads from Postgres and populates Redis
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(meterRegistry.counter("payments.cache.misses").count()).isGreaterThan(initialMisses);
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cachedJson).isNotNull().contains(paymentId.toString());

        // 3. Second GET -> Cache HIT -> reads directly from Redis
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId.toString()));

        assertThat(meterRegistry.counter("payments.cache.hits").count()).isGreaterThan(initialHits);

        // 4. Update payment (confirm) -> Transactional Eviction removes key after commit
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Verify Redis key was evicted
        String afterConfirmCached = redisTemplate.opsForValue().get(cacheKey);
        assertThat(afterConfirmCached).isNull();

        // 5. Third GET -> Cache MISS -> populates Redis with new COMPLETED state
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(1));

        String refreshedCached = redisTemplate.opsForValue().get(cacheKey);
        assertThat(refreshedCached).isNotNull().contains("COMPLETED");
    }
}
