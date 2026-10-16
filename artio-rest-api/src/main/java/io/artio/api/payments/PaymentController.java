package io.artio.api.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.api.orders.OrderTimelineEvents;
import io.artio.api.enterprise.LoyaltyAccounting;
import io.artio.api.enterprise.GiftCardAccounting;
import io.artio.extensions.commerce.PaymentProvider;
import io.artio.extensions.ExtensionRuntimePort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private static final ObjectMapper OWNER_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> OWNER_SCOPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;
    private final ObjectProvider<PaymentProvider> provider;
    private final CheckoutFailureCompensation checkoutFailureCompensation;
    private final String extensionProviderId;
    private final String storefrontUrl;

    public PaymentController(JdbcTemplate jdbc, InventoryApplicationService inventory, OrderUpdatePublisher orderUpdates,
            ObjectProvider<PaymentProvider> provider, CheckoutFailureCompensation checkoutFailureCompensation,
            @Value("${artio.payments.extension-plugin-id:}") String extensionProviderId,
            @Value("${artio.storefront-url:http://localhost:4200}") String storefrontUrl) {
        this.jdbc=jdbc; this.inventory=inventory; this.orderUpdates=orderUpdates; this.provider=provider; this.checkoutFailureCompensation=checkoutFailureCompensation; this.extensionProviderId=extensionProviderId; this.storefrontUrl=storefrontUrl;
    }

    @PostMapping("/orders/{orderId}/checkout-session")
    @Operation(summary = "Create a hosted payment checkout session", description = "Creates checkout through the configured payment provider plugin. Definitive no-session rejection cancels the pending order and restores stock and gift-card tender; ambiguous outcomes remain retryable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checkout session created"),
            @ApiResponse(responseCode = "422", description = "Provider definitively rejected checkout; pending order was cancelled",
                    content = @Content(schema = @Schema(implementation = io.artio.api.error.ApiError.class))),
            @ApiResponse(responseCode = "502", description = "Provider outcome is ambiguous; retry with the same checkout identity",
                    content = @Content(schema = @Schema(implementation = io.artio.api.error.ApiError.class))),
            @ApiResponse(responseCode = "503", description = "No payment provider is configured or enabled",
                    content = @Content(schema = @Schema(implementation = io.artio.api.error.ApiError.class)))
    })
    public CheckoutSession checkoutSession(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No payment provider is configured");
        return extensionCheckout(orderId, auth, extension);
    }

    private CheckoutSession extensionCheckout(UUID orderId, JwtAuthenticationToken auth, PaymentProvider extension) {
        UUID userId = UUID.fromString(auth.getToken().getSubject());
        var rows = jdbc.query("SELECT o.total-COALESCE((SELECT sum(p.amount) FROM payments p WHERE p.order_id=o.id AND p.status='CAPTURED'),0),o.currency,o.status,o.shipping_country FROM orders o WHERE o.id=? AND o.user_id=?",
                (rs, n) -> new OrderRow(rs.getBigDecimal(1), rs.getString(2).trim(), rs.getString(3), rs.getString(4)), orderId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        OrderRow order = rows.getFirst();
        if (!"PENDING".equals(order.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not awaiting payment");
        if(order.total().signum()<=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order has no remaining payment due");
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(31));
        try { inventory.extendReservationsForPayment(orderId, expiresAt.plus(Duration.ofMinutes(1))); }
        catch (IllegalStateException | io.artio.api.catalog.CatalogNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order stock reservation has expired", e);
        }
        var capabilities = extension.capabilities();
        if (!capabilities.hostedCheckout()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider does not support hosted checkout");
        String idempotencyKey = "artio-order-" + orderId;
        PaymentProvider.CheckoutResult result;
        PaymentProvider.OwnedResult<PaymentProvider.CheckoutResult> owned;
        try {
            owned = extension.createCheckoutOwned(new PaymentProvider.CheckoutRequest(orderId.toString(), idempotencyKey,
                    order.total(), order.currency(), storefrontUrl + "/checkout?success=true&orderId=" + orderId + "&session_id={CHECKOUT_SESSION_ID}",
                    storefrontUrl + "/orders", Map.of("orderId", orderId.toString(), "expiresAt", expiresAt.toString(),
                            "country", order.country() == null ? "" : order.country().trim())));
            result = owned.result();
        } catch (PaymentProvider.CheckoutRejectedException rejected) {
            checkoutFailureCompensation.cancelRejectedCheckout(orderId);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Payment provider rejected checkout; order and stock reservation were cancelled");
        } catch (RuntimeException failure) {
            // Provider timeouts may be ambiguous; preserve the reservation and retry with the same key.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider could not create a checkout session");
        }
        if (result == null || result.providerReference() == null || result.providerReference().isBlank()
                || result.checkoutUrl() == null || result.checkoutUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider returned an invalid checkout session");
        }
        PaymentProvider.ProviderOwner owner = owned.owner();
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope,created_at,updated_at) VALUES (?,?,?,?,?,'EXTENSION','PENDING',?,?,?,?,?,?::jsonb,now(),now()) ON CONFLICT (provider_reference) DO NOTHING",
                UUID.randomUUID(), orderId, userId, order.total(), order.currency(), result.providerReference(),
                owner == null ? null : owner.instanceId(), owner == null ? null : owner.releaseId(),
                owner == null ? null : owner.configurationGeneration(), owner == null ? null : owner.externalAccountFingerprint(),
                owner == null ? "{}" : writeJson(owner.scope()));
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
        var pending=jdbc.query("SELECT id,provider_reference,method,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope::text FROM payments WHERE order_id=? AND status='PENDING' FOR UPDATE",
                (rs,n)->new PendingPayment(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),providerOwner(rs,4)),orderId);
        PaymentProvider extension=provider.getIfAvailable();
        for(PendingPayment payment:pending){
            try{
                if(extension!=null){
                    if(payment.owner()==null&&!extension.capabilities().cancellation())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment provider cannot cancel this checkout");
                    PaymentProvider.PaymentResult result=extension.cancel(payment.owner(),payment.providerReference(),"artio-cancel-"+payment.id()).result();
                    if(result==null||!payment.providerReference().equals(result.providerReference()))throw new IllegalStateException("Provider returned invalid cancellation");
                    if(result.state()==PaymentProvider.PaymentState.CAPTURED)throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment was captured and is being reconciled");
                    if(result.state()!=PaymentProvider.PaymentState.CANCELLED&&result.state()!=PaymentProvider.PaymentState.EXPIRED)
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
        var references = jdbc.query("SELECT provider_reference,status,amount,currency,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope::text,id FROM payments WHERE order_id=? AND user_id=? AND method='EXTENSION'",
                (rs, row) -> new CapturePayment(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4).trim(), providerOwner(rs,5), rs.getObject(10,UUID.class)), orderId, userId);
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
        try { result = extension.capture(payment.owner(), providerReference, "artio-capture-" + payment.id()).result(); }
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

    @PostMapping(value={"/provider/webhook", "/provider/bindings/{bindingId}/webhook"}, consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public Map<String,String> providerWebhook(@RequestHeader org.springframework.http.HttpHeaders requestHeaders,
            @RequestBody byte[] body, @PathVariable(required = false) UUID bindingId) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment provider webhook is unavailable");
        PaymentProvider.ProviderOwner webhookOwner = bindingId == null ? null : webhookOwner(bindingId);
        if (webhookOwner == null && bindingId == null && extensionProviderId.isBlank()
                && extension.capabilities().webhooks()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Use the binding-addressed webhook URL for a v2 payment provider");
        }
        if (webhookOwner == null && !extension.capabilities().webhooks())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment provider webhook is unavailable");
        PaymentProvider.WebhookResult event;
        Map<String, String> signedHeaders = new java.util.HashMap<>();
        for (String name : java.util.List.of("provider-signature", "stripe-signature", "paypal-transmission-id",
                "paypal-transmission-time", "paypal-transmission-sig", "paypal-cert-url", "paypal-auth-algo")) {
            String value = requestHeaders.getFirst(name);
            if (value != null && value.length() <= 4096) signedHeaders.put(name, value);
        }
        try {
            event = webhookOwner == null ? extension.verifyWebhook(Map.copyOf(signedHeaders), body)
                    : extension.verifyWebhook(webhookOwner, Map.copyOf(signedHeaders), body,
                            UUID.nameUUIDFromBytes((bindingId + ":" + sha256(body)).getBytes(StandardCharsets.UTF_8)).toString()).result();
        }
        catch (ExtensionRuntimePort.ExtensionCallException rejected) {
            if (switch (rejected.statusCode()) { case 400, 401, 403, 422 -> true; default -> false; })
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider could not verify the webhook; retry delivery");
        } catch (ExtensionRuntimePort.ExtensionTimeoutException | ExtensionRuntimePort.ExtensionUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider could not verify the webhook; retry delivery");
        } catch (ExtensionRuntimePort.ExtensionProtocolException invalidResponse) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider returned an invalid webhook response");
        } catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature"); }
        if (event == null || !event.verified() || event.providerEventId() == null || event.providerEventId().isBlank()
                || event.eventType() == null || event.eventType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature");
        }
        String providerKey = webhookOwner == null
                ? extensionProviderId.isBlank() ? "EXTENSION" : extensionProviderId.substring(0, Math.min(32, extensionProviderId.length()))
                : webhookOwner.instanceId().toString().replace("-", "");
        int inserted = jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                providerKey, event.providerEventId(), event.eventType());
        if (inserted == 0) return Map.of("status", "duplicate");
        if (event.providerReference() == null || event.providerReference().isBlank()) {
            markProviderWebhookProcessed(providerKey, event.providerEventId());
            return Map.of("status", "ignored");
        }
        if (webhookOwner != null) {
            UUID paymentOwner = jdbc.query("SELECT provider_instance_id FROM payments WHERE provider_reference=? AND method='EXTENSION'",
                    (rs, row) -> rs.getObject(1, UUID.class), event.providerReference()).stream().findFirst().orElse(null);
            if (paymentOwner == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment checkout is not persisted yet; retry the provider webhook");
            if (!webhookOwner.instanceId().equals(paymentOwner)) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "payment_not_found");
            }
        }
        UUID orderId = lockOrderForSession(event.providerReference());
        if (orderId == null) {
            if (webhookOwner != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment order is not available yet; retry the provider webhook");
            markProviderWebhookProcessed(providerKey, event.providerEventId());
            return Map.of("status", "ignored");
        }
        if (event.state() == PaymentProvider.PaymentState.CAPTURED) {
            var settlements = jdbc.query("SELECT amount,currency,provider_instance_id FROM payments WHERE provider_reference=? AND method='EXTENSION'",
                    (rs, row) -> new PaymentSettlement(rs.getBigDecimal("amount"), rs.getString("currency").trim(), rs.getObject("provider_instance_id", UUID.class)),
                    event.providerReference());
            if (settlements.isEmpty()) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "payment_not_found");
            }
            var settlement = settlements.getFirst();
            if (event.amount() == null || event.currency() == null
                    || settlement.amount().compareTo(event.amount()) != 0
                    || !settlement.currency().equalsIgnoreCase(event.currency())
                    || webhookOwner != null && !webhookOwner.instanceId().equals(settlement.instanceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider settlement amount or currency does not match the payment");
            }
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

    private PaymentProvider.ProviderOwner webhookOwner(UUID bindingId) {
        return jdbc.query("SELECT i.instance_id,i.release_id,i.configuration_generation,i.external_account_fingerprint,b.scope::text "
                        + "FROM extension_capability_bindings b JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                        + "JOIN extension_plugin_v2_releases r ON r.release_id=i.release_id "
                        + "WHERE b.binding_id=? AND b.capability_id='payment.provider.v1' AND b.operation_name='verifyWebhook'",
                (rs, row) -> providerOwner(rs, 1), bindingId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment webhook binding was not found"));
    }

    private static String sha256(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private void markProviderWebhookProcessed(String providerKey, String eventId) {
        jdbc.update("UPDATE payment_webhook_receipts SET processed_at=now() WHERE provider=? AND event_id=?", providerKey, eventId);
    }

    private UUID lockOrderForSession(String sessionId) {
        var orders=jdbc.query("SELECT o.id FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.provider_reference=? FOR UPDATE OF o",
                (rs,row)->rs.getObject(1,UUID.class),sessionId);
        return orders.isEmpty()?null:orders.getFirst();
    }

    @PostMapping("/{paymentId}/refund")
    @Transactional
    public ResponseEntity<RefundView> refund(@PathVariable UUID paymentId, JwtAuthenticationToken auth,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody RefundCommand command) {
        String role = auth.getToken().getClaimAsString("role");
        if (role == null || !Set.of("ADMIN", "SUPERADMIN").contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key header is required");
        var payments = jdbc.query("SELECT provider_reference,status,amount,refunded_amount,currency,method,order_id,user_id,gift_card_id,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope::text FROM payments WHERE id=? FOR UPDATE",
                (rs,n)->new RefundRow(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5).trim(),rs.getString(6),rs.getObject(7,UUID.class),rs.getObject(8,UUID.class),rs.getObject(9,UUID.class),providerOwner(rs,10)),paymentId);
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
            if ("GIFT_CARD".equals(payment.method())) {
                if(payment.giftCardId()==null)throw new IllegalStateException("Stored-value payment has no card ledger");
                GiftCardAccounting.refund(jdbc,payment.giftCardId(),payment.userId(),payment.orderId(),refundId,refundAmount);
                providerReference="giftcard-refund:"+refundId;
            } else if (extension != null) {
                if (payment.owner()==null&&!extension.capabilities().refunds()) throw new IllegalStateException("Provider does not support refunds");
                PaymentProvider.PaymentResult result = extension.refund(payment.owner(), new PaymentProvider.RefundRequest(payment.providerReference(),
                        "artio-refund-" + refundId, refundAmount, payment.currency())).result();
                if (result == null || result.state() != PaymentProvider.PaymentState.REFUNDED
                        || result.amount() == null || result.amount().compareTo(refundAmount) != 0
                        || !payment.currency().equalsIgnoreCase(result.currency())
                        || result.providerReference() == null || result.providerReference().isBlank())
                    throw new IllegalStateException("Payment provider did not confirm the requested refund");
                providerReference = result.providerReference();
            } else throw new IllegalStateException("No payment provider is configured");
        } catch (RuntimeException failure) {
            if ("GIFT_CARD".equals(payment.method()) && failure instanceof ResponseStatusException responseStatus) throw responseStatus;
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

    public record CheckoutSession(String sessionId,String checkoutUrl){}
    private static PaymentProvider.ProviderOwner providerOwner(ResultSet rs, int column) throws SQLException {
        UUID instanceId = rs.getObject(column, UUID.class);
        if (instanceId == null) return null;
        Number releaseValue = (Number) rs.getObject(column + 1);
        Long releaseId = releaseValue == null ? null : releaseValue.longValue();
        int generation = rs.getInt(column + 2);
        String fingerprint = rs.getString(column + 3);
        String scopeJson = rs.getString(column + 4);
        try {
            return new PaymentProvider.ProviderOwner(instanceId, releaseId, generation, fingerprint == null ? null : fingerprint.trim(),
                    scopeJson == null ? Map.of() : OWNER_MAPPER.readValue(scopeJson, OWNER_SCOPE));
        } catch (Exception invalid) { throw new SQLException("Persisted provider scope is invalid", invalid); }
    }
    private static String writeJson(Object value) {
        try { return OWNER_MAPPER.writeValueAsString(value); } catch (Exception invalid) { throw new IllegalStateException("Provider owner scope could not be serialized", invalid); }
    }
    private record RefundRow(String providerReference,String status,BigDecimal amount,BigDecimal refundedAmount,String currency,String method,UUID orderId,UUID userId,UUID giftCardId,PaymentProvider.ProviderOwner owner){}
    private record RefundRecord(UUID id,BigDecimal amount,String status,String providerReference){}
    private record PaymentSettlement(BigDecimal amount,String currency,UUID instanceId){}
    public record RefundCommand(BigDecimal amount){}
    public record RefundView(UUID id,UUID paymentId,BigDecimal amount,String currency,BigDecimal totalRefunded,
                             BigDecimal remainingAmount,String providerReference,String status){}
    private record OrderRow(BigDecimal total,String currency,String status,String country){}
    private record PendingPayment(UUID id,String providerReference,String method,PaymentProvider.ProviderOwner owner){}
    private record CapturePayment(String providerReference, String status, BigDecimal amount, String currency, PaymentProvider.ProviderOwner owner, UUID id) { }
}
