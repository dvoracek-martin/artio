package io.artio.pricing;
public record DiscountRuleActiveChanged(DiscountRuleId ruleId, boolean active) implements DiscountRuleEvent {}
