package com.muse.payments.payments.infrastructure.fraud;

/**
 * A provider failure that may succeed on another attempt: HTTP 5xx, I/O, or an empty decision.
 * Timeouts are thrown separately as {@link java.util.concurrent.TimeoutException}.
 */
public class FraudTransientException extends RuntimeException {

    public FraudTransientException(String message) {
        super(message);
    }

    public FraudTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
