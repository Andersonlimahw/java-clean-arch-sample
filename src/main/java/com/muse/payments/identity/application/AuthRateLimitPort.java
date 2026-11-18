package com.muse.payments.identity.application;

public interface AuthRateLimitPort {

    boolean tryAcquire(String clientIp);

    default boolean tryAcquireAccount(String email) {
        return true;
    }
}
