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
}
