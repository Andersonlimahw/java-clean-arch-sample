package com.muse.payments.shared.security;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs password hashing on a small pool of platform threads when the caller is a virtual thread.
 *
 * <p>BCrypt is CPU work that never blocks, and a virtual thread is not time-sliced: it keeps
 * its carrier until it blocks or ends. A few concurrent logins would therefore occupy every
 * carrier for the whole hash and stall the other requests. A platform thread is preempted by
 * the operating system, so the carriers stay free while the caller waits parked.
 *
 * <p>When the pool and its queue are full the hash runs on the caller, which is the behavior
 * without this class. A caller that is already a platform thread is never offloaded.
 */
public final class OffloadedPasswordEncoder implements PasswordEncoder, AutoCloseable {

    static final String THREAD_NAME_PREFIX = "password-hash-";
    private static final int QUEUE_SLOTS_PER_THREAD = 8;

    private final PasswordEncoder delegate;
    private final ThreadPoolExecutor executor;

    public OffloadedPasswordEncoder(PasswordEncoder delegate, int threads) {
        this.delegate = Objects.requireNonNull(delegate);
        if (threads < 1) {
            throw new IllegalArgumentException("password hashing threads must be at least 1");
        }
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(threads * QUEUE_SLOTS_PER_THREAD),
                Thread.ofPlatform().daemon().name(THREAD_NAME_PREFIX, 0).factory());
    }

    /** Half of the available processors, at least one. */
    public static int defaultThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return offload(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return offload(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private <T> T offload(Callable<T> hashing) {
        if (!Thread.currentThread().isVirtual()) {
            return call(hashing);
        }
        Future<T> future;
        try {
            future = executor.submit(hashing);
        } catch (RejectedExecutionException saturatedOrClosed) {
            return call(hashing);
        }
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while hashing a password", ex);
        } catch (ExecutionException ex) {
            throw unwrap(ex.getCause());
        }
    }

    private static <T> T call(Callable<T> hashing) {
        try {
            return hashing.call();
        } catch (Exception ex) {
            throw unwrap(ex);
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Password hashing failed", cause);
    }
}
