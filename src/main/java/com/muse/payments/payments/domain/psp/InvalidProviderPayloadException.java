package com.muse.payments.payments.domain.psp;

/** The callback body is not a payload the provider could have sent. */
public class InvalidProviderPayloadException extends RuntimeException {

    public InvalidProviderPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
