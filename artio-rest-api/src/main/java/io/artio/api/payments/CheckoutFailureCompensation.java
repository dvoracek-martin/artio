package io.artio.api.payments;

import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.inventory.ReservationState;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cancels a rejected checkout and releases its stock in the same database transaction. */
@Service
public class CheckoutFailureCompensation {
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;

    public CheckoutFailureCompensation(JdbcTemplate jdbc, InventoryApplicationService inventory,
                                       OrderUpdatePublisher orderUpdates) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.orderUpdates = orderUpdates;
    }

    @Transactional
    public void cancelRejectedCheckout(UUID orderId) {
        var statuses = jdbc.query("SELECT status FROM orders WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), orderId);
        if (statuses.isEmpty() || !"PENDING".equals(statuses.getFirst())) return;
        if (jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId) == 0) return;

        jdbc.update("UPDATE payments SET status='FAILED',updated_at=now() WHERE order_id=? AND status='PENDING'", orderId);
        for (var reservation : inventory.reservationsByOrder(orderId)) {
            if (reservation.state() == ReservationState.RESERVED) inventory.release(reservation.id());
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { orderUpdates.publish(orderId); }
            });
        }
    }
}
