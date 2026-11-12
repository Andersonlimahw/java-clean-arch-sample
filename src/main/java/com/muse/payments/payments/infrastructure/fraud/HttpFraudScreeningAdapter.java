package com.muse.payments.payments.infrastructure.fraud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.payments.application.FraudScreeningOutcome;
import com.muse.payments.payments.application.FraudScreeningPort;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP adapter for the fraud provider.
 *
 * <p>Outer to inner: Retry(CircuitBreaker(Bulkhead(TimeLimiter(submit to a virtual thread)))).
 * Each retry is one circuit-breaker call, so {@code minimum-number-of-calls} must stay
 * at least the retry max attempts or one logical screening can open the circuit.
 * The JDK client read timeout is longer than the time limiter. {@code executor.submit}
 * returns a {@link java.util.concurrent.Future} that {@code cancel(true)} interrupts,
 * so the worker returns at the time limiter instead of waiting for the read timeout.
 * An explicit decline is a normal return, so the time limiter does not record it as a failure.
 * Other 4xx responses are also normal returns: they do not open the circuit. Watch
 * {@code payments.fraud.screening.unexpected_client_errors} for those.
 */
public class HttpFraudScreeningAdapter implements FraudScreeningPort {

    static final String INSTANCE_NAME = "fraudScreening";
    static final String SCREENING_PATH = "/v1/screenings";
    static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String UNEXPECTED_CLIENT_METRIC = "payments.fraud.screening.unexpected_client_errors";

    static final String FALLBACK_EXHAUSTED =
            "Fraud screening unavailable after retrying transient failures. Payment is under review and is not approved.";
    static final String FALLBACK_CIRCUIT_OPEN =
            "Fraud screening circuit is open. Call was short-circuited without contacting the provider. Payment is under review and is not approved.";
    static final String FALLBACK_UNEXPECTED =
            "Fraud screening did not return a usable decision. Payment is under review and is not approved.";

    private static final Logger log = LoggerFactory.getLogger(HttpFraudScreeningAdapter.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;
    private final Counter unexpectedClientErrors;
    private final AtomicInteger inFlight = new AtomicInteger();

    public HttpFraudScreeningAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            Retry retry,
            TimeLimiter timeLimiter,
            ExecutorService executor,
            Counter unexpectedClientErrors,
            MeterRegistry meterRegistry
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.bulkhead = Objects.requireNonNull(bulkhead);
        this.retry = Objects.requireNonNull(retry);
        this.timeLimiter = Objects.requireNonNull(timeLimiter);
        this.executor = Objects.requireNonNull(executor);
        this.unexpectedClientErrors = Objects.requireNonNull(unexpectedClientErrors);
        Gauge.builder("payments.fraud.screening.in.flight", inFlight, AtomicInteger::get)
                .description("Fraud screening HTTP calls currently running")
                .register(meterRegistry);
    }

    @Override
    public FraudScreeningResult screen(FraudScreeningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Callable<FraudScreeningResult> timed = timeLimiter.decorateFutureSupplier(
                () -> executor.submit(() -> exchange(request)));
        Callable<FraudScreeningResult> limited = Bulkhead.decorateCallable(bulkhead, timed);
        Callable<FraudScreeningResult> guarded = CircuitBreaker.decorateCallable(circuitBreaker, limited);
        Callable<FraudScreeningResult> retried = Retry.decorateCallable(retry, guarded);
        try {
            return retried.call();
        } catch (BulkheadFullException ex) {
            log.warn("Fraud screening bulkhead is full for payment {}", request.paymentId());
            return FraudScreeningResult.underReview(FALLBACK_EXHAUSTED);
        } catch (CallNotPermittedException ex) {
            log.warn("Fraud screening short-circuited for payment {}", request.paymentId());
            return FraudScreeningResult.underReview(FALLBACK_CIRCUIT_OPEN);
        } catch (FraudTransientException | ResourceAccessException | TimeoutException ex) {
            log.warn("Fraud screening fallback for payment {} after {}: {}",
                    request.paymentId(), ex.getClass().getSimpleName(), ex.getMessage());
            return FraudScreeningResult.underReview(FALLBACK_EXHAUSTED);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Fraud screening interrupted for payment {}", request.paymentId());
            return FraudScreeningResult.underReview(FALLBACK_EXHAUSTED);
        } catch (Exception ex) {
            log.error("Fraud screening failed unexpectedly for payment {}", request.paymentId(), ex);
            return FraudScreeningResult.underReview(FALLBACK_UNEXPECTED);
        }
    }

    private FraudScreeningResult exchange(FraudScreeningRequest request) {
        inFlight.incrementAndGet();
        try {
            return restClient.post()
                    .uri(SCREENING_PATH)
                    .header(HEADER_IDEMPOTENCY_KEY, request.providerIdempotencyKey().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new ProviderRequest(
                            request.paymentId(),
                            request.userId(),
                            request.money().amount(),
                            request.money().currency()))
                    .exchange((httpRequest, response) -> interpret(request, response.getStatusCode().value(), read(response.getBody())));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private FraudScreeningResult interpret(FraudScreeningRequest request, int status, String body) {
        if (status == 408 || status == 429 || status >= 500) {
            throw new FraudTransientException("Fraud provider failed with HTTP " + status);
        }
        FraudScreeningResult decision = decisionFromBody(body);
        if (status >= 400) {
            if (status == 422 && decision != null && decision.outcome() == FraudScreeningOutcome.DECLINED) {
                return decision;
            }
            unexpectedClientErrors.increment();
            log.error("Fraud provider returned HTTP {} for payment {} without an explicit decline", status, request.paymentId());
            return FraudScreeningResult.underReview(FALLBACK_UNEXPECTED);
        }
        if (decision == null) {
            log.error("Fraud provider returned HTTP {} for payment {} with no usable decision", status, request.paymentId());
            return FraudScreeningResult.underReview(FALLBACK_UNEXPECTED);
        }
        return decision;
    }

    private FraudScreeningResult decisionFromBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            ProviderResponse parsed = objectMapper.readValue(body, ProviderResponse.class);
            if (parsed.outcome() == null || parsed.outcome().isBlank()) {
                return null;
            }
            return switch (parsed.outcome().trim().toUpperCase(Locale.ROOT)) {
                case "CLEAR" -> FraudScreeningResult.clear();
                case "DECLINED" -> FraudScreeningResult.declined(
                        parsed.reason() == null || parsed.reason().isBlank()
                                ? "Fraud provider declined the payment"
                                : parsed.reason());
                default -> null;
            };
        } catch (IOException ex) {
            log.error("Fraud provider body was not a screening decision", ex);
            return null;
        }
    }

    private static String read(InputStream body) throws IOException {
        if (body == null) {
            return "";
        }
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record ProviderRequest(UUID paymentId, UUID userId, BigDecimal amount, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderResponse(String outcome, String reason) {
    }
}
