package com.muse.payments.shared.infrastructure.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyCleanupTest {

    @Test
    @DisplayName("cleanup asks the repository to delete a bounded batch of expired keys")
    void deleteExpiredDelegatesToRepository() throws NoSuchMethodException {
        IdempotencyRepository repository = mock(IdempotencyRepository.class);
        when(repository.deleteExpired(any(Instant.class), eq(IdempotencyCleanup.BATCH_SIZE))).thenReturn(3);

        IdempotencyCleanup cleanup = new IdempotencyCleanup(repository);

        assertThat(cleanup.deleteExpired()).isEqualTo(3);
        verify(repository).deleteExpired(any(Instant.class), eq(IdempotencyCleanup.BATCH_SIZE));

        Scheduled scheduled = IdempotencyCleanup.class.getMethod("deleteExpired").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).contains("app.idempotency.cleanup-interval-ms");
    }
}
