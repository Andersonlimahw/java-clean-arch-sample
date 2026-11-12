package com.muse.payments.payments.infrastructure.fraud;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.muse.payments.payments.application.FraudScreeningOutcome;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import com.muse.payments.payments.domain.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@FraudScreeningSpringTest
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.fraudScreening.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.fraudScreening.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.fraudScreening.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.fraudScreening.wait-duration-in-open-state=60s",
        "resilience4j.retry.instances.fraudScreening.max-attempts=1"
})
class FraudScreeningOpenCircuitTest {

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

    @BeforeEach
    void resetProvider() {
        SERVER.resetAll();
        circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME).reset();
    }

    @Test
    @DisplayName("After the failure threshold the circuit opens and the next call does not reach WireMock or retry")
    void openCircuitShortCircuitsWithoutCallingTheProvider() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(503)));
        FraudScreeningRequest first = request();
        FraudScreeningRequest second = request();
        adapter.screen(first);
        adapter.screen(second);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(HttpFraudScreeningAdapter.INSTANCE_NAME);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        verify(first, 1);
        verify(second, 1);

        double notPermitted = ResilienceMeters.count(meterRegistry, "resilience4j.circuitbreaker.not.permitted.calls", "not_permitted");
        double retriedFailures = ResilienceMeters.count(meterRegistry, "resilience4j.retry.calls", "failed_with_retry");
        double notRetried = ResilienceMeters.count(meterRegistry, "resilience4j.retry.calls", "failed_without_retry");
        FraudScreeningRequest blocked = request();

        FraudScreeningResult held = adapter.screen(blocked);

        assertThat(held.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(held.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_CIRCUIT_OPEN);
        verify(blocked, 0);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.retry.calls", "failed_without_retry"))
                .isEqualTo(notRetried + 1);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.retry.calls", "failed_with_retry"))
                .isEqualTo(retriedFailures);
        assertThat(ResilienceMeters.count(meterRegistry, "resilience4j.circuitbreaker.not.permitted.calls", "not_permitted"))
                .isEqualTo(notPermitted + 1);
    }

    private static FraudScreeningRequest request() {
        UUID paymentId = UUID.randomUUID();
        return new FraudScreeningRequest(paymentId, UUID.randomUUID(), Money.of(new BigDecimal("10.00"), "BRL"));
    }

    private static void verify(FraudScreeningRequest request, int count) {
        SERVER.verify(
                exactly(count),
                postRequestedFor(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                        .withRequestBody(containing(request.paymentId().toString())));
    }
}
