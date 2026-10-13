package io.artio.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantityAndPercentageTests {
    @Test
    void quantityAllowsZeroAndPositiveFractions() {
        assertThat(new Quantity(BigDecimal.ZERO).value()).isEqualByComparingTo("0");
        assertThat(new Quantity(new BigDecimal("1.25")).value()).isEqualByComparingTo("1.25");
    }

    @Test
    void quantityRejectsNegativeAndMissingValues() {
        assertThatThrownBy(() -> new Quantity(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Quantity must not be negative");
        assertThatThrownBy(() -> new Quantity(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void percentageIncludesZeroAndOneHundred() {
        assertThat(new Percentage(BigDecimal.ZERO).value()).isEqualByComparingTo("0");
        assertThat(new Percentage(new BigDecimal("100")).value()).isEqualByComparingTo("100");
    }

    @Test
    void percentageRejectsValuesOutsideItsRangeAndMissingValues() {
        assertThatThrownBy(() -> new Percentage(new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Percentage(new BigDecimal("100.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Percentage(null)).isInstanceOf(NullPointerException.class);
    }
}