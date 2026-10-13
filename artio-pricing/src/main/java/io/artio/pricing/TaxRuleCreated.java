package io.artio.pricing;
import io.artio.kernel.Percentage;
public record TaxRuleCreated(TaxRuleId ruleId, String jurisdiction, Percentage rate) implements TaxRuleEvent {}
