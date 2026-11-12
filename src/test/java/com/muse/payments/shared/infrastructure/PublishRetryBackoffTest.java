package com.muse.payments.shared.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PublishRetryBackoffTest {

    @Test
    void delayGrowsExponentiallyAndCapsAtFiveMinutes() {
        assertThat(PublishRetryBackoff.delay(1, 1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(PublishRetryBackoff.delay(2, 2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(PublishRetryBackoff.delay(3, 3)).isEqualTo(Duration.ofSeconds(19));
        assertThat(PublishRetryBackoff.delay(20, 3)).isEqualTo(Duration.ofSeconds(PublishRetryBackoff.MAX_SECONDS));
    }

    @Test
    void negativeJitterIsIgnored() {
        assertThat(PublishRetryBackoff.delay(1, -5)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void attemptsBelowOneUseTheFirstStep() {
        assertThat(PublishRetryBackoff.delay(0, 0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(PublishRetryBackoff.delay(-4, 1)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void jitterCannotPushTheDelayPastTheCap() {
        assertThat(PublishRetryBackoff.delay(8, 10_000)).isEqualTo(Duration.ofSeconds(PublishRetryBackoff.MAX_SECONDS));
        assertThat(PublishRetryBackoff.delay(7, 1_000)).isEqualTo(Duration.ofSeconds(PublishRetryBackoff.MAX_SECONDS));
    }
}
