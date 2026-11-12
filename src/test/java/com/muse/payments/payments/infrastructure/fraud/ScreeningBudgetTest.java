package com.muse.payments.payments.infrastructure.fraud;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningBudgetTest {

    @Test
    void budgetCoversEveryAttemptAndTheJitterCeiling() {
        Duration budget = ScreeningBudget.of(
                Duration.ofSeconds(1),
                3,
                Duration.ofMillis(100),
                true,
                2d,
                true,
                0.5d);

        assertThat(budget).isEqualTo(Duration.ofMillis(3_000 + 150 + 300));
    }
}
