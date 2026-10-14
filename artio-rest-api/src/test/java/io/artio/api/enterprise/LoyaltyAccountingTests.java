package io.artio.api.enterprise;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LoyaltyAccountingTests {
    @Test
    void accruesWholePointsFromPaidOrderTotal() {
        assertThat(LoyaltyAccounting.pointsForOrder(new BigDecimal("42.99"))).isEqualTo(42);
        assertThat(LoyaltyAccounting.pointsForOrder(new BigDecimal("0.99"))).isZero();
    }

    @Test
    void reversesPointsProportionallyAcrossPartialRefundsWithoutOverReversal() {
        assertThat(LoyaltyAccounting.additionalReversal(1000,new BigDecimal("25.00"),new BigDecimal("100.00"),0)).isEqualTo(250);
        assertThat(LoyaltyAccounting.additionalReversal(1000,new BigDecimal("50.00"),new BigDecimal("100.00"),250)).isEqualTo(250);
        assertThat(LoyaltyAccounting.additionalReversal(1000,new BigDecimal("150.00"),new BigDecimal("100.00"),900)).isEqualTo(100);
        assertThat(LoyaltyAccounting.additionalReversal(1000,new BigDecimal("100.00"),new BigDecimal("100.00"),1000)).isZero();
    }
}
