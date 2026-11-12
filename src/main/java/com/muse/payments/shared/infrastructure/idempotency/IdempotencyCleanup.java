package com.muse.payments.shared.infrastructure.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class IdempotencyCleanup {

    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final IdempotencyRepository repository;

    public IdempotencyCleanup(IdempotencyRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval-ms:3600000}")
    public int deleteExpired() {
        int total = 0;
        int removed;
        do {
            removed = repository.deleteExpired(Instant.now(), BATCH_SIZE);
            total += removed;
        } while (removed == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} expired idempotency keys", total);
        }
        return total;
    }
}
