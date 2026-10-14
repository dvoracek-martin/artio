package io.artio.api.enterprise;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Transactional points ledger rules shared by payment events and customer loyalty APIs. */
public final class LoyaltyAccounting {
    private LoyaltyAccounting() { }

    public static void earnForOrder(JdbcTemplate jdbc, UUID userId, UUID orderId, BigDecimal orderTotal) {
        long points = pointsForOrder(orderTotal);
        if (points <= 0) return;
        // Fulfill the immutable order-time promise, independently of current license state.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM orders WHERE id=? AND user_id=? AND loyalty_eligible)",
                Boolean.class, orderId, userId))) return;
        jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        int inserted = jdbc.update("INSERT INTO loyalty_transactions(id,user_id,order_id,points,kind) VALUES (?,?,?,?,'EARN') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), userId, orderId, points);
        if (inserted == 1) updateBalance(jdbc, userId, points);
    }

    public static void reverseForRefund(JdbcTemplate jdbc, UUID refundId, UUID orderId) {
        var account = jdbc.query("SELECT o.user_id,o.total,COALESCE((SELECT sum(p.refunded_amount) FROM payments p WHERE p.order_id=o.id),0) "
                        + "FROM orders o WHERE o.id=?", (rs,row)->new RefundBasis(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getBigDecimal(3)),orderId);
        if(account.isEmpty())return;
        RefundBasis basis=account.getFirst();
        jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",basis.userId());
        jdbc.query("SELECT points FROM loyalty_accounts WHERE user_id=? FOR UPDATE",rs->{if(rs.next())return rs.getLong(1);return 0L;},basis.userId());
        long earned=jdbc.queryForObject("SELECT COALESCE(sum(points),0) FROM loyalty_transactions WHERE user_id=? AND order_id=? AND kind='EARN'",Long.class,basis.userId(),orderId);
        long reversed=jdbc.queryForObject("SELECT COALESCE(-sum(points),0) FROM loyalty_transactions WHERE user_id=? AND order_id=? AND kind='REFUND'",Long.class,basis.userId(),orderId);
        long delta=additionalReversal(earned,basis.refundedTotal(),basis.orderTotal(),reversed);
        if(delta==0)return;
        int inserted=jdbc.update("INSERT INTO loyalty_transactions(id,user_id,order_id,refund_id,points,kind) VALUES (?,?,?,?,?,'REFUND') ON CONFLICT DO NOTHING",
                UUID.randomUUID(),basis.userId(),orderId,refundId,-delta);
        if(inserted==1)updateBalance(jdbc,basis.userId(),-delta);
    }

    private static void updateBalance(JdbcTemplate jdbc,UUID userId,long delta) {
        jdbc.update("UPDATE loyalty_accounts SET points=points+?,tier=CASE WHEN points+?>=10000 THEN 'GOLD' WHEN points+?>=2500 THEN 'SILVER' ELSE 'BRONZE' END,updated_at=now() WHERE user_id=?",
                delta,delta,delta,userId);
    }
    static long pointsForOrder(BigDecimal total){return total.setScale(0,RoundingMode.DOWN).longValueExact();}
    static long additionalReversal(long earned,BigDecimal refunded,BigDecimal orderTotal,long alreadyReversed){
        if(earned<=0||orderTotal==null||orderTotal.signum()<=0||refunded==null||refunded.signum()<=0)return 0;
        long target=BigDecimal.valueOf(earned).multiply(refunded).divide(orderTotal,0,RoundingMode.DOWN).longValueExact();
        return Math.max(0,Math.min(earned,target)-Math.max(0,alreadyReversed));
    }
    private record RefundBasis(UUID userId,BigDecimal orderTotal,BigDecimal refundedTotal) { }
}
