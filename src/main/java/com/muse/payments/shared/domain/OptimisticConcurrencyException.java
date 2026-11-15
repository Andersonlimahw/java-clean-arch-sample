package com.muse.payments.shared.domain;

/**
 * Thrown when an event-store append detects a concurrent write at the same expected stream version.
 */
public class OptimisticConcurrencyException extends ConflictException {

    public OptimisticConcurrencyException(String message) {
        super(message);
    }
}
