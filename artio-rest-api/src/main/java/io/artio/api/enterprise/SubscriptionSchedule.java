package io.artio.api.enterprise;

import java.time.Instant;
import java.time.ZoneId;

/** Calendar periods preserve merchant-local billing time across month ends and daylight-saving changes. */
final class SubscriptionSchedule {
    private SubscriptionSchedule() { }
    static Instant next(String frequency, Instant after, ZoneId timezone) {
        var local = after.atZone(timezone);
        return switch (frequency) {
            case "WEEKLY" -> local.plusWeeks(1).toInstant();
            case "MONTHLY" -> local.plusMonths(1).toInstant();
            case "QUARTERLY" -> local.plusMonths(3).toInstant();
            default -> throw new IllegalArgumentException("Unsupported subscription frequency");
        };
    }
}
