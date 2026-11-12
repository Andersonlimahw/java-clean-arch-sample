package com.muse.payments.payments.infrastructure.fraud;

/**
 * The provider answered with a business rejection (HTTP 4xx or an explicit decline).
 * Retry and the circuit breaker ignore this exception.
 */
public class FraudBusinessException extends RuntimeException {

    public FraudBusinessException(String message) {
        super(message);
    }
}
