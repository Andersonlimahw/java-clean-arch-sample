package com.muse.payments.payments.infrastructure.fraud;

import com.muse.fraudscreening.FraudScreeningTestApplication;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = FraudScreeningTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "management.tracing.enabled=false",
                "management.otlp.tracing.export.enabled=false",
                "management.otlp.metrics.export.enabled=false",
                "spring.main.banner-mode=off",
                "spring.modulith.moments.enabled=false",
                "logging.structured.format.console=",
                "management.endpoint.health.validate-group-membership=false"
        }
)
@ActiveProfiles("prod")
class FraudScreeningProfileConfigTest {

    @Autowired
    private TimeLimiterRegistry timeLimiters;

    @Autowired
    private RetryRegistry retries;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Test
    @DisplayName("The prod profile uses a shorter timeout and fewer attempts than the base file")
    void prodLimits() {
        assertThat(timeLimiters.timeLimiter("fraudScreening").getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofMillis(400));
        assertThat(retries.retry("fraudScreening").getRetryConfig().getMaxAttempts()).isEqualTo(2);
        assertThat(circuitBreakers.circuitBreaker("fraudScreening").getCircuitBreakerConfig().getFailureRateThreshold())
                .isEqualTo(40f);
    }
}
