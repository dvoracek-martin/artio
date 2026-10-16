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
@RequestMapping("/api/v1/payments")
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
    public CheckoutSession checkoutSession(@PathVariable UUID orderId, @RequestBody(required = false) PaymentSelection request,
            JwtAuthenticationToken auth) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No payment provider is configured");
        return extensionCheckout(orderId, auth, extension, request);
    }

    @PostMapping("/methods")
    @Operation(summary = "List eligible payment methods", description = "Aggregates safe method discovery from eligible payment providers for the requested market and currency.")
    public PaymentMethodDiscoveryView paymentMethods(@RequestBody PaymentMethodDiscoveryRequest request) {
        PaymentProvider extension = provider.getIfAvailable();
        if (extension == null) return new PaymentMethodDiscoveryView(java.util.List.of(), java.util.List.of(), false);
        if (request.country() == null || request.currency() == null || request.amount() != null && request.amount().signum() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment discovery requires a valid country, currency and amount");
        String normalizedCountry = request.country().trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedCurrency = request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        BigDecimal amount = request.amount();
        PaymentProvider.PaymentMethodDiscovery discovery = extension.discoverMethods(
                new PaymentProvider.PaymentDiscoveryRequest(normalizedCountry, normalizedCurrency, amount));
        jdbc.update("DELETE FROM payment_method_references WHERE expires_at < now()");
        java.util.List<PaymentMethodOption> options = discovery.methods().stream().map(method -> {
            UUID reference = UUID.randomUUID();
            jdbc.update("INSERT INTO payment_method_references(reference_id,method_id,provider_plugin_id,provider_instance_id,provider_release_id,configuration_generation,country,currency,amount,expires_at) VALUES (?,?,?,?,?,?,?,?,?,now()+interval '10 minutes')",
                    reference, method.methodId(), method.providerPluginId(), method.providerInstanceId(), method.releaseId(),
                    method.configurationGeneration(), normalizedCountry, normalizedCurrency, amount);
            return new PaymentMethodOption(reference.toString(), method.displayName());
        }).toList();
        return new PaymentMethodDiscoveryView(options, discovery.failures(), discovery.partialFailure());
    }

    private CheckoutSession extensionCheckout(UUID orderId, JwtAuthenticationToken auth, PaymentProvider extension,
            PaymentSelection selection) {
        UUID userId = UUID.fromString(auth.getToken().getSubject());
        var rows = jdbc.query("SELECT o.total-COALESCE((SELECT sum(COALESCE(p.captured_amount,p.amount)) FROM payments p WHERE p.order_id=o.id AND p.status='CAPTURED'),0),o.currency,o.status,o.shipping_country FROM orders o WHERE o.id=? AND o.user_id=?",
                (rs, n) -> new OrderRow(rs.getBigDecimal(1), rs.getString(2).trim(), rs.getString(3), rs.getString(4)), orderId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        OrderRow order = rows.getFirst();
        if (!"PENDING".equals(order.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not awaiting payment");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE order_id=? AND status='CAPTURE_REVIEW')", Boolean.class, orderId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A captured payment needs operator reconciliation before another checkout can start");
        if(order.total().signum()<=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order has no remaining payment due");
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(31));
        try { inventory.extendReservationsForPayment(orderId, expiresAt.plus(Duration.ofMinutes(1))); }
        catch (IllegalStateException | io.artio.api.catalog.CatalogNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order stock reservation has expired", e);
        }
        var capabilities = extension.capabilities();
        if (!capabilities.hostedCheckout()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider does not support hosted checkout");
        PaymentProvider.PaymentMethodDiscovery discovery = extension.discoverMethods(new PaymentProvider.PaymentDiscoveryRequest(
                order.country() == null ? "" : order.country().trim().toUpperCase(java.util.Locale.ROOT),
                order.currency().toUpperCase(java.util.Locale.ROOT), order.total()));
        PaymentProvider.AvailablePaymentMethod selectedMethod = null;
        if (selection != null && selection.methodReference() != null && !selection.methodReference().isBlank()) {
            UUID referenceId;
            try { referenceId = UUID.fromString(selection.methodReference()); }
            catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment method reference"); }
            var references = jdbc.query("SELECT method_id,provider_plugin_id,provider_instance_id,provider_release_id,configuration_generation,country,currency,amount FROM payment_method_references WHERE reference_id=? AND expires_at>now()",
                    (rs, n) -> new PaymentMethodReference(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class),
                            (Long) rs.getObject(4), rs.getInt(5), rs.getString(6).trim(), rs.getString(7).trim(), rs.getBigDecimal(8)), referenceId);
            if (references.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment method reference expired; refresh available methods");
            PaymentMethodReference reference = references.getFirst();
            String country = order.country() == null ? "" : order.country().trim().toUpperCase(java.util.Locale.ROOT);
            if (!reference.country().equals(country) || !reference.currency().equals(order.currency().toUpperCase(java.util.Locale.ROOT))
                    || reference.amount() != null && reference.amount().compareTo(order.total()) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment method reference does not match this order");
            selectedMethod = discovery.methods().stream().filter(candidate -> candidate.methodId().equals(reference.methodId())
                    && candidate.providerPluginId().equals(reference.pluginId())
                    && java.util.Objects.equals(candidate.providerInstanceId(), reference.instanceId())
                    && java.util.Objects.equals(candidate.releaseId(), reference.releaseId())
                    && candidate.configurationGeneration() == reference.configurationGeneration()).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Selected payment method is no longer available"));
        }
        if (selectedMethod == null && selection != null && selection.methodReference() != null && !selection.methodReference().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected payment method is no longer available");
        if (selectedMethod == null && discovery.methods().size() == 1) selectedMethod = discovery.methods().getFirst();
        if (discovery.methods().size() > 1 && selectedMethod == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a payment method before starting checkout");
        if (selectedMethod != null && !selectedMethod.hostedCheckout())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected payment method cannot create hosted checkout");
        if (selectedMethod == null && !discovery.failures().isEmpty() && discovery.methods().isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment method discovery is temporarily unavailable");
        String selectedProviderIdentity = selectedMethod == null ? "default"
                : selectedMethod.providerInstanceId() == null ? selectedMethod.providerPluginId()
                : selectedMethod.providerInstanceId().toString();
        String selectedMethodIdentity = selectedMethod == null ? "default" : selectedMethod.methodId();
        String attemptDiscriminator = UUID.nameUUIDFromBytes((selectedProviderIdentity + ":" + selectedMethodIdentity)
                .getBytes(StandardCharsets.UTF_8)).toString();
        String idempotencyKey = "artio-order-" + orderId + "-" + attemptDiscriminator;
        UUID paymentAttemptId = UUID.nameUUIDFromBytes((orderId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        String providerPluginId = selectedMethod == null
                ? extensionProviderId.isBlank() ? "legacy-extension" : extensionProviderId : selectedMethod.providerPluginId();
        UUID selectedInstanceId = selectedMethod == null ? null : selectedMethod.providerInstanceId();
        Long selectedReleaseId = selectedMethod == null ? null : selectedMethod.releaseId();
        int selectedGeneration = selectedMethod == null ? 0 : selectedMethod.configurationGeneration();
        String selectedAccountFingerprint = selectedInstanceId == null ? null : jdbc.query(
                "SELECT external_account_fingerprint FROM extension_plugin_instances WHERE instance_id=? AND release_id=? AND configuration_generation=?",
                (rs, row) -> rs.getString(1) == null ? null : rs.getString(1).trim(), selectedInstanceId, selectedReleaseId, selectedGeneration)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Selected payment provider instance changed; refresh available methods"));
        Map<String, String> selectedScope = Map.of("country", order.country() == null ? "" : order.country().trim().toUpperCase(java.util.Locale.ROOT),
                "currency", order.currency().toUpperCase(java.util.Locale.ROOT));
        String requestFingerprint = sha256((orderId + ":" + providerPluginId + ":" + selectedInstanceId + ":"
                + selectedReleaseId + ":" + selectedGeneration + ":" + selectedAccountFingerprint + ":" + selectedMethodIdentity + ":"
                + order.total().toPlainString() + ":" + order.currency()).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO payment_attempts(attempt_id,order_id,payment_obligation_id,provider_plugin_id,provider_instance_id,provider_release_id,configuration_generation,provider_account_fingerprint,provider_scope,payment_method_id,idempotency_key,request_fingerprint,amount,currency,status,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?, ?,?::jsonb,?,?,?,?,?,'CREATING',?) ON CONFLICT(order_id,idempotency_key) DO NOTHING",
                paymentAttemptId, orderId, orderId, providerPluginId, selectedInstanceId, selectedReleaseId, selectedGeneration,
                selectedAccountFingerprint, writeJson(selectedScope), selectedMethod == null ? null : selectedMethod.methodId(),
                idempotencyKey, requestFingerprint, order.total(), order.currency(), java.sql.Timestamp.from(expiresAt));
        PaymentAttempt durableAttempt = jdbc.query("SELECT attempt_id,request_fingerprint,status,provider_reference,checkout_url FROM payment_attempts WHERE order_id=? AND idempotency_key=?",
                (rs, row) -> new PaymentAttempt(rs.getObject("attempt_id", UUID.class), rs.getString("request_fingerprint"),
                        rs.getString("status"), rs.getString("provider_reference"), rs.getString("checkout_url")), orderId, idempotencyKey)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Payment attempt could not be persisted"));
        if (!durableAttempt.id().equals(paymentAttemptId) || !durableAttempt.requestFingerprint().equals(requestFingerprint))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A different payment attempt already owns this checkout identity");
        if ("FAILED".equals(durableAttempt.status()) || "CANCELLED".equals(durableAttempt.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment attempt has already been closed");
        jdbc.update("UPDATE payment_attempts SET status='CREATING',updated_at=now() WHERE attempt_id=? AND status IN ('CREATING','UNKNOWN')",
                paymentAttemptId);
        PaymentProvider.CheckoutResult result;
        PaymentProvider.OwnedResult<PaymentProvider.CheckoutResult> owned;
        try {
            owned = extension.createCheckoutOwned(new PaymentProvider.CheckoutRequest(orderId.toString(), idempotencyKey,
                    order.total(), order.currency(), storefrontUrl + "/checkout?success=true&orderId=" + orderId + "&session_id={CHECKOUT_SESSION_ID}",
                    storefrontUrl + "/orders", Map.of("orderId", orderId.toString(), "expiresAt", expiresAt.toString(),
                            "country", order.country() == null ? "" : order.country().trim()),
                    selectedMethod == null ? null : selectedMethod.methodId()),
                    selectedMethod == null ? null : selectedMethod.providerInstanceId());
            result = owned.result();
        } catch (PaymentProvider.CheckoutRejectedException rejected) {
            jdbc.update("UPDATE payment_attempts SET status='FAILED',updated_at=now() WHERE attempt_id=? AND status IN ('CREATING','UNKNOWN')", paymentAttemptId);
            checkoutFailureCompensation.cancelRejectedCheckout(orderId);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Payment provider rejected checkout; order and stock reservation were cancelled");
        } catch (RuntimeException failure) {
            // Provider timeouts may be ambiguous; preserve the reservation and retry with the same key.
            jdbc.update("UPDATE payment_attempts SET status='UNKNOWN',updated_at=now() WHERE attempt_id=? AND status='CREATING'", paymentAttemptId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider could not create a checkout session");
        }
        if (result == null || result.providerReference() == null || result.providerReference().isBlank()
                || result.checkoutUrl() == null || result.checkoutUrl().isBlank()) {
            jdbc.update("UPDATE payment_attempts SET status='UNKNOWN',updated_at=now() WHERE attempt_id=? AND status='CREATING'", paymentAttemptId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider returned an invalid checkout session");
        }
        PaymentProvider.ProviderOwner owner = owned.owner();
        if (owner != null && (!owner.instanceId().equals(selectedInstanceId) || !java.util.Objects.equals(owner.releaseId(), selectedReleaseId)
                || owner.configurationGeneration() != selectedGeneration
                || !java.util.Objects.equals(owner.externalAccountFingerprint(), selectedAccountFingerprint))) {
            jdbc.update("UPDATE payment_attempts SET status='UNKNOWN',updated_at=now() WHERE attempt_id=?", paymentAttemptId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider ownership changed while creating checkout");
        }
        jdbc.update("UPDATE payment_attempts SET status='PENDING',provider_reference=?,checkout_url=?,provider_account_fingerprint=?,provider_scope=?::jsonb,updated_at=now() WHERE attempt_id=?",
                result.providerReference(), result.checkoutUrl(), owner == null ? null : owner.externalAccountFingerprint(),
                owner == null ? "{}" : writeJson(owner.scope()), paymentAttemptId);
        int paymentInserted = jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,payment_method_id,payment_obligation_id,payment_attempt_id,status,provider_plugin_id,provider_reference,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope,created_at,updated_at) VALUES (?,?,?,?,?,'EXTENSION',?,?,?, 'PENDING',?,?,?,?,?,?,?::jsonb,now(),now()) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), orderId, userId, order.total(), order.currency(),
                selectedMethod == null ? null : selectedMethod.methodId(), orderId, paymentAttemptId, providerPluginId, result.providerReference(),
                owner == null ? null : owner.instanceId(), owner == null ? null : owner.releaseId(),
                owner == null ? null : owner.configurationGeneration(), owner == null ? null : owner.externalAccountFingerprint(),
                owner == null ? "{}" : writeJson(owner.scope()));
        if (paymentInserted == 0) {
            UUID existingAttempt = jdbc.query("SELECT payment_attempt_id FROM payments WHERE provider_plugin_id=? AND provider_instance_id IS NOT DISTINCT FROM ? "
                            + "AND provider_account_fingerprint IS NOT DISTINCT FROM ? AND provider_reference=?",
                    (rs, row) -> rs.getObject(1, UUID.class), providerPluginId, owner == null ? null : owner.instanceId(),
                    owner == null ? null : owner.externalAccountFingerprint(), result.providerReference()).stream().findFirst().orElse(null);
            if (!paymentAttemptId.equals(existingAttempt)) {
                jdbc.update("UPDATE payment_attempts SET status='UNKNOWN',updated_at=now() WHERE attempt_id=?", paymentAttemptId);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payment provider reused an external reference for a different attempt");
            }
        }
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
                jdbc.update("UPDATE payment_attempts SET status='CANCELLED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", payment.id());
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
        var matchingReferences = references.stream().filter(payment -> java.security.MessageDigest.isEqual(
                payment.providerReference().getBytes(StandardCharsets.UTF_8), returnedProviderReference.getBytes(StandardCharsets.UTF_8))).toList();
        if (matchingReferences.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment provider order is unavailable");
        }
        CapturePayment payment = matchingReferences.getFirst();
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
        UUID settlementId = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_settlements(settlement_id,payment_id,provider_event_id,provider_reference,amount,currency,disposition) VALUES (?,?,?,?,?,?,'APPLIED')",
                settlementId, payment.id(), "buyer-return:" + payment.id(), providerReference, result.amount(), result.currency().trim().toUpperCase(java.util.Locale.ROOT));
        jdbc.update("UPDATE payments SET captured_amount=?,captured_currency=?,provider_transaction_reference=COALESCE(?,provider_transaction_reference),status='CAPTURED',updated_at=now() WHERE id=? AND status='PENDING'",
                result.amount(), result.currency().trim().toUpperCase(java.util.Locale.ROOT), result.transactionReference(), payment.id());
        jdbc.update("UPDATE payment_attempts SET status='CAPTURED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", payment.id());
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
        if ((event.providerReference() == null || event.providerReference().isBlank())
                && (event.transactionReference() == null || event.transactionReference().isBlank())) {
            markProviderWebhookProcessed(providerKey, event.providerEventId());
            return Map.of("status", "ignored");
        }
        UUID linkedPaymentId = paymentIdForReference(event.providerReference(), event.transactionReference(), webhookOwner);
        if (webhookOwner != null) {
            if (linkedPaymentId == null && paymentReferenceExists(event.providerReference(), event.transactionReference())) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "payment_not_found");
            }
            if (linkedPaymentId == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment checkout is not persisted yet; retry the provider webhook");
        }
        UUID orderId = linkedPaymentId == null ? null : lockOrderForPayment(linkedPaymentId);
        if (orderId == null) {
            if (webhookOwner != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment order is not available yet; retry the provider webhook");
            markProviderWebhookProcessed(providerKey, event.providerEventId());
            return Map.of("status", "ignored");
        }
        if (Set.of(PaymentProvider.PaymentState.DISPUTED, PaymentProvider.PaymentState.DISPUTE_WON,
                PaymentProvider.PaymentState.DISPUTE_LOST, PaymentProvider.PaymentState.REVERSED).contains(event.state())) {
            var paymentRows = jdbc.query("SELECT COALESCE(a.amount,p.amount) expected_amount,COALESCE(p.captured_amount,p.amount) captured_amount,"
                    + "p.currency,COALESCE(p.captured_currency,p.currency) captured_currency,p.provider_instance_id,p.refunded_amount,p.reversed_amount,p.status "
                    + "FROM payments p LEFT JOIN payment_attempts a ON a.attempt_id=p.payment_attempt_id WHERE p.id=? AND p.method='EXTENSION' FOR UPDATE OF p",
                    (rs,row) -> new PaymentRiskRow(rs.getBigDecimal("expected_amount"),rs.getBigDecimal("captured_amount"),rs.getString("currency").trim(),
                            rs.getString("captured_currency").trim(),rs.getObject("provider_instance_id",UUID.class),rs.getBigDecimal("refunded_amount"),
                            rs.getBigDecimal("reversed_amount"),rs.getString("status")),linkedPaymentId);
            if(paymentRows.isEmpty()){
                markProviderWebhookProcessed(providerKey,event.providerEventId());
                return Map.of("status","payment_not_found");
            }
            PaymentRiskRow payment=paymentRows.getFirst();
            if(!Set.of("CAPTURED","CAPTURE_REVIEW","PARTIALLY_REFUNDED","REFUNDED","REVERSED").contains(payment.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A provider dispute or reversal does not match a captured payment");
            if(event.amount()==null||event.amount().signum()<=0||event.currency()==null||event.currency().length()!=3
                    ||webhookOwner!=null&&!webhookOwner.instanceId().equals(payment.instanceId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provider risk event amount or owner does not match the payment");
            String currency=event.currency().trim().toUpperCase(java.util.Locale.ROOT);
            if(!currency.equalsIgnoreCase(payment.capturedCurrency()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provider risk event currency does not match the captured payment");
            String disposition=switch(event.state()){
                case DISPUTED -> "CHARGEBACK_OPEN";
                case DISPUTE_WON -> "CHARGEBACK_WON";
                case DISPUTE_LOST -> "CHARGEBACK_LOST";
                case REVERSED -> "PAYMENT_REVERSAL";
                default -> throw new IllegalStateException("Unexpected payment risk state");
            };
            UUID settlementId=UUID.randomUUID();
            jdbc.update("INSERT INTO payment_settlements(settlement_id,payment_id,provider_event_id,provider_reference,amount,currency,disposition) VALUES (?,?,?,?,?,?,?)",
                    settlementId,linkedPaymentId,event.providerEventId(),event.providerReference(),event.amount(),currency,disposition);
            boolean openChargeback=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_reconciliation_cases c JOIN payment_settlements s ON s.settlement_id=c.settlement_id WHERE c.payment_id=? AND c.case_type='CHARGEBACK' AND c.status='OPEN' AND s.provider_reference=? AND s.disposition='CHARGEBACK_OPEN')",Boolean.class,linkedPaymentId,event.providerReference()));
            String caseType=event.state()==PaymentProvider.PaymentState.REVERSED?"PAYMENT_REVERSAL"
                    :event.state()==PaymentProvider.PaymentState.DISPUTED||event.state()==PaymentProvider.PaymentState.DISPUTE_LOST&&!openChargeback?"CHARGEBACK":null;
            if(caseType!=null)jdbc.update("INSERT INTO payment_reconciliation_cases(case_id,payment_id,order_id,settlement_id,case_type,expected_amount,captured_amount,expected_currency,captured_currency) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(),linkedPaymentId,orderId,settlementId,caseType,payment.expectedAmount(),event.amount(),payment.currency(),currency);
            if(event.state()==PaymentProvider.PaymentState.DISPUTED){
                jdbc.update("UPDATE payments SET provider_transaction_reference=COALESCE(?,provider_transaction_reference),status='CAPTURE_REVIEW',updated_at=now() WHERE id=?",
                        event.transactionReference(),linkedPaymentId);
            }else if(event.state()==PaymentProvider.PaymentState.DISPUTE_WON){
                jdbc.update("UPDATE payment_reconciliation_cases c SET status='RESOLVED',resolution_note='Provider resolved the chargeback in the merchant’s favor',resolved_at=now() WHERE c.payment_id=? AND c.case_type='CHARGEBACK' AND c.status='OPEN' AND c.settlement_id IN (SELECT settlement_id FROM payment_settlements WHERE provider_reference=? AND disposition='CHARGEBACK_OPEN')",linkedPaymentId,event.providerReference());
                jdbc.update("UPDATE payments p SET provider_transaction_reference=COALESCE(?,provider_transaction_reference),status=CASE WHEN p.refunded_amount>=COALESCE(p.captured_amount,p.amount) THEN 'REFUNDED' WHEN p.refunded_amount>0 THEN 'PARTIALLY_REFUNDED' ELSE 'CAPTURED' END,updated_at=now() WHERE p.id=? AND p.status='CAPTURE_REVIEW' AND NOT EXISTS (SELECT 1 FROM payment_reconciliation_cases c WHERE c.payment_id=p.id AND c.status='OPEN')",
                        event.transactionReference(),linkedPaymentId);
            }else{
                BigDecimal remainingCapture=payment.capturedAmount().subtract(payment.refundedAmount()).subtract(payment.reversedAmount());
                if(event.amount().compareTo(remainingCapture)>0)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provider reversal exceeds the remaining captured balance");
                jdbc.update("UPDATE payments SET provider_transaction_reference=COALESCE(?,provider_transaction_reference),reversed_amount=reversed_amount+?,status='REVERSED',updated_at=now() WHERE id=?",
                        event.transactionReference(),event.amount(),linkedPaymentId);
                if(event.state()==PaymentProvider.PaymentState.DISPUTE_LOST)
                    jdbc.update("UPDATE payment_reconciliation_cases c SET resolution_note='Provider ruled against the merchant; the disputed funds were reversed' WHERE c.payment_id=? AND c.case_type='CHARGEBACK' AND c.status='OPEN' AND c.settlement_id IN (SELECT settlement_id FROM payment_settlements WHERE provider_reference=? AND disposition='CHARGEBACK_OPEN')",linkedPaymentId,event.providerReference());
            }
            markProviderWebhookProcessed(providerKey,event.providerEventId());
            return Map.of("status","reconciliation_required");
        }
        if (event.state() == PaymentProvider.PaymentState.CAPTURED) {
            var settlements = jdbc.query("SELECT p.id,COALESCE(a.amount,p.amount) amount,p.currency,p.provider_instance_id,p.status FROM payments p LEFT JOIN payment_attempts a ON a.attempt_id=p.payment_attempt_id WHERE p.id=? AND p.method='EXTENSION'",
                    (rs, row) -> new PaymentSettlement(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency").trim(),
                            rs.getObject("provider_instance_id", UUID.class), rs.getString("status")),
                    linkedPaymentId);
            if (settlements.isEmpty()) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "payment_not_found");
            }
            var settlement = settlements.getFirst();
            if (event.amount() == null || event.amount().signum() < 0 || event.currency() == null || event.currency().length() != 3
                    || webhookOwner != null && !webhookOwner.instanceId().equals(settlement.instanceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider settlement amount or currency does not match the payment");
            }
            String orderStatus = jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId);
            String capturedCurrency = event.currency().trim().toUpperCase(java.util.Locale.ROOT);
            boolean currencyMatches = settlement.currency().equalsIgnoreCase(capturedCurrency);
            int amountComparison = settlement.amount().compareTo(event.amount());
            String disposition;
            String caseType = null;
            if (!currencyMatches) { disposition = "CURRENCY_MISMATCH"; caseType = disposition; }
            else if ("CANCELLED".equals(orderStatus)) {
                disposition = "LATE_CAPTURE"; caseType = disposition;
            } else if ("PAID".equals(orderStatus) || amountComparison < 0) { disposition = "OVERPAYMENT"; caseType = disposition; }
            else if (amountComparison > 0) { disposition = "UNDERPAYMENT"; caseType = disposition; }
            else disposition = "APPLIED";

            UUID settlementId = UUID.randomUUID();
            jdbc.update("INSERT INTO payment_settlements(settlement_id,payment_id,provider_event_id,provider_reference,amount,currency,disposition) VALUES (?,?,?,?,?,?,?)",
                    settlementId, settlement.id(), event.providerEventId(), event.providerReference(), event.amount(), capturedCurrency, disposition);
            if (caseType != null) {
                jdbc.update("INSERT INTO payment_reconciliation_cases(case_id,payment_id,order_id,settlement_id,case_type,expected_amount,captured_amount,expected_currency,captured_currency) VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), settlement.id(), orderId, settlementId, caseType, settlement.amount(), event.amount(), settlement.currency(), capturedCurrency);
            }
            if ("UNDERPAYMENT".equals(disposition) || "CURRENCY_MISMATCH".equals(disposition)) {
            jdbc.update("UPDATE payments SET captured_amount=?,captured_currency=?,provider_transaction_reference=COALESCE(?,provider_transaction_reference),status='CAPTURE_REVIEW',updated_at=now() WHERE id=?", event.amount(), capturedCurrency, event.transactionReference(), settlement.id());
                jdbc.update("UPDATE payment_attempts SET status='CAPTURED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", settlement.id());
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "reconciliation_required");
            }
            jdbc.update("UPDATE payments SET captured_amount=?,captured_currency=?,provider_transaction_reference=COALESCE(?,provider_transaction_reference),status='CAPTURED',updated_at=now() WHERE id=? AND status IN ('PENDING','FAILED','CANCELLED','CAPTURED')", event.amount(), capturedCurrency, event.transactionReference(), settlement.id());
            jdbc.update("UPDATE payment_attempts SET status='CAPTURED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", settlement.id());
            if ("LATE_CAPTURE".equals(disposition)) {
                markProviderWebhookProcessed(providerKey, event.providerEventId());
                return Map.of("status", "reconciliation_required");
            }
            int paid = jdbc.update("UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'", orderId);
            if (paid > 0) {
                OrderTimelineEvents.record(jdbc,orderId,null,"ORDER_PAID","Payment confirmed",true);
                awardLoyalty(orderId);
                orderUpdates.publish(orderId);
                jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind,order_id) SELECT gen_random_uuid(),o.user_id,oi.variant_id,'PURCHASE',o.id FROM orders o JOIN order_items oi ON oi.order_id=o.id WHERE o.id=? ON CONFLICT DO NOTHING",orderId);
                jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,created_at) SELECT ?,user_id,'PAYMENT_RECEIVED','Payment received','Payment received for order '||id,false,now() FROM orders WHERE id=? AND NOT EXISTS (SELECT 1 FROM notifications WHERE type='PAYMENT_RECEIVED' AND body='Payment received for order '||?)",UUID.randomUUID(),orderId,orderId);
            }
        } else if (event.state() == PaymentProvider.PaymentState.EXPIRED || event.state() == PaymentProvider.PaymentState.FAILED
                || event.state() == PaymentProvider.PaymentState.CANCELLED) {
            jdbc.update("UPDATE payments SET status='FAILED',updated_at=now() WHERE id=? AND status='PENDING'", linkedPaymentId);
            jdbc.update("UPDATE payment_attempts SET status='FAILED',updated_at=now() WHERE attempt_id=(SELECT payment_attempt_id FROM payments WHERE id=?)", linkedPaymentId);
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

    private boolean paymentReferenceExists(String providerReference,String transactionReference) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payments WHERE method='EXTENSION' AND "
                + "(provider_reference IN (?,?) OR provider_transaction_reference IN (?,?))",Boolean.class,
                providerReference,transactionReference,providerReference,transactionReference));
    }

    private UUID paymentIdForReference(String providerReference,String transactionReference,PaymentProvider.ProviderOwner owner) {
        if (owner != null) return jdbc.query("SELECT id FROM payments WHERE (provider_reference IN (?,?) OR provider_transaction_reference IN (?,?)) AND method='EXTENSION' AND provider_plugin_id IS NOT NULL "
                        + "AND provider_instance_id=? AND provider_release_id IS NOT DISTINCT FROM ? AND provider_configuration_generation=? "
                        + "AND provider_account_fingerprint IS NOT DISTINCT FROM ?",
                (rs, row) -> rs.getObject(1, UUID.class), providerReference,transactionReference,providerReference,transactionReference,owner.instanceId(), owner.releaseId(),
                owner.configurationGeneration(), owner.externalAccountFingerprint()).stream().findFirst().orElse(null);
        if (!extensionProviderId.isBlank()) {
            UUID configured = jdbc.query("SELECT id FROM payments WHERE (provider_reference IN (?,?) OR provider_transaction_reference IN (?,?)) AND method='EXTENSION' AND provider_plugin_id=?",
                    (rs, row) -> rs.getObject(1, UUID.class), providerReference,transactionReference,providerReference,transactionReference,extensionProviderId).stream().findFirst().orElse(null);
            if (configured != null) return configured;
        }
        return jdbc.query("SELECT id FROM payments WHERE (provider_reference IN (?,?) OR provider_transaction_reference IN (?,?)) AND method='EXTENSION' AND provider_plugin_id IS NULL",
                (rs, row) -> rs.getObject(1, UUID.class), providerReference,transactionReference,providerReference,transactionReference).stream().findFirst().orElse(null);
    }

    private UUID lockOrderForPayment(UUID paymentId) {
        var orders=jdbc.query("SELECT o.id FROM orders o JOIN payments p ON p.order_id=o.id WHERE p.id=? FOR UPDATE OF o",
                (rs,row)->rs.getObject(1,UUID.class),paymentId);
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
        var payments = jdbc.query("SELECT provider_reference,status,COALESCE(captured_amount,amount) amount,refunded_amount,reversed_amount,COALESCE(captured_currency,currency) currency,method,order_id,user_id,gift_card_id,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope::text FROM payments WHERE id=? FOR UPDATE",
                (rs,n)->new RefundRow(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getString(6).trim(),rs.getString(7),rs.getObject(8,UUID.class),rs.getObject(9,UUID.class),rs.getObject(10,UUID.class),providerOwner(rs,11)),paymentId);
        if (payments.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");
        RefundRow payment = payments.getFirst();
        var priorRows = jdbc.query("SELECT id,amount,status,provider_reference FROM payment_refunds WHERE payment_id=? AND idempotency_key=?",
                (rs,n)->new RefundRecord(rs.getObject("id",UUID.class),rs.getBigDecimal("amount"),rs.getString("status"),rs.getString("provider_reference")),paymentId,idempotencyKey);
        RefundRecord prior = priorRows.isEmpty() ? null : priorRows.getFirst();
        BigDecimal remaining = payment.amount().subtract(payment.refundedAmount()).subtract(payment.reversedAmount());
        if (prior != null && prior.status().equals("COMPLETED")) {
            if (command != null && command.amount() != null && prior.amount().compareTo(command.amount()) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"This idempotency key was already used for a different refund amount");
            return ResponseEntity.ok(new RefundView(prior.id(), paymentId, prior.amount(), payment.currency(), payment.refundedAmount(), remaining,
                    prior.providerReference(), "COMPLETED"));
        }
        if (!Set.of("CAPTURED", "CAPTURE_REVIEW", "PARTIALLY_REFUNDED").contains(payment.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only captured payments can be refunded");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_reconciliation_cases WHERE payment_id=? AND case_type IN ('CHARGEBACK','PAYMENT_REVERSAL') AND status='OPEN')",Boolean.class,paymentId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A provider dispute or reversal must be reconciled before issuing a refund");
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
        if ("REFUNDED".equals(paymentStatus)) jdbc.update("UPDATE payment_reconciliation_cases SET status='RESOLVED',resolution_note='Captured amount fully refunded',resolved_at=now() WHERE payment_id=? AND status='OPEN'",paymentId);
        jdbc.update("UPDATE payment_refunds SET status='COMPLETED',provider_reference=?,updated_at=now() WHERE id=?",providerReference,refundId);
        LoyaltyAccounting.reverseForRefund(jdbc,refundId,payment.orderId());
        OrderTimelineEvents.record(jdbc,payment.orderId(),UUID.fromString(auth.getToken().getSubject()),"PAYMENT_REFUNDED",
                "Refund processed: "+refundAmount.toPlainString()+" "+payment.currency(),true);
        return ResponseEntity.ok(new RefundView(refundId,paymentId,refundAmount,payment.currency(),totalRefunded,
                payment.amount().subtract(totalRefunded).subtract(payment.reversedAmount()),providerReference,"COMPLETED"));
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
    private record RefundRow(String providerReference,String status,BigDecimal amount,BigDecimal refundedAmount,BigDecimal reversedAmount,String currency,String method,UUID orderId,UUID userId,UUID giftCardId,PaymentProvider.ProviderOwner owner){}
    private record RefundRecord(UUID id,BigDecimal amount,String status,String providerReference){}
    private record PaymentSettlement(UUID id,BigDecimal amount,String currency,UUID instanceId,String status){}
    private record PaymentRiskRow(BigDecimal expectedAmount,BigDecimal capturedAmount,String currency,String capturedCurrency,UUID instanceId,BigDecimal refundedAmount,BigDecimal reversedAmount,String status){}
    public record RefundCommand(BigDecimal amount){}
    public record RefundView(UUID id,UUID paymentId,BigDecimal amount,String currency,BigDecimal totalRefunded,
                             BigDecimal remainingAmount,String providerReference,String status){}
    private record OrderRow(BigDecimal total,String currency,String status,String country){}
    private record PendingPayment(UUID id,String providerReference,String method,PaymentProvider.ProviderOwner owner){}
    private record CapturePayment(String providerReference, String status, BigDecimal amount, String currency, PaymentProvider.ProviderOwner owner, UUID id) { }
    private record PaymentAttempt(UUID id, String requestFingerprint, String status, String providerReference, String checkoutUrl) { }
    public record PaymentSelection(String methodReference) { }
    public record PaymentMethodDiscoveryRequest(String country, String currency, BigDecimal amount) { }
    public record PaymentMethodOption(String reference, String displayName) { }
    public record PaymentMethodDiscoveryView(java.util.List<PaymentMethodOption> methods,
            java.util.List<PaymentProvider.ProviderFailure> failures, boolean partialFailure) { }
    private record PaymentMethodReference(String methodId, String pluginId, UUID instanceId, Long releaseId,
            int configurationGeneration, String country, String currency, BigDecimal amount) { }
}
