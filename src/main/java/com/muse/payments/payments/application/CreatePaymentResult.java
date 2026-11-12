package com.muse.payments.payments.application;

public sealed interface CreatePaymentResult {

    record Created(PaymentResponse body) implements CreatePaymentResult {
    }

    record Declined() implements CreatePaymentResult {
    }

    record Replayed(CreatePaymentResult outcome) implements CreatePaymentResult {
    }
}
