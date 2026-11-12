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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

        UUID leaseId = UUID.randomUUID();
        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.of(leaseId));

        IdempotencyDecision decision = service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1));

        assertThat(decision.isReplayed()).isFalse();
        assertThat(decision.leaseId()).isEqualTo(leaseId);
    }

    @Test
    @DisplayName("evaluateOrAcquire replays cached response when key exists with COMPLETED and same hash")
    void evaluateOrAcquire_replayedOnMatchingPayload() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-2";
        String payload = "{\"amount\":100}";
        String hash = IdempotencyService.computeHash(payload);

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.empty());
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

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.empty());
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

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.empty());
        when(repository.find(userId, key)).thenReturn(Optional.of(new IdempotencyRecord(
                key, userId, "hash", IdempotencyRecord.STATUS_PROCESSING, null, null, Instant.now(), Instant.now().plusSeconds(3600)
        )));

        assertThatThrownBy(() -> service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Concurrent request in progress");
    }

    @Test
    @DisplayName("expired completed key is acquired as a new request instead of being replayed")
    void expiredCompletedKeyIsNotReplayed() {
        UUID userId = UUID.randomUUID();
        String key = "expired-completed";
        String payload = "{\"amount\":100}";

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any()))
                .thenReturn(Optional.empty(), Optional.of(UUID.randomUUID()));
        when(repository.find(userId, key)).thenReturn(Optional.of(expiredRecord(
                key, userId, "different-hash", IdempotencyRecord.STATUS_COMPLETED, 201, "{\"id\":\"old\"}"
        )));

        IdempotencyDecision decision = service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(24));

        assertThat(decision.isReplayed()).isFalse();
        verify(repository, times(2)).tryInsertProcessing(eq(userId), eq(key), any(), any());
    }

    @Test
    @DisplayName("expired in-progress key is acquired as a new request instead of a concurrent conflict")
    void expiredProcessingKeyIsNotAConflict() {
        UUID userId = UUID.randomUUID();
        String key = "expired-processing";
        String payload = "{\"amount\":100}";

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any()))
                .thenReturn(Optional.empty(), Optional.of(UUID.randomUUID()));
        when(repository.find(userId, key)).thenReturn(Optional.of(expiredRecord(
                key, userId, "hash", IdempotencyRecord.STATUS_PROCESSING, null, null
        )));

        IdempotencyDecision decision = service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(24));

        assertThat(decision.isReplayed()).isFalse();
        verify(repository, times(2)).tryInsertProcessing(eq(userId), eq(key), any(), any());
    }

    @Test
    @DisplayName("expired key that cannot be reacquired is not replayed")
    void expiredKeyThatCannotBeReacquiredIsNotReplayed() {
        UUID userId = UUID.randomUUID();
        String key = "expired-stuck";
        String payload = "{\"amount\":100}";
        String hash = IdempotencyService.computeHash(payload);

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.empty());
        when(repository.find(userId, key)).thenReturn(Optional.of(expiredRecord(
                key, userId, hash, IdempotencyRecord.STATUS_COMPLETED, 201, "{\"id\":\"old\"}"
        )));

        assertThatThrownBy(() -> service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(24)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Concurrent request in progress");
        verify(repository, times(2)).tryInsertProcessing(eq(userId), eq(key), any(), any());
    }

    @Test
    @DisplayName("live completed key still replays the cached response")
    void liveCompletedKeyStillReplays() {
        UUID userId = UUID.randomUUID();
        String key = "live-key";
        String payload = "{\"amount\":100}";
        String hash = IdempotencyService.computeHash(payload);

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.empty());
        when(repository.find(userId, key)).thenReturn(Optional.of(new IdempotencyRecord(
                key, userId, hash, IdempotencyRecord.STATUS_COMPLETED, 201, "{\"id\":\"123\"}",
                Instant.now(), Instant.now().plus(Duration.ofHours(24))
        )));

        IdempotencyDecision decision = service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(24));

        assertThat(decision.isReplayed()).isTrue();
        assertThat(decision.responseCode()).isEqualTo(201);
        assertThat(decision.responseBody()).isEqualTo("{\"id\":\"123\"}");
        verify(repository, times(1)).tryInsertProcessing(eq(userId), eq(key), any(), any());
    }

    @Test
    @DisplayName("saveCompleted delegates to repository markCompleted")
    void saveCompleted_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        String key = "test-key-5";

        String payload = "{\"amount\":100}";
        UUID leaseId = UUID.randomUUID();
        when(repository.markCompleted(userId, key, IdempotencyService.computeHash(payload), leaseId, 201, "{\"id\":\"abc\"}"))
                .thenReturn(1);

        service.saveCompleted(userId, key, payload, leaseId, 201, "{\"id\":\"abc\"}");

        verify(repository).markCompleted(userId, key, IdempotencyService.computeHash(payload), leaseId, 201, "{\"id\":\"abc\"}");
    }

    @Test
    @DisplayName("a missed completion does not fail the caller that already created the payment")
    void saveCompleted_ignoresAMissedUpdate() {
        UUID userId = UUID.randomUUID();
        String key = "lost-lease";
        String payload = "{\"amount\":100}";
        UUID leaseId = UUID.randomUUID();
        when(repository.markCompleted(any(), any(), any(), any(), eq(201), any())).thenReturn(0);

        assertThatCode(() -> service.saveCompleted(userId, key, payload, leaseId, 201, "{\"id\":\"abc\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a live key in an unknown status is a conflict and does not proceed")
    void liveUnknownStatusIsConflict() {
        UUID userId = UUID.randomUUID();
        String key = "weird-status";
        String payload = "{\"amount\":100}";

        when(repository.tryInsertProcessing(eq(userId), eq(key), any(), any())).thenReturn(Optional.empty());
        when(repository.find(userId, key)).thenReturn(Optional.of(new IdempotencyRecord(
                key, userId, "hash", "FAILED", null, null, Instant.now(), Instant.now().plusSeconds(3600)
        )));

        assertThatThrownBy(() -> service.evaluateOrAcquire(userId, key, payload, Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be reused");
        verify(repository, times(1)).tryInsertProcessing(eq(userId), eq(key), any(), any());
    }

    @Test
    @DisplayName("releaseProcessing deletes only the caller's processing lease")
    void releaseProcessing_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        String key = "release-me";
        String payload = "{\"amount\":100}";
        UUID leaseId = UUID.randomUUID();

        service.releaseProcessing(userId, key, payload, leaseId);

        verify(repository).deleteProcessing(userId, key, IdempotencyService.computeHash(payload), leaseId);
    }

    private static IdempotencyRecord expiredRecord(
            String key,
            UUID userId,
            String requestHash,
            String status,
            Integer responseCode,
            String responseBody
    ) {
        return new IdempotencyRecord(
                key,
                userId,
                requestHash,
                status,
                responseCode,
                responseBody,
                Instant.now().minus(Duration.ofHours(25)),
                Instant.now().minusSeconds(30)
        );
    }
}
