package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) {
            throw new ValidationException("Amount must not be null");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new ValidationException("Currency must not be blank");
        }
        String normalizedCurrency = currency.trim().toUpperCase();
        try {
            Currency.getInstance(normalizedCurrency);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid ISO 4217 currency code: " + currency);
        }
        currency = normalizedCurrency;
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }
}
