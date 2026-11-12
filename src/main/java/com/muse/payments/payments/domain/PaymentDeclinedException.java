package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.DomainException;

/**
 * The fraud provider returned an explicit decline. The provider reason stays in the log, not here.
 */
public class PaymentDeclinedException extends DomainException {

    public static final String PUBLIC_MESSAGE = "Payment was declined by fraud screening.";

    public PaymentDeclinedException() {
        super(PUBLIC_MESSAGE);
    }
}
