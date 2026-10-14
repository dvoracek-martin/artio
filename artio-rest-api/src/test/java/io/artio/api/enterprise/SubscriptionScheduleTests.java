package io.artio.api.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SubscriptionScheduleTests {
    @Test
    void monthlyAndQuarterlyPeriodsHandleShortMonths() {
        var zurich = ZoneId.of("Europe/Zurich");
        assertThat(SubscriptionSchedule.next("MONTHLY", Instant.parse("2026-01-31T09:00:00Z"), zurich))
                .isEqualTo(Instant.parse("2026-02-28T09:00:00Z"));
        assertThat(SubscriptionSchedule.next("QUARTERLY", Instant.parse("2026-01-31T09:00:00Z"), zurich))
                .isEqualTo(Instant.parse("2026-04-30T08:00:00Z"));
    }

    @Test
    void weeklyPeriodPreservesLocalHourAcrossDaylightSavingChange() {
        assertThat(SubscriptionSchedule.next("WEEKLY", Instant.parse("2026-03-22T09:00:00Z"), ZoneId.of("Europe/Zurich")))
                .isEqualTo(Instant.parse("2026-03-29T08:00:00Z"));
        assertThatThrownBy(() -> SubscriptionSchedule.next("DAILY", Instant.now(), ZoneId.of("UTC")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
