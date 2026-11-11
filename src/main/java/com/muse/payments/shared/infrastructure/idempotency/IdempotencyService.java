package com.muse.payments.shared.infrastructure.idempotency;

import com.muse.payments.shared.domain.ConflictException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static String computeHash(String payload) {
        if (payload == null) {
            payload = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public IdempotencyDecision evaluateOrAcquire(UUID userId, String key, String payloadJson, Duration ttl) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(key, "key must not be null");

        String requestHash = computeHash(payloadJson);
        boolean acquired = repository.tryInsertProcessing(userId, key, requestHash, ttl != null ? ttl : DEFAULT_TTL);

        if (acquired) {
            return IdempotencyDecision.proceed();
        }

        Optional<IdempotencyRecord> optionalRecord = repository.find(userId, key);
        if (optionalRecord.isEmpty()) {
            // Race condition where record was removed or expired between insert attempt and lookup
            return IdempotencyDecision.proceed();
        }

        IdempotencyRecord record = optionalRecord.get();
        if (record.isProcessing()) {
            throw new ConflictException("Concurrent request in progress for idempotency key: " + key);
        }

        if (record.isCompleted()) {
            if (!record.requestHash().equalsIgnoreCase(requestHash)) {
                throw new ConflictException("Idempotency key payload mismatch for key: " + key);
            }
            return IdempotencyDecision.replayed(
                    record.responseCode() != null ? record.responseCode() : 200,
                    record.responseBody()
            );
        }

        return IdempotencyDecision.proceed();
    }

    public void saveCompleted(UUID userId, String key, int statusCode, String responseJson) {
        repository.markCompleted(userId, key, statusCode, responseJson);
    }
}
