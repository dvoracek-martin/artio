package io.artio.pricing;

import io.artio.kernel.Aggregate; import io.artio.kernel.Percentage; import java.time.Instant; import java.util.Objects;

/** Tax rate for a jurisdiction over an effective period. */
public final class TaxRule extends Aggregate<TaxRuleId> {
    private final String jurisdiction; private final TaxCategory category; private final Percentage rate; private final Instant validFrom; private final Instant validUntil; private boolean active;
    private TaxRule(TaxRuleId id, String jurisdiction, TaxCategory category, Percentage rate, Instant from, Instant until, boolean active) {
        super(id); this.jurisdiction = Price.code(jurisdiction, "Jurisdiction"); this.category = Objects.requireNonNull(category); this.rate = Objects.requireNonNull(rate); this.validFrom = Objects.requireNonNull(from);
        if (until != null && !until.isAfter(from)) throw new IllegalArgumentException("Tax rule end must be after its start"); this.validUntil = until; this.active = active;
    }
    public static TaxRule create(TaxRuleId id, String jurisdiction, Percentage rate, Instant from, Instant until) { return create(id,jurisdiction,TaxCategory.STANDARD,rate,from,until); }
    public static TaxRule create(TaxRuleId id, String jurisdiction, TaxCategory category, Percentage rate, Instant from, Instant until) { var rule = new TaxRule(id, jurisdiction, category, rate, from, until, true); rule.recordEvent(new TaxRuleCreated(id, jurisdiction, rate)); return rule; }
    public static TaxRule reconstitute(TaxRuleId id, String jurisdiction, Percentage rate, Instant from, Instant until, boolean active) { return reconstitute(id,jurisdiction,TaxCategory.STANDARD,rate,from,until,active); }
    public static TaxRule reconstitute(TaxRuleId id, String jurisdiction, TaxCategory category, Percentage rate, Instant from, Instant until, boolean active) { return new TaxRule(id, jurisdiction, category, rate, from, until, active); }
    public boolean applies(String region, Instant at) { return active && jurisdiction.equals(region) && !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil)); }
    public void setActive(boolean value) { if (active != value) { active = value; recordEvent(new TaxRuleActiveChanged(getId(), value)); } }
    public String getJurisdiction() { return jurisdiction; } public TaxCategory getCategory() { return category; } public Percentage getRate() { return rate; } public Instant getValidFrom() { return validFrom; } public Instant getValidUntil() { return validUntil; } public boolean isActive() { return active; }
}
