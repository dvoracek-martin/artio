package io.artio.api.orders;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderUpdatePublisher {
    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final RedisOrderUpdateFanout redisFanout;

    public OrderUpdatePublisher(JdbcTemplate jdbc, ObjectProvider<SimpMessagingTemplate> messaging,
            ObjectProvider<RedisOrderUpdateFanout> redisFanout) {
        this.jdbc = jdbc;
        this.messaging = messaging.getIfAvailable();
        this.redisFanout = redisFanout.getIfAvailable();
    }

    public void publish(UUID orderId) {
        if (messaging == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publishCommittedOrder(orderId); }
            });
            return;
        }
        publishCommittedOrder(orderId);
    }

    private void publishCommittedOrder(UUID orderId) {
        var rows = jdbc.query("SELECT user_id,status,total,currency,tracking_number FROM orders WHERE id=?",
                (rs, n) -> new OrderRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getString(4).trim(), rs.getString(5)), orderId);
        if (rows.isEmpty()) return;
        OrderRow row = rows.getFirst();
        var update = new OrderStreamController.OrderUpdate(orderId, row.status(), row.total(), row.currency(),
                row.tracking(), Instant.now());
        if (redisFanout != null) {
            try {
                redisFanout.publish(row.userId(), update);
                return;
            } catch (RuntimeException unavailable) {
                // Realtime is an optimization; do not fail a committed commerce operation if Redis is unavailable.
            }
        }
        messaging.convertAndSendToUser(row.userId().toString(), "/queue/orders/" + orderId, update);
    }

    private record OrderRow(UUID userId, String status, java.math.BigDecimal total, String currency, String tracking) { }
}
