package com.muse.payments.shared.domain;

public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
