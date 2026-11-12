package com.muse.payments.payments.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.PaymentCachePort;
import com.muse.payments.payments.application.PaymentResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class PaymentCacheService implements PaymentCachePort {

    private static final Logger log = LoggerFactory.getLogger(PaymentCacheService.class);
    private static final String KEY_PREFIX = "payment:cache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter errorCounter;
    private final Timer latencyTimer;

    private final ConcurrentHashMap<UUID, Flight> flights = new ConcurrentHashMap<>();

    public PaymentCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.cache.payment-ttl:10m}") Duration ttl
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.ttl = ttl != null ? ttl : Duration.ofMinutes(10);

        this.hitCounter = meterRegistry.counter("payments.cache.hits");
        this.missCounter = meterRegistry.counter("payments.cache.misses");
        this.errorCounter = meterRegistry.counter("payments.cache.errors");
        this.latencyTimer = meterRegistry.timer("payments.cache.latency");
    }

    public Optional<PaymentResponse> get(UUID paymentId) {
        String key = buildKey(paymentId);
        try {
            return latencyTimer.record(() -> {
                String cachedJson = redisTemplate.opsForValue().get(key);
                if (cachedJson != null && !cachedJson.isBlank()) {
                    hitCounter.increment();
                    log.debug("Cache HIT for payment {}", paymentId);
                    try {
                        return Optional.of(objectMapper.readValue(cachedJson, PaymentResponse.class));
                    } catch (Exception e) {
                        log.warn("Corrupted cache value for key {}. Evicting.", key);
                        evict(paymentId);
                        return Optional.empty();
                    }
                }
                missCounter.increment();
                log.debug("Cache MISS for payment {}", paymentId);
                return Optional.empty();
            });
        } catch (Exception ex) {
            errorCounter.increment();
            log.warn("Redis failure while reading cache key {}: {}. Proceeding to DB.", key, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(UUID paymentId, PaymentResponse response) {
        if (response == null) {
            return;
        }
        String key = buildKey(paymentId);
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, ttl);
            log.debug("Cached payment {} with TTL {}", paymentId, ttl);
        } catch (Exception ex) {
            errorCounter.increment();
            log.warn("Redis failure while storing cache key {}: {}", key, ex.getMessage());
        }
    }

    public void evict(UUID paymentId) {
        String key = buildKey(paymentId);
        try {
            redisTemplate.delete(key);
            log.debug("Evicted cache for payment {}", paymentId);
        } catch (Exception ex) {
            errorCounter.increment();
            log.warn("Redis failure while evicting cache key {}: {}", key, ex.getMessage());
        }
    }

    public void evictAfterCommit(UUID paymentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(paymentId);
                }
            });
        } else {
            evict(paymentId);
        }
    }

    public PaymentResponse getOrLoad(UUID paymentId, Supplier<PaymentResponse> dbLoader) {
        Optional<PaymentResponse> cached = get(paymentId);
        if (cached.isPresent()) {
            return cached.get();
        }

        Flight flight = flights.compute(paymentId, (id, existing) -> {
            Flight current = existing != null ? existing : new Flight();
            current.waiters++;
            return current;
        });
        flight.lock.lock();
        try {
            Optional<PaymentResponse> doubleChecked = get(paymentId);
            if (doubleChecked.isPresent()) {
                return doubleChecked.get();
            }

            PaymentResponse loaded = dbLoader.get();
            if (loaded != null) {
                put(paymentId, loaded);
            }
            return loaded;
        } finally {
            flight.lock.unlock();
            flights.compute(paymentId, (id, existing) -> {
                if (existing != flight) {
                    return existing;
                }
                existing.waiters--;
                return existing.waiters == 0 ? null : existing;
            });
        }
    }

    private static final class Flight {
        private final ReentrantLock lock = new ReentrantLock();
        private int waiters;
    }

    private String buildKey(UUID paymentId) {
        return KEY_PREFIX + paymentId.toString();
    }
}
