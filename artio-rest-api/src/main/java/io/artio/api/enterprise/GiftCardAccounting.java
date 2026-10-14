package io.artio.api.enterprise;

import io.artio.api.orders.OrderTimelineEvents;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Applies stored-value tender to a single pending order inside its checkout/refund transaction. */
public final class GiftCardAccounting {
    private GiftCardAccounting() { }

    public static Redemption redeem(JdbcTemplate jdbc,String rawCode,UUID userId,UUID orderId,BigDecimal requestedAmount) {
        if(rawCode==null||rawCode.isBlank()||rawCode.length()>128)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid gift card code is required");
        var orders=jdbc.query("SELECT total,currency,status FROM orders WHERE id=? AND user_id=? FOR UPDATE",
                (rs,row)->new OrderRow(rs.getBigDecimal(1),rs.getString(2).trim(),rs.getString(3)),orderId,userId);
        if(orders.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        OrderRow order=orders.getFirst();
        if(!"PENDING".equals(order.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Gift cards can only be applied to unpaid orders");
        String hash=hash(rawCode.replace("-","").toUpperCase(java.util.Locale.ROOT));
        var cards=jdbc.query("SELECT id,balance,currency FROM gift_cards WHERE code_hash=? AND active AND (expires_at IS NULL OR expires_at>now()) FOR UPDATE",
                (rs,row)->new Card(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getString(3).trim()),hash);
        if(cards.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Gift card not found, inactive or expired");
        Card card=cards.getFirst();
        if(!order.currency().equalsIgnoreCase(card.currency()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Gift card currency does not match the order");
        var prior=jdbc.query("SELECT id,amount FROM gift_card_transactions WHERE gift_card_id=? AND order_id=? AND kind='REDEEM'",
                (rs,row)->new PriorRedemption(rs.getObject(1,UUID.class),rs.getBigDecimal(2)),card.id(),orderId);
        if(!prior.isEmpty()){
            PriorRedemption saved=prior.getFirst();
            if(requestedAmount!=null&&requestedAmount.compareTo(saved.amount())!=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"This gift card was already applied to the order with a different amount");
            BigDecimal due=remainingDue(jdbc,orderId,order.total());
            return new Redemption(card.id(),card.balance(),card.currency(),BigDecimal.ZERO,due,order.status(),saved.id());
        }
        BigDecimal due=remainingDue(jdbc,orderId,order.total());
        if(due.signum()<=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order is already fully paid");
        BigDecimal available=card.balance().min(due);
        BigDecimal amount=requestedAmount==null?available:requestedAmount;
        if(amount.signum()<=0||amount.compareTo(available)>0||amount.stripTrailingZeros().scale()>java.util.Currency.getInstance(order.currency()).getDefaultFractionDigits())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Gift card redemption must be positive, currency-precise and no greater than the order due or card balance");
        UUID transactionId=UUID.randomUUID();
        jdbc.update("UPDATE gift_cards SET balance=balance-? WHERE id=?",amount,card.id());
        jdbc.update("INSERT INTO gift_card_transactions(id,gift_card_id,user_id,order_id,amount,kind) VALUES (?,?,?,?,?,'REDEEM')",
                transactionId,card.id(),userId,orderId,amount);
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,gift_card_id,created_at,updated_at) "
                        +"VALUES (?,?,?,?,?,'GIFT_CARD','CAPTURED',?,?,now(),now())",
                UUID.randomUUID(),orderId,userId,amount,order.currency(),"giftcard:"+transactionId,card.id());
        BigDecimal remaining=due.subtract(amount);
        String status="PENDING";
        if(remaining.signum()==0){
            if(jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'",orderId)>0){
                status="PAID";
                OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_PAID","Order paid with gift card",true);
                LoyaltyAccounting.earnForOrder(jdbc,userId,orderId,order.total());
            }
        }
        return new Redemption(card.id(),card.balance().subtract(amount),card.currency(),amount,remaining,status,transactionId);
    }

    public static void refund(JdbcTemplate jdbc,UUID giftCardId,UUID userId,UUID orderId,UUID paymentRefundId,BigDecimal amount){
        int changed=jdbc.update("UPDATE gift_cards SET balance=balance+?,expires_at=CASE WHEN expires_at IS NOT NULL AND expires_at<=now() THEN now()+interval '365 days' ELSE expires_at END WHERE id=? AND active",amount,giftCardId);
        if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Stored-value card is unavailable; this refund requires manual review");
        jdbc.update("INSERT INTO gift_card_transactions(id,gift_card_id,user_id,order_id,payment_refund_id,amount,kind) VALUES (?,?,?,?,?,?,'REFUND')",
                UUID.randomUUID(),giftCardId,userId,orderId,paymentRefundId,amount);
    }

    /** Restores stored value after an external checkout is definitively rejected, even if the card was disabled meanwhile. */
    public static void restoreRejectedCheckout(JdbcTemplate jdbc,UUID giftCardId,UUID userId,UUID orderId,
            UUID paymentRefundId,BigDecimal amount){
        int inserted=jdbc.update("INSERT INTO gift_card_transactions(id,gift_card_id,user_id,order_id,payment_refund_id,amount,kind) " +
                        "VALUES (?,?,?,?,?,?,'REFUND') ON CONFLICT (payment_refund_id) WHERE payment_refund_id IS NOT NULL DO NOTHING",
                UUID.randomUUID(),giftCardId,userId,orderId,paymentRefundId,amount);
        if(inserted==0)return;
        int updated=jdbc.update("UPDATE gift_cards SET balance=balance+?,expires_at=CASE WHEN expires_at IS NOT NULL AND expires_at<=now() " +
                "THEN now()+interval '365 days' ELSE expires_at END WHERE id=?",amount,giftCardId);
        if(updated==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Stored-value card is unavailable; checkout cancellation requires review");
    }

    private static BigDecimal remainingDue(JdbcTemplate jdbc,UUID orderId,BigDecimal total){
        BigDecimal captured=jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM payments WHERE order_id=? AND status='CAPTURED'",BigDecimal.class,orderId);
        return total.subtract(captured==null?BigDecimal.ZERO:captured).max(BigDecimal.ZERO);
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("Gift card code hashing is unavailable",failure);}
    }
    private record Card(UUID id,BigDecimal balance,String currency){}
    private record OrderRow(BigDecimal total,String currency,String status){}
    private record PriorRedemption(UUID id,BigDecimal amount){}
    public record Redemption(UUID cardId,BigDecimal balance,String currency,BigDecimal appliedAmount,BigDecimal remainingDue,String orderStatus,UUID transactionId){}
}
