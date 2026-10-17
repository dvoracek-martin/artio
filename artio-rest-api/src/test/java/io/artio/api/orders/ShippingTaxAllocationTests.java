package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShippingTaxAllocationTests {
    private static final Currency CHF = Currency.getInstance("CHF");

    @Test
    void allocatesTaxInclusiveShippingByTaxableItemValueAndReconcilesGross() {
        var result = ShippingTaxAllocation.allocate(new BigDecimal("15.00"), CHF, List.of(
                new ShippingTaxAllocation.TaxableBasis("STANDARD", new BigDecimal("8.1"), new BigDecimal("100.00")),
                new ShippingTaxAllocation.TaxableBasis("REDUCED", new BigDecimal("2.6"), new BigDecimal("50.00"))));

        assertThat(result.allocations()).extracting(ShippingTaxAllocation.Allocation::taxCategory)
                .containsExactly("REDUCED", "STANDARD");
        assertThat(result.allocations()).extracting(ShippingTaxAllocation.Allocation::grossAmount)
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("10.00"));
        assertThat(result.allocations()).extracting(ShippingTaxAllocation.Allocation::taxableAmount)
                .containsExactly(new BigDecimal("4.87"), new BigDecimal("9.25"));
        assertThat(result.allocations()).extracting(ShippingTaxAllocation.Allocation::taxAmount)
                .containsExactly(new BigDecimal("0.13"), new BigDecimal("0.75"));
        assertThat(result.netAmount()).isEqualByComparingTo("14.12");
        assertThat(result.taxAmount()).isEqualByComparingTo("0.88");
        assertThat(result.netAmount().add(result.taxAmount())).isEqualByComparingTo("15.00");
    }

    @Test
    void leavesShippingUntaxedWhenThereAreNoTaxedItems() {
        var result = ShippingTaxAllocation.allocate(new BigDecimal("4.50"), CHF, List.of(
                new ShippingTaxAllocation.TaxableBasis("EXEMPT", BigDecimal.ZERO, new BigDecimal("100.00"))));

        assertThat(result.netAmount()).isEqualByComparingTo("4.50");
        assertThat(result.taxAmount()).isEqualByComparingTo("0.00");
        assertThat(result.allocations()).isEmpty();
    }

    @Test
    void retainsEveryMinorUnitWhenProportionalSharesNeedRounding() {
        var result = ShippingTaxAllocation.allocate(new BigDecimal("0.05"), CHF, List.of(
                new ShippingTaxAllocation.TaxableBasis("STANDARD", new BigDecimal("8.1"), new BigDecimal("1")),
                new ShippingTaxAllocation.TaxableBasis("REDUCED", new BigDecimal("2.6"), new BigDecimal("1")),
                new ShippingTaxAllocation.TaxableBasis("ZERO", BigDecimal.ZERO, new BigDecimal("100"))));

        assertThat(result.allocations()).extracting(ShippingTaxAllocation.Allocation::grossAmount)
                .containsExactly(new BigDecimal("0.02"), new BigDecimal("0.03"));
        assertThat(result.allocations().stream().map(ShippingTaxAllocation.Allocation::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.05");
        assertThat(result.netAmount().add(result.taxAmount())).isEqualByComparingTo("0.05");
    }
}
