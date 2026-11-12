package com.muse.fraudscreening;

import com.muse.payments.payments.infrastructure.fraud.FraudScreeningConfiguration;
import com.muse.payments.payments.infrastructure.fraud.FraudScreeningProperties;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterMetricsAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Loads the fraud adapter and the Resilience4j registries without the rest of the platform.
 */
@SpringBootConfiguration
@EnableConfigurationProperties(FraudScreeningProperties.class)
@Import(FraudScreeningConfiguration.class)
@ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        RestClientAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        SimpleMetricsExportAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        CircuitBreakerMetricsAutoConfiguration.class,
        RetryAutoConfiguration.class,
        RetryMetricsAutoConfiguration.class,
        TimeLimiterAutoConfiguration.class,
        TimeLimiterMetricsAutoConfiguration.class
})
public class FraudScreeningTestApplication {
}
