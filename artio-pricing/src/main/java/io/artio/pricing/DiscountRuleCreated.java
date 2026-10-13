package io.artio.pricing;
import io.artio.kernel.Percentage;
public record DiscountRuleCreated(DiscountRuleId ruleId, String code, Percentage percentage) implements DiscountRuleEvent {}
