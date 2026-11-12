package com.muse.payments.shared.infrastructure.idempotency;

import java.time.Instant;
import java.util.UUID;

public record ProcessingLease(UUID leaseId, Instant createdAt) {
}
