package com.muse.payments.identity;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginCommand;
import com.muse.payments.identity.application.LoginCommandHandler;
import com.muse.payments.identity.application.RegisterCommand;
import com.muse.payments.identity.application.RegisterCommandHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LoginPerformanceIT extends TestcontainersBase {

    @Autowired
    private RegisterCommandHandler registerHandler;

    @Autowired
    private LoginCommandHandler loginHandler;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("BCrypt default cost is 10 and sequential login p95 is under 150ms")
    void measureLoginLatency() throws Exception {
        assertThat(passwordEncoder.encode("CostCheck123!")).startsWith("$2a$10$");
        String email = "perf." + UUID.randomUUID() + "@example.com";
        String password = "SecurePassword123!";
        registerHandler.handle(new RegisterCommand(email, password, "Perf User"));

        LoginCommand command = new LoginCommand(email, password);

        // Warmup (JIT + Hikari + Nimbus signer)
        for (int i = 0; i < 3; i++) {
            AuthTokensResponse warmup = loginHandler.handle(command);
            assertThat(warmup.accessToken()).isNotBlank();
        }

        // 1. Sequential p50 / p95 / p99 (20 requests)
        int seqCount = 20;
        long[] seqNanos = new long[seqCount];
        for (int i = 0; i < seqCount; i++) {
            long start = System.nanoTime();
            AuthTokensResponse res = loginHandler.handle(command);
            seqNanos[i] = System.nanoTime() - start;
            assertThat(res.accessToken()).isNotBlank();
        }
        Arrays.sort(seqNanos);

        // 2. Concurrent virtual-thread burst (20 concurrent requests)
        int burstCount = 20;
        long[] burstNanos = new long[burstCount];
        long burstWallStart = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Long>> tasks = new ArrayList<>(burstCount);
            for (int i = 0; i < burstCount; i++) {
                tasks.add(() -> {
                    long start = System.nanoTime();
                    AuthTokensResponse res = loginHandler.handle(command);
                    long elapsed = System.nanoTime() - start;
                    assertThat(res.accessToken()).isNotBlank();
                    return elapsed;
                });
            }
            List<Future<Long>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < burstCount; i++) {
                burstNanos[i] = futures.get(i).get();
            }
        }
        long burstWallMs = (System.nanoTime() - burstWallStart) / 1_000_000;
        Arrays.sort(burstNanos);

        System.out.printf(
                "LOGIN_BENCHMARK_SEQ count=%d p50=%.1fms p95=%.1fms p99=%.1fms%n",
                seqCount,
                percentileMs(seqNanos, 0.50),
                percentileMs(seqNanos, 0.95),
                percentileMs(seqNanos, 0.99)
        );
        System.out.printf(
                "LOGIN_BENCHMARK_BURST count=%d wall=%dms p50=%.1fms p95=%.1fms p99=%.1fms%n",
                burstCount,
                burstWallMs,
                percentileMs(burstNanos, 0.50),
                percentileMs(burstNanos, 0.95),
                percentileMs(burstNanos, 0.99)
        );

        // Override on slow or shared machines: -Dlogin.p95.budget.ms=300
        double budgetMs = Double.parseDouble(System.getProperty("login.p95.budget.ms", "150"));
        assertThat(percentileMs(seqNanos, 0.95))
                .as("Sequential login p95 latency should stay under %.0fms", budgetMs)
                .isLessThan(budgetMs);
    }

    private static double percentileMs(long[] sortedNanos, double p) {
        int index = Math.min(sortedNanos.length - 1, (int) Math.ceil(p * sortedNanos.length) - 1);
        return sortedNanos[index] / 1_000_000.0;
    }
}
