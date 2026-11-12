package com.muse.payments.payments.infrastructure.fraud;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.muse.payments.payments.application.FraudScreeningOutcome;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import com.muse.payments.payments.domain.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@FraudScreeningSpringTest
class FraudScreeningResilienceTest {

    @RegisterExtension
    static final WireMockExtension SERVER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void fraudProvider(DynamicPropertyRegistry registry) {
        registry.add("app.fraud-screening.base-url", SERVER::baseUrl);
    }

    @Autowired
    private HttpFraudScreeningAdapter adapter;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @BeforeEach
    void resetAndWarmTheClient() {
        SERVER.resetAll();
        circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME).reset();
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(okJson("{\"outcome\":\"CLEAR\"}")));
        adapter.screen(request());
        SERVER.resetAll();
        circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME).reset();
    }

    @Test
    @DisplayName("The test profile retries only transient failures and does not open on one screening")
    void testProfileInvariants() {
        var retry = retryRegistry.retry(HttpFraudScreeningAdapter.INSTANCE_NAME).getRetryConfig();
        var breaker = circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME).getCircuitBreakerConfig();
        assertThat(retry.getMaxAttempts()).isEqualTo(3);
        assertThat(breaker.getMinimumNumberOfCalls()).isGreaterThanOrEqualTo(retry.getMaxAttempts());
        assertThat(retry.getExceptionPredicate().test(new FraudTransientException("down"))).isTrue();
        assertThat(retry.getExceptionPredicate().test(new org.springframework.web.client.ResourceAccessException("io"))).isTrue();
        assertThat(retry.getExceptionPredicate().test(new java.util.concurrent.TimeoutException("slow"))).isTrue();
        assertThat(retry.getExceptionPredicate().test(
                io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(
                        circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME)))).isFalse();
    }

    @Test
    @DisplayName("A clear provider response is one call after the client is warm")
    void happyPathCallsProviderOnce() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(okJson("{\"outcome\":\"clear\"}")));
        FraudScreeningRequest request = request();

        FraudScreeningResult result = adapter.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.CLEAR);
        verifyRequests(request, 1);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.circuitbreaker.calls", "successful"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 200 DECLINED is one call and is not a time-limiter failure")
    void explicitDeclineIn200IsNotRetried() {
        double limiterFailures = ResilienceMeters.count(meterRegistry, "resilience4j.timelimiter.calls", "failed");
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(okJson("{\"outcome\":\"DECLINED\",\"reason\":\"stolen card\"}")));
        FraudScreeningRequest request = request();

        FraudScreeningResult result = adapter.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.DECLINED);
        assertThat(result.reason()).isEqualTo("stolen card");
        verifyRequests(request, 1);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.timelimiter.calls", "failed")).isEqualTo(limiterFailures);
    }

    @Test
    @DisplayName("HTTP 422 with an explicit decline is not retried")
    void explicitDeclineIn422IsNotRetried() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"outcome\":\"declined\",\"reason\":\"velocity\"}")));
        FraudScreeningRequest request = request();

        FraudScreeningResult result = adapter.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.DECLINED);
        assertThat(result.reason()).isEqualTo("velocity");
        verifyRequests(request, 1);
    }

    @Test
    @DisplayName("An empty body or an unknown outcome is held without a retry")
    void unusableSuccessBodyIsHeldWithoutRetry() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(okJson("{\"outcome\":\"MAYBE\"}")));
        FraudScreeningRequest unknown = request();
        assertThat(adapter.screen(unknown).outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        verifyRequests(unknown, 1);

        SERVER.resetAll();
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(okJson("")));
        FraudScreeningRequest empty = request();
        assertThat(adapter.screen(empty).outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        verifyRequests(empty, 1);
    }

    @Test
    @DisplayName("HTTP 404 is held, counted, and not retried")
    void otherClientErrorsAreHeldWithoutRetry() {
        double before = meterRegistry.counter(HttpFraudScreeningAdapter.UNEXPECTED_CLIENT_METRIC).count();
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(404)));
        FraudScreeningRequest request = request();

        FraudScreeningResult result = adapter.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(result.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_UNEXPECTED);
        verifyRequests(request, 1);
        assertThat(meterRegistry.counter(HttpFraudScreeningAdapter.UNEXPECTED_CLIENT_METRIC).count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("HTTP 429 is retried up to the limit and then held")
    void tooManyRequestsIsRetried() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(429)));
        FraudScreeningRequest request = request();

        FraudScreeningResult result = adapter.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(result.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_EXHAUSTED);
        verifyRequests(request, 3);
    }

    @Test
    @Timeout(15)
    @DisplayName("A slow provider trips the time limiter and the worker is free when the call returns")
    void slowProviderTripsTimeLimiterAndReleasesTheWorker() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(8_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"outcome\":\"CLEAR\"}")));
        double timeouts = ResilienceMeters.count(meterRegistry, "resilience4j.timelimiter.calls", "timeout");
        FraudScreeningRequest request = request();
        long started = System.nanoTime();

        FraudScreeningResult result = adapter.screen(request);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(result.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_EXHAUSTED);
        assertThat(elapsedMs).isLessThan(4_500);
        verifyRequests(request, 3);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.timelimiter.calls", "timeout")).isEqualTo(timeouts + 3);
        await().atMost(Duration.ofSeconds(1)).until(() ->
                meterRegistry.get("payments.fraud.screening.in.flight").gauge().value() == 0d);
        assertThat(circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HTTP 503 is retried up to the limit and then held")
    void serverErrorRetriesUpToTheLimitThenFallsBack() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(503)));
        double retriedFailures = ResilienceMeters.count(meterRegistry, "resilience4j.retry.calls", "failed_with_retry");
        FraudScreeningRequest request = request();

        FraudScreeningResult result = adapter.screen(request);

        assertThat(result.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(result.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_EXHAUSTED);
        verifyRequests(request, 3);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.retry.calls", "failed_with_retry"))
                .isEqualTo(retriedFailures + 1);
    }

    private static FraudScreeningRequest request() {
        UUID paymentId = UUID.randomUUID();
        return new FraudScreeningRequest(
                paymentId,
                UUID.randomUUID(),
                Money.of(new BigDecimal("10.00"), "BRL"),
                paymentId);
    }

    private static void verifyRequests(FraudScreeningRequest request, int count) {
        SERVER.verify(
                exactly(count),
                postRequestedFor(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                        .withHeader(HttpFraudScreeningAdapter.HEADER_IDEMPOTENCY_KEY, equalTo(request.providerIdempotencyKey().toString()))
                        .withRequestBody(containing(request.paymentId().toString())));
    }
}
