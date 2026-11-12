package com.muse.payments.payments.application;

public sealed interface CreatePaymentResult {

    record Created(PaymentResponse body) implements CreatePaymentResult {
    }

    record Replayed(int status, String body) implements CreatePaymentResult {
    }
}
