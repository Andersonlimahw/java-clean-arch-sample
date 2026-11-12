package com.muse.payments.shared.domain;

public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(message);
    }
}
