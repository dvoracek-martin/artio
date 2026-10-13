package io.artio.pricing;
import io.artio.kernel.Money;
/** Complete, auditable breakdown for a single sellable and requested quantity. */
public record PricingQuote(Money unitListPrice, Money lineListPrice, Money discounts, Money taxableAmount, Money tax, Money total) implements java.io.Serializable {}
