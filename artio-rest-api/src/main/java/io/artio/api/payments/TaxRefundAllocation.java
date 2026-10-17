package io.artio.api.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Allocates an amount-only refund against the immutable order-time tax totals. */
final class TaxRefundAllocation {
    private TaxRefundAllocation() { }

    static Map<String, Object> snapshot(BigDecimal orderSubtotal, BigDecimal orderTax, BigDecimal orderShipping,
            BigDecimal orderTotal, String currency, BigDecimal refundAmount, BigDecimal cumulativeRefundBefore,
            Object orderTaxSnapshot) {
        int scale = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Currency does not define a minor unit");
        BigDecimal total = orderTotal.max(BigDecimal.ZERO);
        BigDecimal tax = orderTax.max(BigDecimal.ZERO);
        BigDecimal before = cumulativeRefundBefore.max(BigDecimal.ZERO);
        BigDecimal after = before.add(refundAmount);
        BigDecimal eligibleBefore = before.min(total);
        BigDecimal eligibleAfter = after.min(total);
        BigDecimal cumulativeTaxBefore = proportionalTax(eligibleBefore, total, tax, scale);
        BigDecimal cumulativeTaxAfter = proportionalTax(eligibleAfter, total, tax, scale);
        BigDecimal eligibleRefund = eligibleAfter.subtract(eligibleBefore);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("method", "PROPORTIONAL_ORDER_TAX_SNAPSHOT_V1");
        result.put("currency", currency);
        result.put("refundAmount", refundAmount);
        result.put("orderSubtotal", orderSubtotal);
        result.put("orderTax", orderTax);
        result.put("orderShipping", orderShipping);
        result.put("orderTotal", orderTotal);
        result.put("cumulativeRefundBefore", before);
        result.put("cumulativeRefundAfter", after);
        result.put("eligibleOrderRefundAmount", eligibleRefund);
        result.put("unallocatedRefundAmount", refundAmount.subtract(eligibleRefund).max(BigDecimal.ZERO));
        result.put("cumulativeTaxAllocatedBefore", cumulativeTaxBefore);
        result.put("cumulativeTaxAllocatedAfter", cumulativeTaxAfter);
        result.put("allocatedTax", cumulativeTaxAfter.subtract(cumulativeTaxBefore));
        result.put("orderTaxSnapshot", orderTaxSnapshot);
        return Map.copyOf(result);
    }

    private static BigDecimal proportionalTax(BigDecimal eligibleRefund, BigDecimal orderTotal, BigDecimal orderTax, int scale) {
        if (orderTotal.signum() <= 0 || eligibleRefund.signum() <= 0 || orderTax.signum() <= 0)
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        if (eligibleRefund.compareTo(orderTotal) >= 0) return orderTax.setScale(scale, RoundingMode.HALF_EVEN);
        return orderTax.multiply(eligibleRefund).divide(orderTotal, scale, RoundingMode.HALF_EVEN);
    }
}
