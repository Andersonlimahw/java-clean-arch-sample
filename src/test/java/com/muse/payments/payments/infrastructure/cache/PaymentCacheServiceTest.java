package com.muse.payments.payments.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.PaymentResponse;
import com.muse.payments.payments.domain.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private PaymentCacheService cacheService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        meterRegistry = new SimpleMeterRegistry();

        cacheService = new PaymentCacheService(redisTemplate, objectMapper, meterRegistry, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("get returns cached response and increments hit counter on cache hit")
    void get_onHit_returnsValueAndIncrementsHits() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
                paymentId, userId, new BigDecimal("100.00"), "BRL", "Test",
                PaymentStatus.PENDING, Instant.now(), Instant.now(), 0L
        );
        String json = objectMapper.writeValueAsString(response);

        when(valueOperations.get("payment:cache:" + paymentId)).thenReturn(json);

        Optional<PaymentResponse> result = cacheService.get(paymentId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(paymentId);
        assertThat(meterRegistry.counter("payments.cache.hits").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("get returns empty and increments miss counter on cache miss")
    void get_onMiss_returnsEmptyAndIncrementsMisses() {
        UUID paymentId = UUID.randomUUID();
        when(valueOperations.get("payment:cache:" + paymentId)).thenReturn(null);

        Optional<PaymentResponse> result = cacheService.get(paymentId);

        assertThat(result).isEmpty();
        assertThat(meterRegistry.counter("payments.cache.misses").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("get gracefully falls back to empty and records error when Redis throws exception")
    void get_onRedisError_fallsBackGracefully() {
        UUID paymentId = UUID.randomUUID();
        when(valueOperations.get("payment:cache:" + paymentId)).thenThrow(new RuntimeException("Redis connection refused"));

        Optional<PaymentResponse> result = cacheService.get(paymentId);

        assertThat(result).isEmpty();
        assertThat(meterRegistry.counter("payments.cache.errors").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("put serializes response and saves with TTL")
    void put_savesValueWithTtl() {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
                paymentId, userId, new BigDecimal("50.00"), "USD", "Desc",
                PaymentStatus.PENDING, Instant.now(), Instant.now(), 0L
        );

        cacheService.put(paymentId, response);

        verify(valueOperations).set(eq("payment:cache:" + paymentId), any(), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("evict deletes key from Redis")
    void evict_deletesKey() {
        UUID paymentId = UUID.randomUUID();

        cacheService.evict(paymentId);

        verify(redisTemplate).delete("payment:cache:" + paymentId);
    }
}
