package io.artio.pricing;

import io.artio.kernel.Aggregate; import io.artio.kernel.Percentage; import java.time.Instant; import java.util.Objects; import java.util.UUID;

/** Discount can target one sellable or all sellables and may be limited to a market. */
public final class DiscountRule extends Aggregate<DiscountRuleId> {
    private final String code; private final UUID sellableId; private final String market; private final Percentage percentage;
    private final int priority; private final Instant validFrom; private final Instant validUntil; private final Integer usageLimit; private boolean active;
    private DiscountRule(DiscountRuleId id, String code, UUID sellableId, String market, Percentage percentage, int priority, Instant validFrom, Instant validUntil, Integer usageLimit, boolean active) {
        super(id); if (code == null || !code.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) throw new IllegalArgumentException("Discount code must be 2-64 uppercase letters, digits, '_' or '-'");
        this.code = code; this.sellableId = sellableId; this.market = market == null ? null : Price.code(market, "Market"); this.percentage = Objects.requireNonNull(percentage);
        if (priority < 0) throw new IllegalArgumentException("Discount priority cannot be negative"); this.priority = priority;
        this.validFrom = Objects.requireNonNull(validFrom); if (validUntil != null && !validUntil.isAfter(validFrom)) throw new IllegalArgumentException("Discount end must be after its start");
        if (usageLimit != null && usageLimit < 1) throw new IllegalArgumentException("Discount usage limit must be positive");
        this.usageLimit = usageLimit;
        this.validUntil = validUntil; this.active = active;
    }
    public static DiscountRule create(DiscountRuleId id, String code, UUID sellableId, String market, Percentage percentage, int priority, Instant from, Instant until) { return create(id,code,sellableId,market,percentage,priority,from,until,null); }
    public static DiscountRule create(DiscountRuleId id, String code, UUID sellableId, String market, Percentage percentage, int priority, Instant from, Instant until, Integer usageLimit) { var rule = new DiscountRule(id, code, sellableId, market, percentage, priority, from, until, usageLimit, true); rule.recordEvent(new DiscountRuleCreated(id, code, percentage)); return rule; }
    public static DiscountRule reconstitute(DiscountRuleId id, String code, UUID sellableId, String market, Percentage percentage, int priority, Instant from, Instant until, boolean active) { return reconstitute(id,code,sellableId,market,percentage,priority,from,until,null,active); }
    public static DiscountRule reconstitute(DiscountRuleId id, String code, UUID sellableId, String market, Percentage percentage, int priority, Instant from, Instant until, Integer usageLimit, boolean active) { return new DiscountRule(id, code, sellableId, market, percentage, priority, from, until, usageLimit, active); }
    public boolean applies(UUID item, String itemMarket, Instant at) { return active && (sellableId == null || sellableId.equals(item)) && (market == null || market.equals(itemMarket)) && !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil)); }
    public void setActive(boolean value) { if (active != value) { active = value; recordEvent(new DiscountRuleActiveChanged(getId(), value)); } }
    public String getCode() { return code; } public UUID getSellableId() { return sellableId; } public String getMarket() { return market; } public Percentage getPercentage() { return percentage; } public int getPriority() { return priority; } public Instant getValidFrom() { return validFrom; } public Instant getValidUntil() { return validUntil; } public Integer getUsageLimit() { return usageLimit; } public boolean isActive() { return active; }
}
