package com.muse.payments.payments.infrastructure.fraud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.muse.payments.payments.application.FraudScreeningPort;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * HTTP adapter for the fraud provider. Resilience4j stays in this class.
 *
 * <p>Outer to inner: Retry(CircuitBreaker(TimeLimiter(HTTP))). TimeLimiter is innermost so each
 * attempt has its own timeout and that timeout is the failure the circuit breaker records.
 * Retry is outermost so an open circuit fails with {@link CallNotPermittedException} before
 * another HTTP call. That exception is not in the retry list, so an open circuit is not retried.
 * HTTP 4xx is a {@link FraudBusinessException}, which retry and the circuit breaker both ignore.
 *
 * <p>{@code Decorators.ofCompletionStage} applies this same order, but in Resilience4j 2.4 its
 * time limiter does not honor {@code cancelRunningFuture}. {@code decorateFutureSupplier} does,
 * so a timed-out attempt is cancelled instead of running until the HTTP read timeout.
 */
public class HttpFraudScreeningAdapter implements FraudScreeningPort {

    static final String SCREENING_PATH = "/v1/screenings";

    /**
     * Retries of transient failures were exhausted. The payment is not approved.
     */
    static final String FALLBACK_EXHAUSTED =
            "Fraud screening unavailable after retrying transient failures. Payment is under review and is not approved.";

    /**
     * The circuit is open, so the provider was not called. The payment is not approved.
     */
    static final String FALLBACK_CIRCUIT_OPEN =
            "Fraud screening circuit is open. Call was short-circuited without contacting the provider. Payment is under review and is not approved.";

    private static final Logger log = LoggerFactory.getLogger(HttpFraudScreeningAdapter.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ExecutorService httpExecutor;

    public HttpFraudScreeningAdapter(
            RestClient restClient,
            CircuitBreaker circuitBreaker,
            Retry retry,
            TimeLimiter timeLimiter,
            ExecutorService httpExecutor
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.retry = Objects.requireNonNull(retry);
        this.timeLimiter = Objects.requireNonNull(timeLimiter);
        this.httpExecutor = Objects.requireNonNull(httpExecutor);
    }

    @Override
    public FraudScreeningResult screen(FraudScreeningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Callable<FraudScreeningResult> timed = timeLimiter.decorateFutureSupplier(
                () -> CompletableFuture.supplyAsync(() -> invoke(request), httpExecutor));
        Callable<FraudScreeningResult> guarded = CircuitBreaker.decorateCallable(circuitBreaker, timed);
        Callable<FraudScreeningResult> retried = Retry.decorateCallable(retry, guarded);
        try {
            return retried.call();
        } catch (FraudBusinessException ex) {
            return FraudScreeningResult.declined(ex.getMessage());
        } catch (CallNotPermittedException ex) {
            log.warn("Fraud screening short-circuited for user {}", request.userId());
            return FraudScreeningResult.underReview(FALLBACK_CIRCUIT_OPEN);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("Fraud screening fallback for user {} after {}: {}",
                    request.userId(), cause.getClass().getSimpleName(), cause.getMessage());
            return FraudScreeningResult.underReview(FALLBACK_EXHAUSTED);
        }
    }

    private FraudScreeningResult invoke(FraudScreeningRequest request) {
        try {
            return exchange(request);
        } catch (FraudBusinessException | FraudTransientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new FraudTransientException("Fraud provider call failed", ex);
        } finally {
            // Cancellation interrupts the worker. Clear the flag so the pool can reuse the thread.
            Thread.interrupted();
        }
    }

    private FraudScreeningResult exchange(FraudScreeningRequest request) {
        ProviderResponse body = restClient.post()
                .uri(SCREENING_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new ProviderRequest(request.userId(), request.amount(), request.currency()))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (httpRequest, response) -> {
                    throw new FraudBusinessException(
                            "Fraud provider rejected the screening with HTTP " + response.getStatusCode().value());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (httpRequest, response) -> {
                    throw new FraudTransientException(
                            "Fraud provider failed with HTTP " + response.getStatusCode().value());
                })
                .body(ProviderResponse.class);
        if (body == null || body.outcome() == null || body.outcome().isBlank()) {
            throw new FraudTransientException("Fraud provider returned an empty decision");
        }
        return switch (body.outcome()) {
            case "CLEAR" -> FraudScreeningResult.clear();
            case "DECLINED" -> FraudScreeningResult.declined(
                    body.reason() == null || body.reason().isBlank()
                            ? "Fraud provider declined the payment"
                            : body.reason());
            default -> throw new FraudTransientException(
                    "Fraud provider returned an unknown outcome: " + body.outcome());
        };
    }

    public record ProviderRequest(UUID userId, BigDecimal amount, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderResponse(String outcome, String reason) {
    }
}
