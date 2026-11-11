package com.muse.payments.shared.infrastructure.idempotency;

import com.muse.payments.shared.domain.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private IdempotencyRepository repository;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRepository.class);
        service = new IdempotencyService(repository);
    }

    @Test
    @DisplayName("evaluateOrAcquire proceeds when key is newly acquired")
    void evaluateOrAcquire_proceedOnNewKey() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-1";
        String payload = "{\"amount\":100}";

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(true);

        IdempotencyDecision decision = service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1));

        assertThat(decision.isReplayed()).isFalse();
    }

    @Test
    @DisplayName("evaluateOrAcquire replays cached response when key exists with COMPLETED and same hash")
    void evaluateOrAcquire_replayedOnMatchingPayload() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-2";
        String payload = "{\"amount\":100}";
        String hash = IdempotencyService.computeHash(payload);

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(false);
        when(repository.find(userId, key)).thenReturn(Optional.of(new IdempotencyRecord(
                key, userId, hash, IdempotencyRecord.STATUS_COMPLETED, 201, "{\"id\":\"123\"}", Instant.now(), Instant.now().plusSeconds(3600)
        )));

        IdempotencyDecision decision = service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1));

        assertThat(decision.isReplayed()).isTrue();
        assertThat(decision.responseCode()).isEqualTo(201);
        assertThat(decision.responseBody()).isEqualTo("{\"id\":\"123\"}");
    }

    @Test
    @DisplayName("evaluateOrAcquire throws ConflictException when payload hash differs for same key")
    void evaluateOrAcquire_throwsConflictOnPayloadMismatch() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-3";
        String payload = "{\"amount\":100}";
        String differentHash = "different-hash-value";

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(false);
        when(repository.find(userId, key)).thenReturn(Optional.of(new IdempotencyRecord(
                key, userId, differentHash, IdempotencyRecord.STATUS_COMPLETED, 201, "{\"id\":\"123\"}", Instant.now(), Instant.now().plusSeconds(3600)
        )));

        assertThatThrownBy(() -> service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Idempotency key payload mismatch");
    }

    @Test
    @DisplayName("evaluateOrAcquire throws ConflictException when request with same key is currently PROCESSING")
    void evaluateOrAcquire_throwsConflictOnConcurrentProcessing() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-4";
        String payload = "{\"amount\":100}";

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(false);
        when(repository.find(userId, key)).thenReturn(Optional.of(new IdempotencyRecord(
                key, userId, "hash", IdempotencyRecord.STATUS_PROCESSING, null, null, Instant.now(), Instant.now().plusSeconds(3600)
        )));

        assertThatThrownBy(() -> service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Concurrent request in progress");
    }

    @Test
    @DisplayName("saveCompleted delegates to repository markCompleted")
    void saveCompleted_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-5";

        service.saveCompleted(userId, key, 201, "{\"id\":\"abc\"}");

        verify(repository).markCompleted(userId, key, 201, "{\"id\":\"abc\"}");
    }
}
