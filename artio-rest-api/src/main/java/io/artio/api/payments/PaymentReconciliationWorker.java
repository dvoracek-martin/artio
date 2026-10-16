package io.artio.api.payments;

import io.artio.api.enterprise.LoyaltyAccounting;
import io.artio.api.orders.OrderTimelineEvents;

import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.extensions.commerce.PaymentProvider;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles provider-neutral hosted checkout sessions when webhook delivery is delayed or lost. */
@Component
@ConditionalOnProperty(name = "artio.workers.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationWorker.class);
    private static final int BATCH_SIZE = 50;
    private static final int SESSION_LIFETIME_MINUTES = 32;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final ObjectMapper OWNER_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> OWNER_SCOPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;
    private final TransactionTemplate transactions;
    private final ObjectProvider<PaymentProvider> paymentProvider;

    public PaymentReconciliationWorker(JdbcTemplate jdbc, InventoryApplicationService inventory,
            OrderUpdatePublisher orderUpdates, PlatformTransactionManager transactionManager,
            ObjectProvider<PaymentProvider> paymentProvider) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.orderUpdates = orderUpdates;
        this.transactions = new TransactionTemplate(transactionManager);
        this.paymentProvider = paymentProvider;
    }

    @Scheduled(fixedDelayString = "${artio.payments.reconciliation-interval-ms:60000}")
    public void reconcileDueSessions() {
        if (paymentProvider.getIfAvailable() == null) return;
        String claimSql = """
                UPDATE payments SET reconciliation_claimed_at=now(), reconciliation_attempts=reconciliation_attempts+1
                WHERE id IN (
                    SELECT id FROM payments
                    WHERE method='EXTENSION' AND status='PENDING'
                      AND created_at < now() - (? * interval '1 minute')
                      AND reconciliation_available_at <= now()
                      AND (reconciliation_claimed_at IS NULL OR reconciliation_claimed_at < now() - (? * interval '1 second'))
                    ORDER BY reconciliation_available_at, created_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                RETURNING id, order_id, provider_reference, reconciliation_attempts, method, amount, currency,
                          provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope::text
                """;
        List<PaymentSession> claimed = transactions.execute(status -> jdbc.query(claimSql, (rs, row) -> new PaymentSession(rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("provider_reference"),
                rs.getInt("reconciliation_attempts"), rs.getString("method"), rs.getBigDecimal("amount"), rs.getString("currency").trim(), providerOwner(rs,8)), SESSION_LIFETIME_MINUTES,
                CLAIM_LEASE.toSeconds(), BATCH_SIZE));
        if (claimed == null) return;
        for (PaymentSession payment : claimed) reconcile(payment);
    }

    private void reconcile(PaymentSession payment) {
        PaymentProvider provider = paymentProvider.getIfAvailable();
        if (provider == null) { retry(payment, "PAYMENT_PROVIDER_UNAVAILABLE"); return; }
        try {
            var owned = provider.reconcile(payment.owner(), payment.sessionId(), "artio-reconcile-" + payment.id());
            if (owned.isEmpty()) { retry(payment, "PROVIDER_PENDING"); return; }
            var result = owned.get().result();
            switch (result.state()) {
                case CAPTURED -> {
                    if (!payment.sessionId().equals(result.providerReference()) || result.amount() == null || result.currency() == null) {
                        retry(payment, "PROVIDER_SETTLEMENT_INCOMPLETE");
                    } else applyCaptured(payment, result);
                }
                case EXPIRED, FAILED, CANCELLED -> applyExpired(payment);
                default -> retry(payment, "PROVIDER_" + result.state().name());
            }
        } catch (Exception failure) {
            log.warn("Payment provider reconciliation failed for payment {}; retry scheduled ({})",
                    payment.id(), failure.getClass().getSimpleName());
            retry(payment, failure.getClass().getSimpleName());
        }
    }

    private void applyCaptured(PaymentSession payment, PaymentProvider.PaymentResult result) {
        transactions.executeWithoutResult(status -> {
            var rows = jdbc.query("SELECT o.id,o.status,p.status FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.id=? FOR UPDATE OF o,p",
                    (rs, row) -> new OrderPayment(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), payment.id());
            if (rows.isEmpty() || !Set.of("PENDING", "FAILED", "CANCELLED").contains(rows.getFirst().paymentStatus())) {
                clearClaim(payment.id()); return;
            }
            OrderPayment orderPayment = rows.getFirst();
            UUID orderId = orderPayment.orderId();
            String captureCurrency = result.currency().trim().toUpperCase(java.util.Locale.ROOT);
            boolean currencyMatches = payment.currency().equalsIgnoreCase(captureCurrency);
            int amountComparison = payment.amount().compareTo(result.amount());
            String disposition;
            String caseType = null;
            if (!currencyMatches) { disposition = "CURRENCY_MISMATCH"; caseType = disposition; }
            else if ("CANCELLED".equals(orderPayment.orderStatus())) { disposition = "LATE_CAPTURE"; caseType = disposition; }
            else if ("PAID".equals(orderPayment.orderStatus()) || amountComparison < 0) { disposition = "OVERPAYMENT"; caseType = disposition; }
            else if (amountComparison > 0) { disposition = "UNDERPAYMENT"; caseType = disposition; }
            else disposition = "APPLIED";
            UUID settlementId = UUID.randomUUID();
            String reconciliationEventId = "reconcile:" + payment.id();
            jdbc.update("INSERT INTO payment_settlements(settlement_id,payment_id,provider_event_id,provider_reference,amount,currency,disposition) VALUES (?,?,?,?,?,?,?)",
                    settlementId, payment.id(), reconciliationEventId, payment.sessionId(), result.amount(), captureCurrency, disposition);
            if (caseType != null) jdbc.update("INSERT INTO payment_reconciliation_cases(case_id,payment_id,order_id,settlement_id,case_type,expected_amount,captured_amount,expected_currency,captured_currency) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), payment.id(), orderId, settlementId, caseType, payment.amount(), result.amount(), payment.currency(), captureCurrency);
            boolean review = "UNDERPAYMENT".equals(disposition) || "CURRENCY_MISMATCH".equals(disposition);
            jdbc.update("UPDATE payments SET captured_amount=?,captured_currency=?,provider_transaction_reference=COALESCE(?,provider_transaction_reference),status=?,updated_at=now(),reconciliation_claimed_at=NULL WHERE id=? AND status IN ('PENDING','FAILED','CANCELLED')",
                    result.amount(), captureCurrency, result.transactionReference(), review ? "CAPTURE_REVIEW" : "CAPTURED", payment.id());
            jdbc.update("UPDATE payment_attempts SET status='CAPTURED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", payment.id());
            int paid = review || "LATE_CAPTURE".equals(disposition) ? 0
                    : jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'", orderId);
            if(paid>0){
                OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_PAID","Payment confirmed",true);
                jdbc.query("SELECT user_id,total FROM orders WHERE id=?",(rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getBigDecimal(2)},orderId)
                        .stream().findFirst().ifPresent(order->LoyaltyAccounting.earnForOrder(jdbc,(UUID)order[0],orderId,(java.math.BigDecimal)order[1]));
                jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind,order_id) SELECT gen_random_uuid(),o.user_id,oi.variant_id,'PURCHASE',o.id FROM orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.id=? ON CONFLICT DO NOTHING", orderId);
                jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,created_at) SELECT ?,user_id,'PAYMENT_RECEIVED','Payment received','Payment received for order '||id,false,now() FROM orders WHERE id=? AND NOT EXISTS (SELECT 1 FROM notifications WHERE type='PAYMENT_RECEIVED' AND body='Payment received for order '||?)", UUID.randomUUID(), orderId, orderId);
                orderUpdates.publish(orderId);
            }
        });
    }

    private void applyExpired(PaymentSession payment) {
        transactions.executeWithoutResult(status -> {
            UUID orderId = lockPendingOrder(payment);
            if (orderId == null) { clearClaim(payment.id()); return; }
            jdbc.update("UPDATE payments SET status='FAILED',updated_at=now(),reconciliation_claimed_at=NULL WHERE id=? AND status='PENDING'", payment.id());
            jdbc.update("UPDATE payment_attempts SET status='FAILED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", payment.id());
            boolean hasPending = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM payments WHERE order_id=? AND status='PENDING')", Boolean.class, orderId));
            if (!hasPending && jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId) > 0) {
                OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_CANCELLED","Order cancelled because payment expired",true);
                for (var reservation : inventory.reservationsByOrder(orderId))
                    if (reservation.state() == io.artio.inventory.ReservationState.RESERVED) inventory.release(reservation.id());
                orderUpdates.publish(orderId);
            }
        });
    }

    private void retry(PaymentSession payment, String errorType) {
        long backoffSeconds = retryDelaySeconds(payment.attempts());
        String diagnostic = errorType == null ? null : errorType.substring(0, Math.min(errorType.length(), 64));
        jdbc.update("UPDATE payments SET reconciliation_available_at=?, reconciliation_claimed_at=NULL, reconciliation_last_error=? WHERE id=? AND status='PENDING'",
                Timestamp.from(Instant.now().plusSeconds(backoffSeconds)), diagnostic, payment.id());
    }

    static long retryDelaySeconds(int attempts) {
        return Math.min(3600, 30L << Math.max(0, Math.min(attempts - 1, 7)));
    }

    private void clearClaim(UUID paymentId) {
        jdbc.update("UPDATE payments SET reconciliation_claimed_at=NULL WHERE id=?", paymentId);
    }

    private UUID lockPendingOrder(PaymentSession payment) {
        List<UUID> orders = jdbc.query("SELECT o.id FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.id=? AND p.status='PENDING' AND o.status='PENDING' FOR UPDATE OF o",
                (rs, row) -> rs.getObject(1, UUID.class), payment.id());
        return orders.isEmpty() ? null : orders.getFirst();
    }

    private static PaymentProvider.ProviderOwner providerOwner(ResultSet rs, int column) throws SQLException {
        UUID instanceId = rs.getObject(column, UUID.class);
        if (instanceId == null) return null;
        Number releaseValue = (Number) rs.getObject(column + 1);
        try {
            return new PaymentProvider.ProviderOwner(instanceId, releaseValue == null ? null : releaseValue.longValue(),
                    rs.getInt(column + 2), rs.getString(column + 3) == null ? null : rs.getString(column + 3).trim(), OWNER_MAPPER.readValue(rs.getString(column + 4), OWNER_SCOPE));
        } catch (Exception invalid) { throw new SQLException("Persisted provider scope is invalid", invalid); }
    }
    private record PaymentSession(UUID id, UUID orderId, String sessionId, int attempts, String method,
                                  java.math.BigDecimal amount, String currency, PaymentProvider.ProviderOwner owner) { }
    private record OrderPayment(UUID orderId, String orderStatus, String paymentStatus) { }
}
