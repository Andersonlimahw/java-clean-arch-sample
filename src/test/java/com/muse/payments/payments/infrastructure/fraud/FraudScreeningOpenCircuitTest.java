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
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.fraudScreening.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.fraudScreening.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.fraudScreening.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.fraudScreening.wait-duration-in-open-state=60s",
        "resilience4j.retry.instances.fraudScreening.max-attempts=1"
})
class FraudScreeningOpenCircuitTest {

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

    @BeforeEach
    void resetProvider() {
        SERVER.resetAll();
        circuitBreakerRegistry.circuitBreaker(properties.resilienceInstance()).reset();
    }

    @Test
    @DisplayName("After the failure threshold the circuit opens, later calls do not reach WireMock, and they are not retried")
    void openCircuitShortCircuitsWithoutCallingTheProvider() {
        SERVER.stubFor(post(urlEqualTo(HttpFraudScreeningAdapter.SCREENING_PATH))
                .willReturn(aResponse().withStatus(503)));

        FraudScreeningRequest first = request();
        FraudScreeningRequest second = request();
        port.screen(first);
        port.screen(second);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(properties.resilienceInstance());
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        verifyRequests(first, 1);
        verifyRequests(second, 1);

        SERVER.resetRequests();
        double retriedFailures = count("failed_with_retry");
        double notRetried = count("failed_without_retry");

        FraudScreeningRequest blocked = request();
        FraudScreeningResult held = port.screen(blocked);

        assertThat(held.outcome()).isEqualTo(FraudScreeningOutcome.UNDER_REVIEW);
        assertThat(held.reason()).isEqualTo(HttpFraudScreeningAdapter.FALLBACK_CIRCUIT_OPEN);
        verifyRequests(blocked, 0);
        assertThat(count("failed_without_retry")).isEqualTo(notRetried + 1);
        assertThat(count("failed_with_retry")).isEqualTo(retriedFailures);
        assertThat(count("resilience4j.circuitbreaker.not.permitted.calls", "not_permitted")).isEqualTo(1);
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

    private double count(String kind) {
        return count("resilience4j.retry.calls", kind);
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
