package io.artio.pricing;

import io.artio.kernel.Aggregate; import io.artio.kernel.Money;
import java.time.Instant; import java.util.Objects; import java.util.UUID;

/** A time-bounded price for a catalog sellable in a market and sales channel. */
public final class Price extends Aggregate<PriceId> {
    private final UUID sellableId; private final String market; private final String channel;
    private Money amount; private Instant validFrom; private Instant validUntil; private boolean active;
    private Price(PriceId id, UUID sellableId, String market, String channel, Money amount, Instant validFrom, Instant validUntil, boolean active) {
        super(id); this.sellableId = Objects.requireNonNull(sellableId, "Sellable ID is required"); this.market = code(market, "Market"); this.channel = code(channel, "Channel");
        this.amount = Objects.requireNonNull(amount, "Price amount is required"); this.validFrom = Objects.requireNonNull(validFrom, "Price start is required");
        if (validUntil != null && !validUntil.isAfter(validFrom)) throw new IllegalArgumentException("Price end must be after its start");
        this.validUntil = validUntil; this.active = active;
    }
    public static Price create(PriceId id, UUID sellableId, String market, String channel, Money amount, Instant validFrom, Instant validUntil) {
        var price = new Price(id, sellableId, market, channel, amount, validFrom, validUntil, true);
        price.recordEvent(new PriceCreated(id, sellableId, amount)); return price;
    }
    public static Price reconstitute(PriceId id, UUID sellableId, String market, String channel, Money amount, Instant validFrom, Instant validUntil, boolean active) {
        return new Price(id, sellableId, market, channel, amount, validFrom, validUntil, active);
    }
    public void changeAmount(Money value) { Objects.requireNonNull(value); if (!amount.equals(value)) { var before = amount; amount = value; recordEvent(new PriceAmountChanged(getId(), before, value)); } }
    public void setActive(boolean value) { if (active != value) { active = value; recordEvent(new PriceActiveChanged(getId(), value)); } }
    public boolean appliesTo(UUID id, String market, String channel, Instant at) { return active && sellableId.equals(id) && this.market.equals(market) && this.channel.equals(channel) && !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil)); }
    public UUID getSellableId() { return sellableId; } public String getMarket() { return market; } public String getChannel() { return channel; }
    public Money getAmount() { return amount; } public Instant getValidFrom() { return validFrom; } public Instant getValidUntil() { return validUntil; } public boolean isActive() { return active; }
    public static String marketCode(String value) { return code(value, "Market"); }
    public static String channelCode(String value) { return code(value, "Channel"); }
    public static String jurisdictionCode(String value) { return code(value, "Jurisdiction"); }
    static String code(String value, String label) { if (value == null || !value.matches("[A-Z0-9][A-Z0-9_-]{1,31}")) throw new IllegalArgumentException(label + " must use 2-32 uppercase letters, digits, '_' or '-'"); return value; }
}
