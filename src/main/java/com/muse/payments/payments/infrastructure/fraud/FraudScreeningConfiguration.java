package com.muse.payments.payments.infrastructure.fraud;

import com.muse.payments.payments.application.FraudScreeningPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class FraudScreeningConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService fraudScreeningExecutor(FraudScreeningProperties properties) {
        return Executors.newFixedThreadPool(
                properties.httpPoolSize(),
                Thread.ofPlatform().name("fraud-screening-", 0).daemon(true).factory());
    }

    @Bean
    RestClient fraudScreeningRestClient(RestClient.Builder builder, FraudScreeningProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // HTTP/1.1 matches a single JSON POST. The JDK client otherwise upgrades to HTTP/2,
                // which some gateways (and WireMock) reset.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    FraudScreeningPort fraudScreeningPort(
            RestClient fraudScreeningRestClient,
            ExecutorService fraudScreeningExecutor,
            FraudScreeningProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry
    ) {
        String instance = properties.resilienceInstance();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.find(instance)
                .orElseThrow(() -> missing(instance, "circuit breaker"));
        Retry retry = retryRegistry.find(instance)
                .orElseThrow(() -> missing(instance, "retry"));
        TimeLimiter timeLimiter = timeLimiterRegistry.find(instance)
                .orElseThrow(() -> missing(instance, "time limiter"));
        Duration timeout = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
        if (properties.readTimeout().compareTo(timeout) <= 0) {
            throw new IllegalStateException(
                    "app.fraud-screening.read-timeout (" + properties.readTimeout()
                            + ") must be greater than the Resilience4j time limiter timeout ("
                            + timeout + ") for instance '" + instance
                            + "' so a slow provider is cut by the time limiter");
        }
        int maxAttempts = retry.getRetryConfig().getMaxAttempts();
        if (properties.httpPoolSize() < maxAttempts) {
            throw new IllegalStateException(
                    "app.fraud-screening.http-pool-size (" + properties.httpPoolSize()
                            + ") must be at least the retry max attempts (" + maxAttempts
                            + ") so a timed-out attempt cannot block the next one");
        }
        return new HttpFraudScreeningAdapter(
                fraudScreeningRestClient, circuitBreaker, retry, timeLimiter, fraudScreeningExecutor);
    }

    private static IllegalStateException missing(String instance, String kind) {
        return new IllegalStateException(
                "Missing resilience4j " + kind + " instance '" + instance + "'. Configure it under resilience4j.");
    }
}
