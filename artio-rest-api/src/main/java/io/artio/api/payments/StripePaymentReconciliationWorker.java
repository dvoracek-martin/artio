package io.artio.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** Reconciles Stripe Checkout sessions when webhook delivery is delayed or lost. */
@Component
public class StripePaymentReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(StripePaymentReconciliationWorker.class);
    private static final int BATCH_SIZE = 50;
    private static final int SESSION_LIFETIME_MINUTES = 32;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;
    private final RestClient stripe;
    private final ObjectMapper mapper;
    private final String stripeKey;
    private final TransactionTemplate transactions;

    public StripePaymentReconciliationWorker(JdbcTemplate jdbc, InventoryApplicationService inventory,
            OrderUpdatePublisher orderUpdates, ObjectMapper mapper, PlatformTransactionManager transactionManager,
            RestClient.Builder stripeBuilder, @Value("${artio.payments.stripe-secret-key:}") String stripeKey,
            @Value("${artio.payments.stripe-api-url:https://api.stripe.com}") String stripeApiUrl) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.orderUpdates = orderUpdates;
        this.mapper = mapper;
        this.stripe = stripeBuilder.baseUrl(stripeApiUrl).build();
        this.stripeKey = stripeKey;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${artio.payments.reconciliation-interval-ms:60000}")
    public void reconcileDueSessions() {
        if (stripeKey.isBlank()) return;
        List<PaymentSession> claimed = transactions.execute(status -> jdbc.query("""
                UPDATE payments SET reconciliation_claimed_at=now(), reconciliation_attempts=reconciliation_attempts+1
                WHERE id IN (
                    SELECT id FROM payments
                    WHERE method='STRIPE' AND status='PENDING'
                      AND created_at < now() - (? * interval '1 minute')
                      AND reconciliation_available_at <= now()
                      AND (reconciliation_claimed_at IS NULL OR reconciliation_claimed_at < now() - (? * interval '1 second'))
                    ORDER BY reconciliation_available_at, created_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                RETURNING id, order_id, provider_reference, reconciliation_attempts
                """, (rs, row) -> new PaymentSession(rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("provider_reference"),
                rs.getInt("reconciliation_attempts")), SESSION_LIFETIME_MINUTES,
                CLAIM_LEASE.toSeconds(), BATCH_SIZE));
        if (claimed == null) return;
        for (PaymentSession payment : claimed) reconcile(payment);
    }

    private void reconcile(PaymentSession payment) {
        try {
            Map<?, ?> body = stripe.get()
                    .uri("/v1/checkout/sessions/{id}", payment.sessionId())
                    .headers(headers -> headers.setBearerAuth(stripeKey))
                    .retrieve().body(Map.class);
            JsonNode session = mapper.valueToTree(body);
            String status = session.path("status").asText();
            String paymentStatus = session.path("payment_status").asText();
            if ("complete".equals(status) && "paid".equals(paymentStatus)) {
                applyPaid(payment);
            } else if ("expired".equals(status)) {
                applyExpired(payment);
            } else {
                retry(payment, "SESSION_" + (status.isBlank() ? "UNKNOWN" : status.toUpperCase(java.util.Locale.ROOT)));
            }
        } catch (Exception failure) {
            String errorType = failure instanceof org.springframework.web.client.RestClientResponseException response
                    ? "StripeHttp" + response.getStatusCode().value() : failure.getClass().getSimpleName();
            log.warn("Stripe reconciliation failed for payment {}; retry scheduled ({})", payment.id(), errorType);
            retry(payment, errorType);
        }
    }

    private void applyPaid(PaymentSession payment) {
        transactions.executeWithoutResult(status -> {
            UUID orderId = lockPendingOrder(payment);
            if (orderId == null) { clearClaim(payment.id()); return; }
            int captured = jdbc.update("UPDATE payments SET status='CAPTURED',updated_at=now(),reconciliation_claimed_at=NULL WHERE id=? AND status='PENDING'", payment.id());
            if (captured == 0) { clearClaim(payment.id()); return; }
            jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'", orderId);
            jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind,order_id) SELECT gen_random_uuid(),o.user_id,oi.variant_id,'PURCHASE',o.id FROM orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.id=? ON CONFLICT DO NOTHING", orderId);
            jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,created_at) SELECT ?,user_id,'PAYMENT_RECEIVED','Payment received','Payment received for order '||id,false,now() FROM orders WHERE id=? AND NOT EXISTS (SELECT 1 FROM notifications WHERE type='PAYMENT_RECEIVED' AND body='Payment received for order '||?)", UUID.randomUUID(), orderId, orderId);
            orderUpdates.publish(orderId);
        });
    }

    private void applyExpired(PaymentSession payment) {
        transactions.executeWithoutResult(status -> {
            UUID orderId = lockPendingOrder(payment);
            if (orderId == null) { clearClaim(payment.id()); return; }
            jdbc.update("UPDATE payments SET status='FAILED',updated_at=now(),reconciliation_claimed_at=NULL WHERE id=? AND status='PENDING'", payment.id());
            boolean hasPending = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM payments WHERE order_id=? AND status='PENDING')", Boolean.class, orderId));
            if (!hasPending && jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId) > 0) {
                for (var reservation : inventory.reservationsByOrder(orderId))
                    if (reservation.state() == io.artio.inventory.ReservationState.RESERVED) inventory.release(reservation.id());
                orderUpdates.publish(orderId);
            }
        });
    }

    private UUID lockPendingOrder(PaymentSession payment) {
        List<UUID> orders = jdbc.query("SELECT o.id FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.id=? AND p.status='PENDING' AND o.status='PENDING' FOR UPDATE OF o",
                (rs, row) -> rs.getObject(1, UUID.class), payment.id());
        return orders.isEmpty() ? null : orders.getFirst();
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

    private record PaymentSession(UUID id, UUID orderId, String sessionId, int attempts) { }
}
