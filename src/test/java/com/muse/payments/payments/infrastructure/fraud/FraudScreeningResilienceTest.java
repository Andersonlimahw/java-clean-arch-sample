package com.muse.payments.payments.infrastructure.fraud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.muse.fraudscreening.FraudScreeningTestApplication;
import com.muse.payments.payments.application.FraudScreeningOutcome;
import com.muse.payments.payments.application.FraudScreeningPort;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
                "management.endpoint.health.validate-group-membership=false"
        }
)
@ActiveProfiles("test")
class FraudScreeningResilienceTest {

    private static final WireMockServer SERVER = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        SERVER.start();
    }

    @DynamicPropertySource
    static void fraudProvider(DynamicPropertyRegistry registry) {
        registry.add("app.fraud-screening.base-url", SERVER::baseUrl);
    }

    @Autowired
    private FraudScreeningPort port;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private FraudScreeningProperties properties;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private TimeLimiterRegistry timeLimiterRegistry;

    @BeforeEach
    void resetProvider() {
        SERVER.resetAll();
        circuitBreakerRegistry.circuitBreaker(properties.resilienceInstance()).reset();
    }

    @Test
    @DisplayName("The test profile supplies the timeout, the retry limit, and the instance name")
    void testProfileSuppliesLimits() {
        String instance = properties.resilienceInstance();
        assertThat(instance).isEqualTo("fraudScreening");
        assertThat(timeLimiterRegistry.timeLimiter(instance).getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(retryRegistry.retry(instance).getRetryConfig().getMaxAttempts()).isEqualTo(3);
        var predicate = retryRegistry.retry(instance).getRetryConfig().getExceptionPredicate();
        assertThat(predicate.test(new FraudTransientException("down"))).isTrue();
        assertThat(predicate.test(new FraudBusinessException("no"))).isFalse();
        assertThat(predicate.test(new java.util.concurrent.TimeoutException("slow"))).isTrue();
        assertThat(circuitBreakerRegistry.circuitBreaker(instance).getCircuitBreakerConfig().getMinimumNumberOfCalls())
                .isEqualTo(20);
    }

    @Test
    @DisplayName("A clear provider response is returned once and records the fraudScreening meters")
    void happyPathCallsProviderOnce() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(okJson("{\"outcome\":\"CLEAR\"}")));
        double cleared = count("resilience4j.circuitbreaker.calls", "successful");
        double withoutRetry = count("resilience4j.retry.calls", "successful_without_retry");
        double withinTime = count("resilience4j.timelimiter.calls", "successful");
        FraudScreeningRequest request = request();

        FraudScreeningResult result = port.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.CLEAR);
        verifyRequests(request, 1);
        assertThat(count("resilience4j.circuitbreaker.calls", "successful")).isEqualTo(cleared + 1);
        assertThat(count("resilience4j.retry.calls", "successful_without_retry")).isEqualTo(withoutRetry + 1);
        assertThat(count("resilience4j.timelimiter.calls", "successful")).isEqualTo(withinTime + 1);
    }

    @Test
    @Timeout(15)
    @DisplayName("A slow provider trips the time limiter on every attempt, then the explicit under-review fallback")
    void slowProviderTripsTimeLimiterAndFallsBack() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(8_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"outcome\":\"CLEAR\"}")));

        double timeouts = count("resilience4j.timelimiter.calls", "timeout");
        FraudScreeningRequest request = request();
        long started = System.nanoTime();
        FraudScreeningResult result = port.screen(request);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(result.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_EXHAUSTED);
        assertThat(elapsedMs).isLessThan(4_500);
        verifyRequests(request, 3);
        assertThat(count("resilience4j.timelimiter.calls", "timeout")).isEqualTo(timeouts + 3);
        assertThat(circuitBreakerRegistry.circuitBreaker(properties.resilienceInstance()).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HTTP 5xx is retried up to the configured limit and then returns under review, not an approval")
    void serverErrorRetriesUpToTheLimitThenFallsBack() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(503)));
        double retriedFailures = count("resilience4j.retry.calls", "failed_with_retry");
        FraudScreeningRequest request = request();

        FraudScreeningResult result = port.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(result.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_EXHAUSTED);
        verifyRequests(request, 3);
        assertThat(count("resilience4j.retry.calls", "failed_with_retry")).isEqualTo(retriedFailures + 1);
        assertThat(circuitBreakerRegistry.circuitBreaker(properties.resilienceInstance()).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HTTP 4xx is a business rejection: one call, no retry, and the payment is declined")
    void clientErrorIsNotRetried() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(422).withBody("{\"outcome\":\"DECLINED\"}")));
        double retriedFailures = count("resilience4j.retry.calls", "failed_with_retry");
        double notRetried = count("resilience4j.retry.calls", "failed_without_retry");
        FraudScreeningRequest request = request();

        FraudScreeningResult result = port.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.DECLINED);
        assertThat(result.reason()).contains("HTTP 422");
        verifyRequests(request, 1);
        assertThat(count("resilience4j.retry.calls", "failed_without_retry")).isEqualTo(notRetried + 1);
        assertThat(count("resilience4j.retry.calls", "failed_with_retry")).isEqualTo(retriedFailures);
        assertThat(circuitBreakerRegistry.circuitBreaker(properties.resilienceInstance()).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private FraudScreeningRequest request() {
        return new FraudScreeningRequest(UUID.randomUUID(), new BigDecimal("10.00"), "BRL");
    }

    private static void verifyRequests(FraudScreeningRequest request, int count) {
        SERVER.verify(
                exactly(count),
                postRequestedFor(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                        .withRequestBody(containing(request.userId().toString())));
    }

    private double count(String metric, String kind) {
        String instance = properties.resilienceInstance();
        var tagged = meterRegistry.find(metric).tag("name", instance).tag("kind", kind);
        if (tagged.functionCounter() != null) {
            return tagged.functionCounter().count();
        }
        if (tagged.counter() != null) {
            return tagged.counter().count();
        }
        if (tagged.timer() != null) {
            return tagged.timer().count();
        }
        return 0d;
    }
}
