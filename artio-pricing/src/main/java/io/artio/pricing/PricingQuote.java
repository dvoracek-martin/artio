package io.artio.pricing;
import io.artio.kernel.Money;
import java.math.BigDecimal;
import java.time.Instant;
/** Complete, auditable breakdown for a single sellable and requested quantity. */
public record PricingQuote(Money unitListPrice, Money lineListPrice, Money discounts, Money taxableAmount, Money tax, Money total,
                           String jurisdiction, BigDecimal taxRatePercent, Instant taxRuleValidFrom,
                           Instant taxRuleValidUntil, Instant pricedAt) implements java.io.Serializable {}
