package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TaxRefundAllocationTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cumulativeRoundingAllocatesTheWholeOriginalTaxWithoutDrift() throws Exception {
        var original = mapper.readTree("{\"schemaVersion\":2,\"lines\":[{\"taxRatePercent\":8.1}]}");
        var first = TaxRefundAllocation.snapshot(new BigDecimal("100.00"), new BigDecimal("8.10"), BigDecimal.ZERO,
                new BigDecimal("108.10"), "CHF", new BigDecimal("36.03"), BigDecimal.ZERO, original);
        var second = TaxRefundAllocation.snapshot(new BigDecimal("100.00"), new BigDecimal("8.10"), BigDecimal.ZERO,
                new BigDecimal("108.10"), "CHF", new BigDecimal("36.03"), new BigDecimal("36.03"), original);
        var finalPart = TaxRefundAllocation.snapshot(new BigDecimal("100.00"), new BigDecimal("8.10"), BigDecimal.ZERO,
                new BigDecimal("108.10"), "CHF", new BigDecimal("36.04"), new BigDecimal("72.06"), original);

        assertThat(first.get("allocatedTax")).isEqualTo(new BigDecimal("2.70"));
        assertThat(second.get("allocatedTax")).isEqualTo(new BigDecimal("2.70"));
        assertThat(finalPart.get("allocatedTax")).isEqualTo(new BigDecimal("2.70"));
        assertThat(((BigDecimal) first.get("allocatedTax")).add((BigDecimal) second.get("allocatedTax"))
                .add((BigDecimal) finalPart.get("allocatedTax"))).isEqualByComparingTo("8.10");
    }

    @Test
    void overpaymentRefundIsMarkedUnallocatedAndCannotAllocateTaxTwice() throws Exception {
        var original = mapper.readTree("{}");
        var allocation = TaxRefundAllocation.snapshot(new BigDecimal("100.00"), new BigDecimal("8.10"), BigDecimal.ZERO,
                new BigDecimal("108.10"), "CHF", new BigDecimal("10.00"), new BigDecimal("108.10"), original);

        assertThat(allocation.get("allocatedTax")).isEqualTo(new BigDecimal("0.00"));
        assertThat(allocation.get("eligibleOrderRefundAmount")).isEqualTo(new BigDecimal("0.00"));
        assertThat(allocation.get("unallocatedRefundAmount")).isEqualTo(new BigDecimal("10.00"));
    }
}
