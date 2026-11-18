package com.muse.payments.payments.domain.psp;

/** The callback is not authentic, is stale, or its signature header is malformed. */
public class InvalidProviderSignatureException extends RuntimeException {

    public InvalidProviderSignatureException(String message) {
        super(message);
    }
}
