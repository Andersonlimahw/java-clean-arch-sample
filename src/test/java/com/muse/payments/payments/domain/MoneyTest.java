package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("Money creates with normalized 2 decimal scale and valid uppercase currency")
    void shouldCreateValidMoney() {
        Money money = Money.of(new BigDecimal("29.9"), "brl");
        assertThat(money.amount()).isEqualByComparingTo("29.90");
        assertThat(money.currency()).isEqualTo("BRL");
    }

    @Test
    @DisplayName("Money rejects zero, negative or null amount")
    void shouldRejectInvalidAmount() {
        assertThatThrownBy(() -> Money.of(null, "USD"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount must not be null");

        assertThatThrownBy(() -> Money.of(BigDecimal.ZERO, "USD"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount must be greater than zero");

        assertThatThrownBy(() -> Money.of(new BigDecimal("-5.00"), "USD"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount must be greater than zero");
    }

    @Test
    @DisplayName("Money rejects invalid ISO 4217 currency")
    void shouldRejectInvalidCurrency() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.00"), "XYZ123"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid ISO 4217 currency code");

        assertThatThrownBy(() -> Money.of(new BigDecimal("10.00"), ""))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Currency must not be blank");
    }
}
