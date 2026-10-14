package io.artio.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.api.orders.OrderTimelineEvents;
import io.artio.api.enterprise.LoyaltyAccounting;
import io.artio.extensions.commerce.PaymentProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ObjectProvider<PaymentProvider> provider;
    private final String extensionProviderId;
    private final RestClient stripe = RestClient.create();
    private final ObjectMapper mapper;
    private final String stripeKey;
    private final String webhookSecret;
    private final String storefrontUrl;

    public PaymentController(JdbcTemplate jdbc, InventoryApplicationService inventory, OrderUpdatePublisher orderUpdates,
            CheckoutFailureCompensation checkoutCompensation, ObjectMapper mapper,
            ObjectProvider<PaymentProvider> provider,
            @Value("${artio.payments.stripe-secret-key:}") String stripeKey,
            @Value("${artio.payments.stripe-webhook-secret:}") String webhookSecret,
            @Value("${artio.payments.extension-plugin-id:}") String extensionProviderId,
            @Value("${artio.storefront-url:http://localhost:4200}") String storefrontUrl) {
        this.jdbc=jdbc; this.inventory=inventory; this.orderUpdates=orderUpdates; this.checkoutCompensation=checkoutCompensation; this.mapper=mapper; this.provider=provider; this.extensionProviderId=extensionProviderId; this.stripeKey=stripeKey; this.webhookSecret=webhookSecret; this.storefrontUrl=storefrontUrl;
    }

    @PostMapping("/orders/{orderId}/checkout-session")
    public CheckoutSession checkoutSession(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension != null) return extensionCheckout(orderId, auth, extension);
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

    private CheckoutSession extensionCheckout(UUID orderId, JwtAuthenticationToken auth, PaymentProvider extension) {
        UUID userId = UUID.fromString(auth.getToken().getSubject());
        var rows = jdbc.query("SELECT total,currency,status FROM orders WHERE id=? AND user_id=?",
                (rs, n) -> new OrderRow(rs.getBigDecimal(1), rs.getString(2).trim(), rs.getString(3)), orderId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        OrderRow order = rows.getFirst();
        if (!"PENDING".equals(order.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not awaiting payment");
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(31));
        try { inventory.extendReservationsForPayment(orderId, expiresAt.plus(Duration.ofMinutes(1))); }
        catch (IllegalStateException | io.artio.api.catalog.CatalogNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order stock reservation has expired", e);
        }
        var capabilities = extension.capabilities();
        if (!capabilities.hostedCheckout()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider does not support hosted checkout");
        String idempotencyKey = "artio-order-" + orderId;
        PaymentProvider.CheckoutResult result;
        try {
            result = extension.createCheckout(new PaymentProvider.CheckoutRequest(orderId.toString(), idempotencyKey,
                    order.total(), order.currency(), storefrontUrl + "/checkout?success=true&orderId=" + orderId + "&session_id={CHECKOUT_SESSION_ID}",
                    storefrontUrl + "/orders", Map.of("orderId", orderId.toString(), "expiresAt", expiresAt.toString())));
        } catch (RuntimeException failure) {
            // Provider timeouts may be ambiguous; preserve the reservation and retry with the same key.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider could not create a checkout session");
        }
        if (result == null || result.providerReference() == null || result.providerReference().isBlank()
                || result.checkoutUrl() == null || result.checkoutUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider returned an invalid checkout session");
        }
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) VALUES (?,?,?,?,?,'EXTENSION','PENDING',?,now(),now()) ON CONFLICT (provider_reference) DO NOTHING",
                UUID.randomUUID(), orderId, userId, order.total(), order.currency(), result.providerReference());
        return new CheckoutSession(result.providerReference(), result.checkoutUrl());
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Transactional
    public Map<String,String> cancelPendingOrder(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        UUID userId=UUID.fromString(auth.getToken().getSubject());
        String status=jdbc.query("SELECT status FROM orders WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->rs.getString(1),orderId,userId)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
        if("CANCELLED".equals(status))return Map.of("status","cancelled");
        if(!"PENDING".equals(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only orders awaiting payment can be cancelled");
        var pending=jdbc.query("SELECT id,provider_reference,method FROM payments WHERE order_id=? AND status='PENDING' FOR UPDATE",
                (rs,n)->new PendingPayment(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),orderId);
        PaymentProvider extension=provider.getIfAvailable();
        for(PendingPayment payment:pending){
            try{
                if(extension!=null){
                    if(!extension.capabilities().cancellation())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment provider cannot cancel this checkout");
                    PaymentProvider.PaymentResult result=extension.cancel(payment.providerReference(),"artio-cancel-"+orderId);
                    if(result==null||!payment.providerReference().equals(result.providerReference()))throw new IllegalStateException("Provider returned invalid cancellation");
                    if(result.state()==PaymentProvider.PaymentState.CAPTURED)throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment was captured and is being reconciled");
                    if(result.state()!=PaymentProvider.PaymentState.CANCELLED&&result.state()!=PaymentProvider.PaymentState.EXPIRED)
                        throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment cancellation is not confirmed");
                }else if("STRIPE".equals(payment.method())&&!stripeKey.isBlank()){
                    Map<?,?> session=stripe.post().uri("https://api.stripe.com/v1/checkout/sessions/{id}/expire",payment.providerReference())
                            .headers(h->h.setBearerAuth(stripeKey)).retrieve().body(Map.class);
                    if(session==null||!payment.providerReference().equals(session.get("id"))||!"expired".equals(session.get("status")))
                        throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment cancellation is not confirmed");
                }else throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment provider cannot cancel this checkout");
                jdbc.update("UPDATE payments SET status='CANCELLED',updated_at=now() WHERE id=? AND status='PENDING'",payment.id());
            }catch(ResponseStatusException known){throw known;}
            catch(RuntimeException failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Payment cancellation was not confirmed; retry after provider reconciliation");}
        }
        if(jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'",orderId)==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Order status changed while cancellation was in progress");
        for(var reservation:inventory.reservationsByOrder(orderId))
            if(reservation.state()==io.artio.inventory.ReservationState.RESERVED)inventory.release(reservation.id());
        OrderTimelineEvents.record(jdbc,orderId,userId,"ORDER_CANCELLED","Order cancelled before payment",true);
        jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,dedupe_key,created_at) VALUES (?,?,?,'Order cancelled',?,false,?,now()) ON CONFLICT (dedupe_key) DO NOTHING",
                UUID.randomUUID(),userId,"ORDER_CANCELLED","Order "+orderId+" was cancelled.","order-cancelled:"+orderId);
        orderUpdates.publish(orderId);
        return Map.of("status","cancelled");
    }

    /** Captures a buyer-approved provider order after a verified ownership and reference check. */
    @PostMapping("/provider/orders/{orderId}/capture")
    @Transactional
    public Map<String, String> captureProviderOrder(@PathVariable UUID orderId,
            @RequestParam("token") String returnedProviderReference, JwtAuthenticationToken auth) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension == null || returnedProviderReference == null || returnedProviderReference.isBlank()
                || returnedProviderReference.length() > 128) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment provider order is unavailable");
        }
        UUID userId = UUID.fromString(auth.getToken().getSubject());
        var references = jdbc.query("SELECT provider_reference,status,amount,currency FROM payments WHERE order_id=? AND user_id=? AND method='EXTENSION'",
                (rs, row) -> new CapturePayment(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4).trim()), orderId, userId);
        if (references.isEmpty() || !java.security.MessageDigest.isEqual(
                references.getFirst().providerReference().getBytes(StandardCharsets.UTF_8), returnedProviderReference.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment provider order is unavailable");
        }
        CapturePayment payment = references.getFirst();
        String providerReference = payment.providerReference();
        String orderStatus = jdbc.query("SELECT status FROM orders WHERE id=? AND user_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), orderId, userId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if ("CANCELLED".equals(orderStatus)) return Map.of("status", "cancelled");
        if ("CAPTURED".equals(payment.status()) || "PAID".equals(orderStatus)) return Map.of("status", "captured");
        if (!"PENDING".equals(orderStatus) || !"PENDING".equals(payment.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not payable");
        }
        PaymentProvider.PaymentResult result;
        try { result = extension.capture(providerReference, "artio-capture-" + orderId); }
        catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment capture is being reconciled"); }
        if (result == null || !providerReference.equals(result.providerReference())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider returned an invalid capture result");
        }
        if (result.state() != PaymentProvider.PaymentState.CAPTURED) return Map.of("status", "pending");
        if (result.amount() == null || payment.amount().compareTo(result.amount()) != 0
                || result.currency() == null || !payment.currency().equalsIgnoreCase(result.currency())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider returned an inconsistent capture amount");
        }
        jdbc.update("UPDATE payments SET status='CAPTURED',updated_at=now() WHERE provider_reference=? AND status='PENDING'", providerReference);
        int changed = jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'", orderId);
        if (changed > 0) {
            OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_PAID","Payment confirmed",true);
            awardLoyalty(orderId);
            orderUpdates.publish(orderId);
            jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind,order_id) SELECT gen_random_uuid(),o.user_id,oi.variant_id,'PURCHASE',o.id FROM orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.id=? ON CONFLICT DO NOTHING", orderId);
            jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,created_at) SELECT ?,user_id,'PAYMENT_RECEIVED','Payment received','Payment received for order '||id,false,now() FROM orders WHERE id=? AND NOT EXISTS (SELECT 1 FROM notifications WHERE type='PAYMENT_RECEIVED' AND body='Payment received for order '||?)", UUID.randomUUID(), orderId, orderId);
        }
        return Map.of("status", "captured");
    }

    @PostMapping(value="/provider/webhook", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public Map<String,String> providerWebhook(@RequestHeader org.springframework.http.HttpHeaders requestHeaders,
            @RequestBody byte[] body) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment provider webhook is unavailable");
        PaymentProvider.WebhookResult event;
        Map<String, String> signedHeaders = new java.util.HashMap<>();
        for (String name : java.util.List.of("provider-signature", "stripe-signature", "paypal-transmission-id",
                "paypal-transmission-time", "paypal-transmission-sig", "paypal-cert-url", "paypal-auth-algo")) {
            String value = requestHeaders.getFirst(name);
            if (value != null && value.length() <= 4096) signedHeaders.put(name, value);
        }
        try { event = extension.verifyWebhook(Map.copyOf(signedHeaders), body); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature"); }
        if (event == null || !event.verified() || event.providerEventId() == null || event.providerEventId().isBlank()
                || event.eventType() == null || event.eventType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature");
        }
        String providerKey = extensionProviderId.isBlank() ? "EXTENSION" : extensionProviderId.substring(0, Math.min(32, extensionProviderId.length()));
        int inserted = jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                providerKey, event.providerEventId(), event.eventType());
        if (inserted == 0) return Map.of("status", "duplicate");
        if (event.providerReference() == null || event.providerReference().isBlank()) {
            markProviderWebhookProcessed(providerKey, event.providerEventId());
            return Map.of("status", "ignored");
        }
        UUID orderId = lockOrderForSession(event.providerReference());
        if (orderId == null) {
            markProviderWebhookProcessed(providerKey, event.providerEventId());
            return Map.of("status", "ignored");
        }
        if (event.state() == PaymentProvider.PaymentState.CAPTURED) {
            String orderStatus = jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId);
            if ("CANCELLED".equals(orderStatus)) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "cancelled");
            }
            if (!"PENDING".equals(orderStatus) && !"PAID".equals(orderStatus)) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "order_not_payable");
            }
            jdbc.update("UPDATE payments SET status='CAPTURED',updated_at=now() WHERE provider_reference=? AND status IN ('PENDING','CAPTURED')", event.providerReference());
            String paymentStatus=jdbc.queryForObject("SELECT status FROM payments WHERE provider_reference=?",String.class,event.providerReference());
            if(!"CAPTURED".equals(paymentStatus)) {
                markProviderWebhookProcessed(providerKey,event.providerEventId());
                return Map.of("status","payment_not_payable");
            }
            int paid = jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'", orderId);
            if (paid > 0) { OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_PAID","Payment confirmed",true); awardLoyalty(orderId); }
            orderUpdates.publish(orderId);
            jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind,order_id) SELECT gen_random_uuid(),o.user_id,oi.variant_id,'PURCHASE',o.id FROM orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.id=? ON CONFLICT DO NOTHING",orderId);
            jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,created_at) SELECT ?,user_id,'PAYMENT_RECEIVED','Payment received','Payment received for order '||id,false,now() FROM orders WHERE id=? AND NOT EXISTS (SELECT 1 FROM notifications WHERE type='PAYMENT_RECEIVED' AND body='Payment received for order '||?)",UUID.randomUUID(),orderId,orderId);
        } else if (event.state() == PaymentProvider.PaymentState.EXPIRED || event.state() == PaymentProvider.PaymentState.FAILED
                || event.state() == PaymentProvider.PaymentState.CANCELLED) {
            jdbc.update("UPDATE payments SET status='FAILED',updated_at=now() WHERE provider_reference=? AND status='PENDING'", event.providerReference());
            boolean hasPending = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE order_id=? AND status='PENDING')", Boolean.class, orderId));
            if (!hasPending && jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId) > 0) {
                OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_CANCELLED","Order cancelled because payment did not complete",true);
                for (var reservation : inventory.reservationsByOrder(orderId))
                    if (reservation.state() == io.artio.inventory.ReservationState.RESERVED) inventory.release(reservation.id());
                orderUpdates.publish(orderId);
            }
        }
        markProviderWebhookProcessed(providerKey, event.providerEventId());
        return Map.of("status", "processed");
    }

    private void awardLoyalty(UUID orderId) {
        jdbc.query("SELECT user_id,total FROM orders WHERE id=?",(rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getBigDecimal(2)},orderId)
                .stream().findFirst().ifPresent(order->LoyaltyAccounting.earnForOrder(jdbc,(UUID)order[0],orderId,(BigDecimal)order[1]));
    }

    private void markProviderWebhookProcessed(String providerKey, String eventId) {
        jdbc.update("UPDATE payment_webhook_receipts SET processed_at=now() WHERE provider=? AND event_id=?", providerKey, eventId);
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
                    OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_CANCELLED","Order cancelled because payment expired",true);
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
            int paid=jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'",orderId);
            if(paid>0){OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_PAID","Payment confirmed",true);awardLoyalty(orderId);}
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
    @Transactional
    public ResponseEntity<RefundView> refund(@PathVariable UUID paymentId, JwtAuthenticationToken auth,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody RefundCommand command) {
        String role = auth.getToken().getClaimAsString("role");
        if (role == null || !Set.of("ADMIN", "SUPERADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key header is required");
        var payments = jdbc.query("SELECT provider_reference,status,amount,refunded_amount,currency,method,order_id FROM payments WHERE id=? FOR UPDATE",
                (rs,n)->new RefundRow(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5).trim(),rs.getString(6),rs.getObject(7,UUID.class)),paymentId);
        if (payments.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");
        RefundRow payment = payments.getFirst();
        var priorRows = jdbc.query("SELECT id,amount,status,provider_reference FROM payment_refunds WHERE payment_id=? AND idempotency_key=?",
                (rs,n)->new RefundRecord(rs.getObject("id",UUID.class),rs.getBigDecimal("amount"),rs.getString("status"),rs.getString("provider_reference")),paymentId,idempotencyKey);
        RefundRecord prior = priorRows.isEmpty() ? null : priorRows.getFirst();
        BigDecimal remaining = payment.amount().subtract(payment.refundedAmount());
        if (prior != null && prior.status().equals("COMPLETED")) {
            if (command != null && command.amount() != null && prior.amount().compareTo(command.amount()) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"This idempotency key was already used for a different refund amount");
            return ResponseEntity.ok(new RefundView(prior.id(), paymentId, prior.amount(), payment.currency(), payment.refundedAmount(), remaining,
                    prior.providerReference(), "COMPLETED"));
        }
        if (!Set.of("CAPTURED", "PARTIALLY_REFUNDED").contains(payment.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only captured payments can be refunded");
        BigDecimal refundAmount = command == null || command.amount() == null ? remaining : command.amount();
        int fractionDigits = Currency.getInstance(payment.currency()).getDefaultFractionDigits();
        if (fractionDigits < 0 || refundAmount.signum() <= 0 || refundAmount.compareTo(remaining) > 0 || refundAmount.stripTrailingZeros().scale() > fractionDigits)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Refund amount must be positive, use the currency's minor unit, and not exceed the remaining captured amount");
        if (prior != null && prior.amount().compareTo(refundAmount) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This idempotency key was already used for a different refund amount");
        UUID refundId = prior == null ? UUID.nameUUIDFromBytes((paymentId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8)) : prior.id();
        if (prior == null) jdbc.update("INSERT INTO payment_refunds(id,payment_id,idempotency_key,amount,currency,status) VALUES (?,?,?,?,?,'PROCESSING')",
                refundId,paymentId,idempotencyKey,refundAmount,payment.currency());
        else if (prior.status().equals("PROCESSING")) throw new ResponseStatusException(HttpStatus.CONFLICT,"This refund is already processing");
        else jdbc.update("UPDATE payment_refunds SET status='PROCESSING',updated_at=now() WHERE id=? AND status='FAILED'",refundId);

        String providerReference;
        try {
            PaymentProvider extension = provider.getIfAvailable();
            if (extension != null) {
                if (!extension.capabilities().refunds()) throw new IllegalStateException("Provider does not support refunds");
                PaymentProvider.PaymentResult result = extension.refund(new PaymentProvider.RefundRequest(payment.providerReference(),
                        "artio-refund-" + refundId, refundAmount, payment.currency()));
                if (result == null || result.state() != PaymentProvider.PaymentState.REFUNDED
                        || result.amount() == null || result.amount().compareTo(refundAmount) != 0
                        || !payment.currency().equalsIgnoreCase(result.currency())
                        || result.providerReference() == null || result.providerReference().isBlank())
                    throw new IllegalStateException("Payment provider did not confirm the requested refund");
                providerReference = result.providerReference();
            } else {
                if (stripeKey.isBlank()) throw new IllegalStateException("No payment provider is configured");
                Map<?,?> session = stripe.get().uri("https://api.stripe.com/v1/checkout/sessions/{id}",payment.providerReference())
                        .headers(h->h.setBearerAuth(stripeKey)).retrieve().body(Map.class);
                Object intentValue = session == null ? null : session.get("payment_intent");
                if (!(intentValue instanceof String intent) || !intent.startsWith("pi_")) throw new IllegalStateException("Payment has no refundable intent");
                MultiValueMap<String,String> form=new LinkedMultiValueMap<>();
                form.add("payment_intent",intent);
                long minor = refundAmount.movePointRight(fractionDigits).longValueExact();
                form.add("amount",Long.toString(minor));
                Map<?,?> result=stripe.post().uri("https://api.stripe.com/v1/refunds")
                        .headers(h->{h.setBearerAuth(stripeKey);h.set("Idempotency-Key","artio-refund-"+refundId);})
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
                if (result == null || !"succeeded".equals(result.get("status")) || !(result.get("id") instanceof String reference))
                    throw new IllegalStateException("Stripe did not confirm the requested refund");
                providerReference = reference;
            }
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Payment provider did not confirm the refund; retry with the same Idempotency-Key");
        }
        BigDecimal totalRefunded = payment.refundedAmount().add(refundAmount);
        String paymentStatus = totalRefunded.compareTo(payment.amount()) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
        jdbc.update("UPDATE payments SET refunded_amount=?,status=?,updated_at=now() WHERE id=?",totalRefunded,paymentStatus,paymentId);
        jdbc.update("UPDATE payment_refunds SET status='COMPLETED',provider_reference=?,updated_at=now() WHERE id=?",providerReference,refundId);
        LoyaltyAccounting.reverseForRefund(jdbc,refundId,payment.orderId());
        OrderTimelineEvents.record(jdbc,payment.orderId(),UUID.fromString(auth.getToken().getSubject()),"PAYMENT_REFUNDED",
                "Refund processed: "+refundAmount.toPlainString()+" "+payment.currency(),true);
        return ResponseEntity.ok(new RefundView(refundId,paymentId,refundAmount,payment.currency(),totalRefunded,
                payment.amount().subtract(totalRefunded),providerReference,"COMPLETED"));
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
    private record RefundRow(String providerReference,String status,BigDecimal amount,BigDecimal refundedAmount,String currency,String method,UUID orderId){}
    private record RefundRecord(UUID id,BigDecimal amount,String status,String providerReference){}
    public record RefundCommand(BigDecimal amount){}
    public record RefundView(UUID id,UUID paymentId,BigDecimal amount,String currency,BigDecimal totalRefunded,
                             BigDecimal remainingAmount,String providerReference,String status){}
    private record OrderRow(BigDecimal total,String currency,String status){}
    private record PendingPayment(UUID id,String providerReference,String method){}
    private record CapturePayment(String providerReference, String status, BigDecimal amount, String currency) { }
}
