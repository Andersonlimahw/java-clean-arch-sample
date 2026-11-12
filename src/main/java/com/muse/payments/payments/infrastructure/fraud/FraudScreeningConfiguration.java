package com.muse.payments.payments.infrastructure.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.FraudScreeningPort;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class FraudScreeningConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.fraud-screening.mode", havingValue = "http", matchIfMissing = true)
    ExecutorService fraudScreeningExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("fraud-screening-", 0).factory());
    }

    @Bean
    @ConditionalOnProperty(name = "app.fraud-screening.mode", havingValue = "http", matchIfMissing = true)
    FraudScreeningPort fraudScreeningPort(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            FraudScreeningProperties properties,
            ExecutorService fraudScreeningExecutor,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            MeterRegistry meterRegistry,
            Environment environment,
            @Value("${app.idempotency.processing-ttl:2m}") Duration processingTtl
    ) {
        boolean prod = Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equals);
        if (prod && !"https".equalsIgnoreCase(properties.baseUrl().getScheme())) {
            throw new IllegalStateException(
                    "FRAUD_SCREENING_BASE_URL must use https when the prod profile is active");
        }
        String name = HttpFraudScreeningAdapter.INSTANCE_NAME;
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.find(name).orElseThrow(() -> missing("circuit breaker"));
        Bulkhead bulkhead = bulkheadRegistry.find(name).orElseThrow(() -> missing("bulkhead"));
        Retry retry = retryRegistry.find(name).orElseThrow(() -> missing("retry"));
        TimeLimiter timeLimiter = timeLimiterRegistry.find(name).orElseThrow(() -> missing("time limiter"));
        Duration timeout = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
        int maxAttempts = retry.getRetryConfig().getMaxAttempts();
        int minimumCalls = circuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls();
        if (minimumCalls < maxAttempts) {
            throw new IllegalStateException(
                    "fraudScreening minimum-number-of-calls (" + minimumCalls
                            + ") must be at least max-attempts (" + maxAttempts
                            + ") because each retry is one circuit-breaker call");
        }
        if (properties.connectTimeout().compareTo(timeout) > 0) {
            throw new IllegalStateException(
                    "app.fraud-screening.connect-timeout must not exceed the time limiter timeout " + timeout);
        }
        if (properties.readTimeout().compareTo(timeout) <= 0) {
            throw new IllegalStateException(
                    "app.fraud-screening.read-timeout (" + properties.readTimeout()
                            + ") must be greater than the time limiter timeout (" + timeout
                            + ") so a cancelled attempt is released by interrupt");
        }
        Duration budget = ScreeningBudget.of(
                properties.readTimeout(),
                maxAttempts,
                wait(environment),
                flag(environment, "enable-exponential-backoff"),
                number(environment, "exponential-backoff-multiplier", 2d),
                flag(environment, "enable-randomized-wait"),
                number(environment, "randomized-wait-factor", 0.5d)
        );
        if (processingTtl.compareTo(budget) <= 0) {
            throw new IllegalStateException(
                    "app.idempotency.processing-ttl (" + processingTtl
                            + ") must be greater than the fraud screening budget (" + budget
                            + "): read timeout times attempts, plus the maximum backoff");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        Counter unexpected = Counter.builder(HttpFraudScreeningAdapter.UNEXPECTED_CLIENT_METRIC)
                .description("Fraud provider responses that were not an explicit decline and not a transient error")
                .register(meterRegistry);
        return new HttpFraudScreeningAdapter(
                restClient,
                objectMapper,
                circuitBreaker,
                bulkhead,
                retry,
                timeLimiter,
                fraudScreeningExecutor,
                unexpected,
                meterRegistry);
    }

    private static Duration wait(Environment environment) {
        String raw = environment.getProperty(
                "resilience4j.retry.instances." + HttpFraudScreeningAdapter.INSTANCE_NAME + ".wait-duration",
                "500ms");
        return DurationStyle.detectAndParse(raw);
    }

    private static boolean flag(Environment environment, String name) {
        return environment.getProperty(
                "resilience4j.retry.instances." + HttpFraudScreeningAdapter.INSTANCE_NAME + "." + name,
                Boolean.class,
                false);
    }

    private static double number(Environment environment, String name, double fallback) {
        return environment.getProperty(
                "resilience4j.retry.instances." + HttpFraudScreeningAdapter.INSTANCE_NAME + "." + name,
                Double.class,
                fallback);
    }

    private static IllegalStateException missing(String kind) {
        return new IllegalStateException(
                "Missing resilience4j " + kind + " instance '" + HttpFraudScreeningAdapter.INSTANCE_NAME + "'");
    }
}
