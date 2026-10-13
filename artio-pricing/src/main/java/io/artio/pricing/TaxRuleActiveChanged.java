package io.artio.pricing;
public record TaxRuleActiveChanged(TaxRuleId ruleId, boolean active) implements TaxRuleEvent {}
