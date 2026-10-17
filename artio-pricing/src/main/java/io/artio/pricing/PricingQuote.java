package io.artio.pricing;
import io.artio.kernel.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Complete, auditable breakdown for a single sellable and requested quantity. */
public record PricingQuote(Money unitListPrice, Money lineListPrice, Money discounts, Money taxableAmount, Money tax, Money total,
                           String jurisdiction, TaxCategory taxCategory, BigDecimal taxRatePercent, Instant taxRuleValidFrom,
                           Instant taxRuleValidUntil, Instant pricedAt, boolean pricesIncludeTax,
                           List<UUID> appliedDiscountIds, List<String> appliedDiscountCodes) implements java.io.Serializable {
    public PricingQuote { appliedDiscountIds = List.copyOf(appliedDiscountIds); appliedDiscountCodes = List.copyOf(appliedDiscountCodes); }
}
