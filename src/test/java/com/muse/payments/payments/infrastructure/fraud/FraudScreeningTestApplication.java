package com.muse.payments.payments.infrastructure.fraud;

import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterMetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * Narrow context for the HTTP adapter. The profile keeps the full application from loading it.
 */
@Configuration
@Profile("fraud-adapter-test")
@EnableConfigurationProperties(FraudScreeningProperties.class)
@Import(FraudScreeningConfiguration.class)
@ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        RestClientAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        SimpleMetricsExportAutoConfiguration.class,
        BulkheadAutoConfiguration.class,
        BulkheadMetricsAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        CircuitBreakerMetricsAutoConfiguration.class,
        RetryAutoConfiguration.class,
        RetryMetricsAutoConfiguration.class,
        TimeLimiterAutoConfiguration.class,
        TimeLimiterMetricsAutoConfiguration.class
})
public class FraudScreeningTestApplication {
}
