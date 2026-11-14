package com.muse.payments.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OffloadedPasswordEncoderTest {

    @Test
    void virtualCallerHashesOnAPlatformThread() throws Exception {
        RecordingEncoder delegate = new RecordingEncoder();
        try (OffloadedPasswordEncoder encoder = new OffloadedPasswordEncoder(delegate, 1)) {
            AtomicReference<String> encoded = new AtomicReference<>();
            AtomicReference<Boolean> matched = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> {
                encoded.set(encoder.encode("secret"));
                matched.set(encoder.matches("secret", "hash:secret"));
            });
            caller.join(5_000);

            assertThat(encoded.get()).isEqualTo("hash:secret");
            assertThat(matched.get()).isTrue();
            assertThat(delegate.lastThread.get().isVirtual()).isFalse();
            assertThat(delegate.lastThread.get().getName()).startsWith(OffloadedPasswordEncoder.THREAD_NAME_PREFIX);
        }
    }

    @Test
    void platformCallerHashesInline() {
        RecordingEncoder delegate = new RecordingEncoder();
        try (OffloadedPasswordEncoder encoder = new OffloadedPasswordEncoder(delegate, 1)) {
            assertThat(encoder.encode("secret")).isEqualTo("hash:secret");
            assertThat(delegate.lastThread.get()).isSameAs(Thread.currentThread());
        }
    }

    @Test
    void saturatedPoolHashesOnTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        RecordingEncoder delegate = new RecordingEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                if ("block".contentEquals(rawPassword)) {
                    workerBusy.countDown();
                    await(release);
                }
                return super.encode(rawPassword);
            }
        };
        try (OffloadedPasswordEncoder encoder = new OffloadedPasswordEncoder(delegate, 1)) {
            // One worker and eight queue slots: nine blocked hashes fill the pool.
            Thread[] blocked = new Thread[9];
            for (int i = 0; i < blocked.length; i++) {
                blocked[i] = Thread.ofVirtual().start(() -> encoder.encode("block"));
            }
            assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();
            awaitQueued(blocked);

            AtomicReference<Thread> overflowThread = new AtomicReference<>();
            Thread overflow = Thread.ofVirtual().start(() -> {
                encoder.encode("overflow");
                overflowThread.set(delegate.lastThread.get());
            });
            overflow.join(5_000);
            assertThat(overflowThread.get()).isSameAs(overflow);

            release.countDown();
            for (Thread thread : blocked) {
                thread.join(5_000);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void delegateFailureReachesTheVirtualCaller() throws Exception {
        PasswordEncoder failing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalArgumentException("bad password");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
        try (OffloadedPasswordEncoder encoder = new OffloadedPasswordEncoder(failing, 1)) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    encoder.encode("secret");
                } catch (Throwable ex) {
                    thrown.set(ex);
                }
            });
            caller.join(5_000);
            assertThat(thrown.get()).isInstanceOf(IllegalArgumentException.class).hasMessage("bad password");
        }
    }

    @Test
    void nonPositiveThreadCountIsRejected() {
        assertThatThrownBy(() -> new OffloadedPasswordEncoder(new RecordingEncoder(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Waits until every blocked caller is parked on its future: one running, eight queued. */
    private static void awaitQueued(Thread[] callers) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean allParked = true;
            for (Thread caller : callers) {
                Thread.State state = caller.getState();
                if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                    allParked = false;
                    break;
                }
            }
            if (allParked) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("blocked callers were not parked in time");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static class RecordingEncoder implements PasswordEncoder {
        final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public String encode(CharSequence rawPassword) {
            lastThread.set(Thread.currentThread());
            return "hash:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            lastThread.set(Thread.currentThread());
            return encodedPassword.equals("hash:" + rawPassword);
        }
    }
}
