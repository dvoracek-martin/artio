package io.artio.api.inventory;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import io.artio.api.orders.OrderUpdatePublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryScheduler {
    private final InventoryApplicationService inventory;
    private final JdbcTemplate jdbc;
    private final OrderUpdatePublisher orderUpdates;
    private final TransactionTemplate transactions;
    public ReservationExpiryScheduler(InventoryApplicationService inventory, JdbcTemplate jdbc,
            OrderUpdatePublisher orderUpdates, PlatformTransactionManager transactionManager) {
        this.inventory = inventory;
        this.jdbc = jdbc;
        this.orderUpdates = orderUpdates;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${artio.inventory.expiry-interval-ms:60000}")
    public void releaseExpiredReservations() {
        Instant now = Instant.now();
        inventory.expireDueReservations(now, 250);
        List<UUID> cancelled = transactions.execute(status -> {
            List<UUID> candidates = jdbc.query("""
                    SELECT o.id FROM orders o
                    WHERE o.status='PENDING'
                      AND EXISTS (SELECT 1 FROM inventory_reservation r WHERE r.order_id=o.id)
                      AND NOT EXISTS (SELECT 1 FROM inventory_reservation r
                                      WHERE r.order_id=o.id AND (r.state<>'RELEASED' OR r.expires_at>?))
                      AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id=o.id AND p.status='CAPTURED')
                    ORDER BY o.created_at
                    LIMIT 250
                    FOR UPDATE OF o SKIP LOCKED
                    """, (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(now));
            for (UUID orderId : candidates) {
                jdbc.update("UPDATE payments SET status='FAILED',updated_at=now() WHERE order_id=? AND status='PENDING'", orderId);
                jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId);
            }
            return candidates;
        });
        if (cancelled != null) cancelled.forEach(orderUpdates::publish);
    }
}
