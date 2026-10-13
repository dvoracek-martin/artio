package io.artio.pricing;

import io.artio.kernel.Money; import io.artio.kernel.Percentage; import io.artio.kernel.Quantity;
import java.math.BigDecimal; import java.math.RoundingMode; import java.time.Instant; import java.util.Comparator; import java.util.List; import java.util.Objects; import java.util.UUID;

/** Pure pricing rules evaluation: ordered discounts first, jurisdiction tax on the discounted subtotal. */
public final class PricingEngine {
    public PricingQuote calculate(UUID sellableId, Money unitPrice, Quantity quantity, String market, String jurisdiction,
                                  Instant at, List<DiscountRule> discounts, TaxRule taxRule) {
        return calculate(sellableId, unitPrice, quantity, market, jurisdiction, at, discounts, taxRule, false);
    }

    public PricingQuote calculate(UUID sellableId, Money unitPrice, Quantity quantity, String market, String jurisdiction,
                                  Instant at, List<DiscountRule> discounts, TaxRule taxRule, boolean pricesIncludeTax) {
        Objects.requireNonNull(sellableId); Objects.requireNonNull(unitPrice); Objects.requireNonNull(quantity);
        Objects.requireNonNull(at); Objects.requireNonNull(discounts);
        int scale = unitPrice.currency().getDefaultFractionDigits(); if (scale < 0) scale = 2;
        BigDecimal factor = quantity.value(); BigDecimal initial = unitPrice.amount();
        BigDecimal lineList = initial.multiply(factor).setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal discounted = lineList;
        var applicable = discounts.stream().filter(rule -> rule.applies(sellableId, market, at))
                .sorted(Comparator.comparingInt(DiscountRule::getPriority).thenComparing(rule -> rule.getId().value())).toList();
        for (DiscountRule rule : applicable) discounted = discounted.multiply(BigDecimal.ONE.subtract(rule.getPercentage().value().movePointLeft(2)));
        BigDecimal lineDiscount = lineList.subtract(discounted).setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal discountedAmount = lineList.subtract(lineDiscount).max(BigDecimal.ZERO);
        BigDecimal taxRate = taxRule != null && taxRule.applies(jurisdiction, at) ? taxRule.getRate().value() : BigDecimal.ZERO;
        BigDecimal taxable;
        BigDecimal tax;
        BigDecimal total;
        if (pricesIncludeTax && taxRate.signum() > 0) {
            taxable = discountedAmount.divide(BigDecimal.ONE.add(taxRate.movePointLeft(2)), scale, RoundingMode.HALF_EVEN);
            total = discountedAmount.setScale(scale, RoundingMode.HALF_EVEN);
            tax = total.subtract(taxable).setScale(scale, RoundingMode.HALF_EVEN);
        } else {
            taxable = discountedAmount;
            tax = taxable.multiply(taxRate.movePointLeft(2)).setScale(scale, RoundingMode.HALF_EVEN);
            total = taxable.add(tax);
        }
        return new PricingQuote(unitPrice, money(lineList, unitPrice, scale), money(lineDiscount, unitPrice, scale),
                money(taxable, unitPrice, scale), money(tax, unitPrice, scale), money(total, unitPrice, scale),
                jurisdiction, taxRule != null && taxRule.applies(jurisdiction, at) ? taxRule.getCategory() : TaxCategory.STANDARD,
                taxRate, taxRule != null && taxRule.applies(jurisdiction, at) ? taxRule.getValidFrom() : null,
                taxRule != null && taxRule.applies(jurisdiction, at) ? taxRule.getValidUntil() : null, at, pricesIncludeTax);
    }
    private Money money(BigDecimal value, Money basis, int scale) { return new Money(value.setScale(scale, RoundingMode.HALF_EVEN), basis.currency()); }
}
