package io.artio.api.inventory;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryScheduler {
    private final InventoryApplicationService inventory;
    public ReservationExpiryScheduler(InventoryApplicationService inventory) { this.inventory = inventory; }

    @Scheduled(fixedDelayString = "${artio.inventory.expiry-interval-ms:60000}")
    public void releaseExpiredReservations() { inventory.expireDueReservations(Instant.now(), 250); }
}
