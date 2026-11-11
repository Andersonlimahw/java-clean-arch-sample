package com.muse.payments.shared.domain;

public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super(message);
    }
}
