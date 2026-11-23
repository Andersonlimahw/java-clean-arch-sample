package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.application.AuthRateLimitPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AuthRateLimiter implements AuthRateLimitPort {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

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
    private final int maxIpRequestsPerMinute;
    private final int maxAccountRequestsPerMinute;
    private final Map<String, WindowCounter> fallbackCache = new ConcurrentHashMap<>();

    public AuthRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.security.auth-ip-max-requests-per-minute:20}") int maxIpRequestsPerMinute,
            @Value("${app.security.auth-account-max-requests-per-minute:15}") int maxAccountRequestsPerMinute
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        if (maxIpRequestsPerMinute <= 0 || maxAccountRequestsPerMinute <= 0) {
            throw new IllegalArgumentException("Auth rate limits must be positive");
        }
        this.maxIpRequestsPerMinute = maxIpRequestsPerMinute;
        this.maxAccountRequestsPerMinute = maxAccountRequestsPerMinute;
    }

    @Override
    public boolean tryAcquire(String clientIp) {
        String normalizedIp = (clientIp != null && !clientIp.isBlank()) ? clientIp.trim() : "anonymous";
        return acquireBucket("ratelimit:auth:" + normalizedIp, maxIpRequestsPerMinute);
    }

    @Override
    public boolean tryAcquireAccount(String email) {
        if (email == null || email.isBlank()) {
            return true;
        }
        return acquireBucket(accountKey(email), maxAccountRequestsPerMinute);
    }

    @Override
    public void forgetAccount(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        String key = accountKey(email);
        fallbackCache.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis unavailable while dropping an account rate-limit bucket ({}); it expires within {}",
                    e.getMessage(), WINDOW_DURATION);
        }
    }

    private static String accountKey(String email) {
        return "ratelimit:auth:account:" + sha256Hex(email.trim().toLowerCase(Locale.ROOT));
    }

    private boolean acquireBucket(String key, int limit) {
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_WITH_TTL,
                    List.of(key),
                    String.valueOf(WINDOW_DURATION.toMillis())
            );
            return count != null && count <= limit;
        } catch (Exception e) {
            log.warn("Redis unavailable for auth rate limiting ({}), using in-memory fallback", e.getMessage());
            return tryAcquireInMemory(key, limit);
        }
    }

    private boolean tryAcquireInMemory(String key, int limit) {
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

        return counter.count().get() <= limit;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record WindowCounter(AtomicInteger count, Instant expiresAt) {
    }
}
