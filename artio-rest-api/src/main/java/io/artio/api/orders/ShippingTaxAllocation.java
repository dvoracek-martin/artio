package io.artio.api.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Splits a tax-inclusive shipping charge over the taxable product tax classes in an order. */
final class ShippingTaxAllocation {
    private ShippingTaxAllocation() { }

    static Result allocate(BigDecimal grossAmount, Currency currency, List<TaxableBasis> taxableBases) {
        if (grossAmount == null || grossAmount.signum() < 0) throw new IllegalArgumentException("Shipping amount cannot be negative");
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Currency does not define a minor unit");
        BigDecimal gross = grossAmount.setScale(scale, RoundingMode.HALF_EVEN);

        Map<TaxClass, BigDecimal> bases = new TreeMap<>();
        for (TaxableBasis basis : taxableBases) {
            if (basis.ratePercent() != null && basis.ratePercent().signum() < 0)
                throw new IllegalArgumentException("Tax rate cannot be negative");
            if (basis.taxableAmount() == null || basis.taxableAmount().signum() <= 0
                    || basis.ratePercent() == null || basis.ratePercent().signum() <= 0) continue;
            if (basis.category() == null || basis.category().isBlank()) throw new IllegalArgumentException("Tax category is required");
            bases.merge(new TaxClass(basis.category(), basis.ratePercent().stripTrailingZeros()), basis.taxableAmount(), BigDecimal::add);
        }
        BigDecimal totalBasis = bases.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (gross.signum() == 0 || totalBasis.signum() == 0)
            return new Result(gross, gross, BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN), List.of());

        List<Allocation> allocations = new ArrayList<>();
        BigDecimal cumulativeBasis = BigDecimal.ZERO;
        BigDecimal previousGrossBoundary = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal netTotal = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal taxTotal = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        for (var entry : bases.entrySet()) {
            cumulativeBasis = cumulativeBasis.add(entry.getValue());
            BigDecimal grossBoundary = gross.multiply(cumulativeBasis).divide(totalBasis, scale, RoundingMode.HALF_EVEN);
            BigDecimal classGross = grossBoundary.subtract(previousGrossBoundary);
            previousGrossBoundary = grossBoundary;
            BigDecimal classNet = classGross.divide(BigDecimal.ONE.add(entry.getKey().ratePercent().movePointLeft(2)), scale, RoundingMode.HALF_EVEN);
            BigDecimal classTax = classGross.subtract(classNet);
            allocations.add(new Allocation(entry.getKey().category(), entry.getKey().ratePercent(), entry.getValue(),
                    classNet, classTax, classGross));
            netTotal = netTotal.add(classNet);
            taxTotal = taxTotal.add(classTax);
        }
        if (netTotal.add(taxTotal).compareTo(gross) != 0) throw new IllegalStateException("Shipping tax allocation does not reconcile to the quoted amount");
        return new Result(netTotal, gross, taxTotal, List.copyOf(allocations));
    }

    record TaxableBasis(String category, BigDecimal ratePercent, BigDecimal taxableAmount) { }
    record Allocation(String taxCategory, BigDecimal taxRatePercent, BigDecimal itemTaxableBasis,
            BigDecimal taxableAmount, BigDecimal taxAmount, BigDecimal grossAmount) { }
    record Result(BigDecimal netAmount, BigDecimal grossAmount, BigDecimal taxAmount, List<Allocation> allocations) {
        Result { allocations = List.copyOf(allocations); }
    }

    private record TaxClass(String category, BigDecimal ratePercent) implements Comparable<TaxClass> {
        @Override public int compareTo(TaxClass other) {
            int categoryOrder = category.compareTo(other.category);
            return categoryOrder != 0 ? categoryOrder : ratePercent.compareTo(other.ratePercent);
        }
    }
}
