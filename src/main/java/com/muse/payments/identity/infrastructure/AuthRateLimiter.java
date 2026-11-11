package com.muse.payments.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    private static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final Map<String, WindowCounter> fallbackCache = new ConcurrentHashMap<>();

    public AuthRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String clientIp) {
        String key = "ratelimit:auth:" + (clientIp != null ? clientIp : "anonymous");

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, WINDOW_DURATION);
            }
            return count != null && count <= MAX_REQUESTS_PER_MINUTE;
        } catch (Exception e) {
            log.warn("Redis unavailable for rate limiting ({}), using in-memory fallback", e.getMessage());
            return tryAcquireInMemory(key);
        }
    }

    private boolean tryAcquireInMemory(String key) {
        Instant now = Instant.now();
        WindowCounter counter = fallbackCache.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt.isBefore(now)) {
                return new WindowCounter(new AtomicInteger(1), now.plus(WINDOW_DURATION));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        return counter.count.get() <= MAX_REQUESTS_PER_MINUTE;
    }

    private record WindowCounter(AtomicInteger count, Instant expiresAt) {
    }
}
