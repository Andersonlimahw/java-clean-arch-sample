package com.muse.payments.payments.application;

import java.util.UUID;

public interface PaymentWriteRateLimitPort {

    boolean tryAcquire(UUID userId);
}
