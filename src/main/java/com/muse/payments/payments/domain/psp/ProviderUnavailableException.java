package com.muse.payments.payments.domain.psp;

/** The PSP could not be reached or returned an unusable response. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
