package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.application.PaymentWriteRateLimitPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PaymentWriteRateLimiter implements PaymentWriteRateLimitPort {

    private static final Logger log = LoggerFactory.getLogger(PaymentWriteRateLimiter.class);

    private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);
    private static final int MAX_FALLBACK_ENTRIES = 10_000;
    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxWritesPerMinute;
    private final Map<String, WindowCounter> fallbackCache = new ConcurrentHashMap<>();

    public PaymentWriteRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.payments.write-max-requests-per-minute:120}") int maxWritesPerMinute
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        if (maxWritesPerMinute <= 0) {
            throw new IllegalArgumentException("app.payments.write-max-requests-per-minute must be positive");
        }
        this.maxWritesPerMinute = maxWritesPerMinute;
    }

    @Override
    public boolean tryAcquire(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String key = "ratelimit:payments:write:" + userId;
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_WITH_TTL,
                    List.of(key),
                    String.valueOf(WINDOW_DURATION.toMillis())
            );
            return count != null && count <= maxWritesPerMinute;
        } catch (Exception e) {
            log.warn("Redis unavailable for payment write rate limiting ({}), using in-memory fallback", e.getMessage());
            return tryAcquireInMemory(key);
        }
    }

    private boolean tryAcquireInMemory(String key) {
        Instant now = Instant.now();
        if (fallbackCache.size() >= MAX_FALLBACK_ENTRIES) {
            fallbackCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        }
        WindowCounter counter = fallbackCache.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now)) {
                return new WindowCounter(new AtomicInteger(1), now.plus(WINDOW_DURATION));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return counter.count().get() <= maxWritesPerMinute;
    }

    private record WindowCounter(AtomicInteger count, Instant expiresAt) {
    }
}
