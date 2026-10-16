package io.artio.pricing;

import io.artio.kernel.Percentage;
import java.time.Instant;

public record TaxRuleDetailsChanged(TaxRuleId ruleId, String jurisdiction, TaxCategory category,
                                   Percentage rate, Instant validFrom, Instant validUntil) implements TaxRuleEvent { }
