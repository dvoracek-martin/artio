package io.artio.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;
    private final CheckoutFailureCompensation checkoutCompensation;
    private final RestClient stripe = RestClient.create();
    private final ObjectMapper mapper;
    private final String stripeKey;
    private final String webhookSecret;
    private final String storefrontUrl;

    public PaymentController(JdbcTemplate jdbc, InventoryApplicationService inventory, OrderUpdatePublisher orderUpdates,
            CheckoutFailureCompensation checkoutCompensation, ObjectMapper mapper,
            @Value("${artio.payments.stripe-secret-key:}") String stripeKey,
            @Value("${artio.payments.stripe-webhook-secret:}") String webhookSecret,
            @Value("${artio.storefront-url:http://localhost:4200}") String storefrontUrl) {
        this.jdbc=jdbc; this.inventory=inventory; this.orderUpdates=orderUpdates; this.checkoutCompensation=checkoutCompensation; this.mapper=mapper; this.stripeKey=stripeKey; this.webhookSecret=webhookSecret; this.storefrontUrl=storefrontUrl;
    }

    @PostMapping("/orders/{orderId}/checkout-session")
    public CheckoutSession checkoutSession(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        if (stripeKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe is not configured");
        UUID userId=UUID.fromString(auth.getToken().getSubject());
        var rows=jdbc.query("SELECT total,currency,status FROM orders WHERE id=? AND user_id=?",(rs,n)->new OrderRow(rs.getBigDecimal(1),rs.getString(2).trim(),rs.getString(3)),orderId,userId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        OrderRow order=rows.getFirst();
        if(!"PENDING".equals(order.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Order is not awaiting payment");
        Instant now=Instant.now();
        Instant sessionExpiresAt=now.plus(Duration.ofMinutes(31));
        try { inventory.extendReservationsForPayment(orderId,sessionExpiresAt.plus(Duration.ofMinutes(1))); }
        catch(IllegalStateException|io.artio.api.catalog.CatalogNotFoundException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Order stock reservation has expired",e);}
        int scale=java.util.Currency.getInstance(order.currency()).getDefaultFractionDigits();
        long minor=order.total().movePointRight(scale).longValueExact();
        MultiValueMap<String,String> form=new LinkedMultiValueMap<>();
        form.add("mode","payment"); form.add("success_url",storefrontUrl+"/checkout/success?session_id={CHECKOUT_SESSION_ID}"); form.add("cancel_url",storefrontUrl+"/orders");
        form.add("expires_at",Long.toString(sessionExpiresAt.getEpochSecond()));
        form.add("client_reference_id",orderId.toString()); form.add("metadata[order_id]",orderId.toString());
        form.add("line_items[0][price_data][currency]",order.currency().toLowerCase()); form.add("line_items[0][price_data][unit_amount]",Long.toString(minor));
        form.add("line_items[0][price_data][product_data][name]","Artio order "+orderId.toString().substring(0,8)); form.add("line_items[0][quantity]","1");
        Map<?,?> session;
        try { session=stripe.post().uri("https://api.stripe.com/v1/checkout/sessions").headers(h->{h.setBearerAuth(stripeKey);h.set("Idempotency-Key","artio-order-"+orderId);}).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class); }
        catch(org.springframework.web.client.RestClientResponseException e){
            int status=e.getStatusCode().value();
            if(e.getStatusCode().is4xxClientError()&&status!=408&&status!=409&&status!=429)checkoutCompensation.cancelRejectedCheckout(orderId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Payment provider could not create a checkout session");
        }
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Payment provider could not create a checkout session");}
        String sessionId=String.valueOf(session.get("id")); String url=String.valueOf(session.get("url"));
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) VALUES (?,?,?,?,?,'STRIPE','PENDING',?,now(),now()) ON CONFLICT (provider_reference) DO NOTHING",UUID.randomUUID(),orderId,userId,order.total(),order.currency(),sessionId);
        return new CheckoutSession(sessionId,url);
    }

    @PostMapping(value="/stripe/webhook", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public Map<String,String> webhook(@RequestHeader(value="Stripe-Signature",required=false) String signature,@RequestBody String body) {
        if(webhookSecret.isBlank()||!verifySignature(body,signature)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook signature");
        try {
            JsonNode event=mapper.readTree(body); String eventType=event.path("type").asText(); String eventId=event.path("id").asText();
            if(eventId.isBlank()||eventType.isBlank()) throw new IllegalArgumentException("Stripe event identity is required");
            int inserted=jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type) VALUES ('STRIPE',?,?) ON CONFLICT DO NOTHING",eventId,eventType);
            if(inserted==0) return Map.of("status","duplicate");
            JsonNode session=event.path("data").path("object"); String sessionId=session.path("id").asText(); String paymentStatus=session.path("payment_status").asText();
            if("checkout.session.expired".equals(eventType)){
                UUID orderId=lockOrderForSession(sessionId);
                if(orderId==null) { markWebhookProcessed(eventId); return Map.of("status","ignored"); }
                jdbc.update("UPDATE payments SET status='FAILED',updated_at=now() WHERE provider_reference=? AND status='PENDING'",sessionId);
                boolean hasPendingPayment=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE order_id=? AND status='PENDING')",Boolean.class,orderId));
                if(!hasPendingPayment && jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'",orderId)>0){
                    for(var reservation:inventory.reservationsByOrder(orderId))if(reservation.state()==io.artio.inventory.ReservationState.RESERVED)inventory.release(reservation.id());
                    orderUpdates.publish(orderId);
                }
                markWebhookProcessed(eventId);
                return Map.of("status","expired");
            }
            if(!"checkout.session.completed".equals(eventType)) { markWebhookProcessed(eventId); return Map.of("status","ignored"); }
            if(!"paid".equals(paymentStatus)) { markWebhookProcessed(eventId); return Map.of("status","pending"); }
            UUID orderId=lockOrderForSession(sessionId);
            if(orderId==null) { markWebhookProcessed(eventId); return Map.of("status","ignored"); }
            String orderStatus=jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,orderId);
            if("CANCELLED".equals(orderStatus)) { markWebhookProcessed(eventId); return Map.of("status","cancelled"); }
            if(!"PENDING".equals(orderStatus)&&!"PAID".equals(orderStatus)) { markWebhookProcessed(eventId); return Map.of("status","order_not_payable"); }
            jdbc.update("UPDATE payments SET status='CAPTURED',updated_at=now() WHERE provider_reference=? AND status IN ('PENDING','CAPTURED')",sessionId);
            String persistedPaymentStatus=jdbc.queryForObject("SELECT status FROM payments WHERE provider_reference=?",String.class,sessionId);
            if(!"CAPTURED".equals(persistedPaymentStatus)) { markWebhookProcessed(eventId); return Map.of("status","payment_not_payable"); }
            jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'",orderId);
            orderUpdates.publish(orderId);
            jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind,order_id) SELECT gen_random_uuid(),o.user_id,oi.variant_id,'PURCHASE',o.id FROM payments p JOIN orders o ON o.id=p.order_id JOIN order_items oi ON oi.order_id=o.id WHERE p.provider_reference=? AND o.status='PAID' ON CONFLICT DO NOTHING",sessionId);
            jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,created_at) SELECT ?,user_id,'PAYMENT_RECEIVED','Payment received','Payment received for order '||order_id,false,now() FROM payments WHERE provider_reference=? AND NOT EXISTS (SELECT 1 FROM notifications WHERE type='PAYMENT_RECEIVED' AND body='Payment received for order '||payments.order_id)",UUID.randomUUID(),sessionId);
            markWebhookProcessed(eventId);
            return Map.of("status","processed");
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook payload"); }
    }

    private UUID lockOrderForSession(String sessionId) {
        var orders=jdbc.query("SELECT o.id FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.provider_reference=? FOR UPDATE OF o",
                (rs,row)->rs.getObject(1,UUID.class),sessionId);
        return orders.isEmpty()?null:orders.getFirst();
    }

    private void markWebhookProcessed(String eventId) {
        jdbc.update("UPDATE payment_webhook_receipts SET processed_at=now() WHERE provider='STRIPE' AND event_id=?",eventId);
    }

    @PostMapping("/{paymentId}/refund")
    public Map<?,?> refund(@PathVariable UUID paymentId,JwtAuthenticationToken auth) {
        if(!auth.getToken().getClaimAsString("role").equals("ADMIN")&&!auth.getToken().getClaimAsString("role").equals("SUPERADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var rows=jdbc.query("SELECT provider_reference,status FROM payments WHERE id=?",(rs,n)->Map.of("ref",rs.getString(1),"status",rs.getString(2)),paymentId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");
        if(!"CAPTURED".equals(rows.getFirst().get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only captured payments can be refunded");
        String intent=String.valueOf(stripe.get().uri("https://api.stripe.com/v1/checkout/sessions/{id}",rows.getFirst().get("ref")).headers(h->h.setBearerAuth(stripeKey)).retrieve().body(Map.class).get("payment_intent"));
        MultiValueMap<String,String> form=new LinkedMultiValueMap<>();form.add("payment_intent",intent);
        Map<?,?> response=stripe.post().uri("https://api.stripe.com/v1/refunds").headers(h->h.setBearerAuth(stripeKey)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        jdbc.update("UPDATE payments SET status='REFUNDED',updated_at=now() WHERE id=?",paymentId);
        jdbc.update("UPDATE orders SET status='RETURNED' WHERE id=(SELECT order_id FROM payments WHERE id=?)",paymentId);
        return response;
    }

    private boolean verifySignature(String body,String header) {
        if(header==null)return false;
        try { String timestamp=null,expected=null;for(String part:header.split(",")){String[] kv=part.split("=",2);if(kv.length==2&&kv[0].equals("t"))timestamp=kv[1];if(kv.length==2&&kv[0].equals("v1"))expected=kv[1];}
            if(timestamp==null||expected==null||Math.abs(System.currentTimeMillis()/1000-Long.parseLong(timestamp))>300)return false;
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String actual=HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII));
        }catch(Exception e){return false;}
    }
    public record CheckoutSession(String sessionId,String checkoutUrl){}
    private record OrderRow(BigDecimal total,String currency,String status){}
}
