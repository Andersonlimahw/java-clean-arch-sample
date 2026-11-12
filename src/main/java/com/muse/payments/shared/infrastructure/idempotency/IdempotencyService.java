package com.muse.payments.shared.infrastructure.idempotency;

import com.muse.payments.shared.application.IdempotencyDecision;
import com.muse.payments.shared.application.IdempotencyPort;
import com.muse.payments.shared.domain.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService implements IdempotencyPort {

    public static final Duration DEFAULT_TTL = IdempotencyPort.COMPLETED_TTL;

    private final IdempotencyRepository repository;
    private final Duration processingTtl;

    public IdempotencyService(
            IdempotencyRepository repository,
            @Value("${app.idempotency.processing-ttl:2m}") Duration processingTtl
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.processingTtl = processingTtl;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyDecision acquireProcessing(UUID userId, String key, String payloadJson) {
        return evaluateOrAcquire(userId, key, payloadJson, processingTtl);
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyDecision evaluateOrAcquire(UUID userId, String key, String payloadJson, Duration ttl) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(key, "key must not be null");

        String requestHash = computeHash(payloadJson);
        Duration effectiveTtl = ttl != null ? ttl : DEFAULT_TTL;

        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<UUID> leaseId = repository.tryInsertProcessing(userId, key, requestHash, effectiveTtl);
            if (leaseId.isPresent()) {
                return IdempotencyDecision.proceed(leaseId.get());
            }

            Optional<IdempotencyRecord> optionalRecord = repository.find(userId, key);
            if (optionalRecord.isEmpty() || optionalRecord.get().isExpired(Instant.now())) {
                continue;
            }
            return decisionForLiveRecord(optionalRecord.get(), requestHash, key);
        }

        throw new ConflictException("Concurrent request in progress for idempotency key: " + key);
    }

    private IdempotencyDecision decisionForLiveRecord(IdempotencyRecord record, String requestHash, String key) {
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

        throw new ConflictException("Idempotency key cannot be reused while it is unexpired: " + key);
    }

    @Transactional
    public void saveCompleted(UUID userId, String key, String payloadJson, UUID leaseId, int statusCode, String responseJson) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        int updated = repository.markCompleted(userId, key, computeHash(payloadJson), leaseId, statusCode, responseJson);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency completion matched no row for key: " + key);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseProcessing(UUID userId, String key, String payloadJson, UUID leaseId) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        repository.deleteProcessing(userId, key, computeHash(payloadJson), leaseId);
    }
}
