package io.artio.api.payments;

import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.api.orders.OrderTimelineEvents;
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
        var orders = jdbc.query("SELECT status,user_id FROM orders WHERE id=? FOR UPDATE",
                (rs, row) -> new OrderState(rs.getString("status"), rs.getObject("user_id", UUID.class)), orderId);
        if (orders.isEmpty() || !"PENDING".equals(orders.getFirst().status())) return;
        UUID userId = orders.getFirst().userId();
        if (jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId) == 0) return;

        jdbc.update("UPDATE payments SET status='FAILED',updated_at=now() WHERE order_id=? AND status='PENDING'", orderId);
        for (var reservation : inventory.reservationsByOrder(orderId)) {
            if (reservation.state() == ReservationState.RESERVED) inventory.release(reservation.id());
        }
        OrderTimelineEvents.record(jdbc, orderId, userId, "ORDER_CANCELLED",
                "Order cancelled because payment provider rejected checkout", true);
        jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,dedupe_key,created_at) " +
                        "VALUES (?,?,?,'Order cancelled',?,false,?,now()) ON CONFLICT (dedupe_key) DO NOTHING",
                UUID.randomUUID(), userId, "ORDER_CANCELLED", "Order " + orderId + " was cancelled.", "order-cancelled:" + orderId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { orderUpdates.publish(orderId); }
            });
        }
    }

    record OrderState(String status, UUID userId) { }
}
