package io.artio.pricing;
import io.artio.kernel.AggregateEvent;
public sealed interface TaxRuleEvent extends AggregateEvent permits TaxRuleCreated, TaxRuleActiveChanged { TaxRuleId ruleId(); }
