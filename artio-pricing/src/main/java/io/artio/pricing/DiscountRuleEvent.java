package io.artio.pricing;
import io.artio.kernel.AggregateEvent;
public sealed interface DiscountRuleEvent extends AggregateEvent permits DiscountRuleCreated, DiscountRuleActiveChanged { DiscountRuleId ruleId(); }
