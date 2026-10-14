package io.artio.api.payments;

import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.enterprise.GiftCardAccounting;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.api.orders.OrderTimelineEvents;
import io.artio.inventory.ReservationState;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
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

        var storedValuePayments = jdbc.query("SELECT id,user_id,gift_card_id,amount,currency FROM payments " +
                        "WHERE order_id=? AND method='GIFT_CARD' AND status='CAPTURED' FOR UPDATE",
                (rs, row) -> new GiftCardPayment(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("gift_card_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency").trim()), orderId);
        for (GiftCardPayment payment : storedValuePayments) {
            if (payment.giftCardId() == null) throw new IllegalStateException("Captured gift-card payment has no card ledger");
            String key = "checkout-rejection:" + orderId;
            UUID refundId = UUID.nameUUIDFromBytes((payment.id() + ":" + key).getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO payment_refunds(id,payment_id,idempotency_key,amount,currency,status,provider_reference) " +
                            "VALUES (?,?,?,?,?,'COMPLETED',?) ON CONFLICT (payment_id,idempotency_key) DO NOTHING",
                    refundId, payment.id(), key, payment.amount(), payment.currency(), "giftcard-refund:" + refundId);
            GiftCardAccounting.restoreRejectedCheckout(jdbc, payment.giftCardId(), payment.userId(), orderId, refundId, payment.amount());
            jdbc.update("UPDATE payments SET refunded_amount=amount,status='REFUNDED',updated_at=now() WHERE id=? AND status='CAPTURED'",
                    payment.id());
            OrderTimelineEvents.record(jdbc, orderId, payment.userId(), "PAYMENT_REFUNDED",
                    "Gift card credit restored after checkout rejection", true);
        }
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
    private record GiftCardPayment(UUID id, UUID userId, UUID giftCardId, java.math.BigDecimal amount, String currency) { }
}
