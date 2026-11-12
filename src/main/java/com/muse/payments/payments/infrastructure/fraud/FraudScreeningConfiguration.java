package com.muse.payments.payments.infrastructure.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.FraudScreeningPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            MeterRegistry meterRegistry,
            Environment environment
    ) {
        boolean prod = Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equals);
        if (prod && !"https".equalsIgnoreCase(properties.baseUrl().getScheme())) {
            throw new IllegalStateException(
                    "FRAUD_SCREENING_BASE_URL must use https when the prod profile is active");
        }
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.find(HttpFraudScreeningAdapter.INSTANCE_NAME)
                .orElseThrow(() -> missing("circuit breaker"));
        Retry retry = retryRegistry.find(HttpFraudScreeningAdapter.INSTANCE_NAME)
                .orElseThrow(() -> missing("retry"));
        TimeLimiter timeLimiter = timeLimiterRegistry.find(HttpFraudScreeningAdapter.INSTANCE_NAME)
                .orElseThrow(() -> missing("time limiter"));
        Duration timeout = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
        if (properties.connectTimeout().compareTo(timeout) > 0) {
            throw new IllegalStateException(
                    "app.fraud-screening.connect-timeout must not exceed the time limiter timeout " + timeout);
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        Counter unexpected = Counter.builder(HttpFraudScreeningAdapter.UNEXPECTED_CLIENT_METRIC)
                .description("Fraud provider responses that were not an explicit decline and not a transient error")
                .register(meterRegistry);
        return new HttpFraudScreeningAdapter(
                restClient, objectMapper, circuitBreaker, retry, timeLimiter, fraudScreeningExecutor, unexpected);
    }

    private static IllegalStateException missing(String kind) {
        return new IllegalStateException(
                "Missing resilience4j " + kind + " instance '" + HttpFraudScreeningAdapter.INSTANCE_NAME + "'");
    }
}
