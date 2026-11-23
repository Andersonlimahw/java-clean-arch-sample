package com.muse.payments.identity.application;

public interface AuthRateLimitPort {

    boolean tryAcquire(String clientIp);

    default boolean tryAcquireAccount(String email) {
        return true;
    }

    /** Drops the per-account bucket, whose key is derived from the email, after an erasure. */
    default void forgetAccount(String email) {
    }
}
