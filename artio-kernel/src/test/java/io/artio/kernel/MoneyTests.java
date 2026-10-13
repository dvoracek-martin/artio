package io.artio.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTests {
    @Test
    void preservesAmountAndCurrency() {
        var money = new Money(new BigDecimal("12.50"), Currency.getInstance("EUR"));

        assertThat(money.amount()).isEqualByComparingTo("12.50");
        assertThat(money.currency()).isEqualTo(Currency.getInstance("EUR"));
    }

    @Test
    void rejectsNegativeAmountsAndMissingValues() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"), Currency.getInstance("EUR")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Amount must be positive");
        assertThatThrownBy(() -> new Money(null, Currency.getInstance("EUR")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }
}