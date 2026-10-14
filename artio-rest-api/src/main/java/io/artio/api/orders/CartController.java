package io.artio.api.orders;

import io.artio.api.pricing.PricingApplicationService;
import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.bundles.BundleApplicationService;
import io.artio.api.enterprise.GiftCardAccounting;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import io.artio.kernel.Money;
import io.artio.kernel.Quantity;
import io.artio.orders.Order;
import io.artio.orders.OrderLineItem;
import io.artio.extensions.commerce.PaymentProvider;
import io.artio.extensions.commerce.ShippingProvider;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final JdbcTemplate jdbc;
    private final PricingApplicationService pricing;
    private final InventoryApplicationService inventory;
    private final BundleApplicationService bundles;
    private final ObjectMapper mapper;
    private final BigDecimal shippingCost;
    private final ObjectProvider<PaymentProvider> paymentProvider;
    private final ObjectProvider<ShippingProvider> shippingProvider;
    public CartController(JdbcTemplate jdbc, PricingApplicationService pricing, InventoryApplicationService inventory, BundleApplicationService bundles,ObjectMapper mapper,@Value("${artio.checkout.shipping-cost:0.00}") BigDecimal shippingCost,ObjectProvider<PaymentProvider> paymentProvider,ObjectProvider<ShippingProvider> shippingProvider) { this.jdbc = jdbc; this.pricing = pricing; this.inventory=inventory; this.bundles=bundles;this.mapper=mapper;this.shippingCost=shippingCost;this.paymentProvider=paymentProvider;this.shippingProvider=shippingProvider; }

    @GetMapping("/shipping-methods")
    public List<ShippingMethodView> shippingMethods(@RequestParam String country, @RequestParam(required=false) String postalCode,
            @RequestParam(required=false) String locality, @RequestParam(required=false) String addressLine1, @RequestParam(required=false) String recipientName) {
        Market market = marketForCountry(country);
        var destination = new ShippingProvider.Address(country.toUpperCase(java.util.Locale.ROOT), postalCode, locality,
                addressLine1, null, recipientName);
        ShippingProvider provider = shippingProvider.getIfAvailable();
        if (provider == null) return List.of(new ShippingMethodView("standard", "Standard delivery", null, shippingCost, market.currency()));
        if (blank(postalCode) || blank(locality) || blank(addressLine1) || blank(recipientName)) return List.of();
        var capabilities = provider.capabilities();
        if (!capabilities.countries().contains(destination.countryCode())) return List.of();
        return provider.methods(destination, market.currency()).stream()
                .map(method -> new ShippingMethodView(method.methodId(), method.name(), method.estimatedDelivery(), null, market.currency())).toList();
    }

    @PostMapping("/shipping-quote")
    public ShippingQuoteView shippingQuote(@RequestBody ShippingQuoteRequest request, JwtAuthenticationToken auth) {
        if (request == null || request.destination() == null || request.methodId() == null || request.methodId().isBlank()) throw new IllegalArgumentException("Shipping method and destination are required");
        ShippingProvider provider = shippingProvider.getIfAvailable();
        String country=request.destination().countryCode()==null?null:request.destination().countryCode().toUpperCase(java.util.Locale.ROOT);
        var destination=new ShippingProvider.Address(country,request.destination().postalCode(),request.destination().locality(),request.destination().addressLine1(),request.destination().addressLine2(),request.destination().recipientName());
        Market market = marketForCountry(country);
        if (provider == null) {
            if (!"standard".equals(request.methodId())) throw new IllegalArgumentException("Unknown shipping method");
            return new ShippingQuoteView("standard", shippingCost, market.currency(), null);
        }
        if(blank(destination.postalCode())||blank(destination.locality())||blank(destination.addressLine1())||blank(destination.recipientName()))throw new IllegalArgumentException("Recipient and complete delivery address are required");
        if (!provider.capabilities().countries().contains(country)) throw new IllegalArgumentException("Shipping provider does not serve this country");
        var method = provider.methods(destination, market.currency()).stream()
                .filter(candidate -> candidate.methodId().equals(request.methodId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown shipping method"));
        BigDecimal weight = validWeight(request.parcelWeightKg());
        BigDecimal declaredValue = request.declaredValue() == null ? BigDecimal.ZERO : request.declaredValue();
        if (declaredValue.signum() < 0) throw new IllegalArgumentException("Declared value cannot be negative");
        var quote = provider.quote(new ShippingProvider.ShippingRequest("cart:" + userId(auth), method.methodId(), destination,
                weight, declaredValue, market.currency()));
        validateShippingQuote(quote, method.methodId(), market.currency());
        return new ShippingQuoteView(quote.methodId(), quote.amount(), quote.currency(), quote.estimatedDelivery());
    }

    @GetMapping
    public CartView get(JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        UUID cartId = ensureCart(userId);
        return view(cartId);
    }

    @PostMapping("/items") @Transactional
    public CartView add(@RequestBody AddItemRequest request, JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        UUID cartId = ensureCart(userId);
        var quote = pricing.quote(new PricingApplicationService.QuoteRequest(request.variantId(), request.market(), request.channel(), request.jurisdiction(), request.quantity(), null));
        validateQuantity(request.quantity());
        int changed=jdbc.update("UPDATE cart_items SET quantity=quantity+?,unit_price=?,currency=? WHERE cart_id=? AND variant_id=? AND bundle_ref IS NULL",request.quantity(),quote.unitListPrice().amount(),quote.unitListPrice().currency().getCurrencyCode(),cartId,request.variantId());
        if(changed==0)jdbc.update("INSERT INTO cart_items(cart_id,variant_id,quantity,unit_price,currency) VALUES (?,?,?,?,?)",cartId,request.variantId(),request.quantity(),quote.unitListPrice().amount(),quote.unitListPrice().currency().getCurrencyCode());
        return view(cartId);
    }

    @PostMapping("/bundles") @Transactional
    public CartView addBundle(@RequestBody AddBundleRequest request,JwtAuthenticationToken auth){
        UUID cartId=ensureCart(userId(auth));validateQuantity(request.bundleQuantity());
        var configureRequest=new BundleApplicationService.ConfigureRequest(request.selectedOptions(),request.bundleQuantity());
        var configuration=bundles.configure(request.bundleId(),configureRequest);
        var quoteRequest=new BundleApplicationService.BundleQuoteRequest(request.selectedOptions(),request.bundleQuantity(),request.market(),request.channel(),request.jurisdiction());
        var bundleQuote=bundles.quote(request.bundleId(),quoteRequest);
        BigDecimal componentTotal=BigDecimal.ZERO;var unitPrices=new HashMap<UUID,Money>();
        for(var component:configuration.components()){var p=pricing.quote(new PricingApplicationService.QuoteRequest(component.sellableId(),request.market(),request.channel(),request.jurisdiction(),BigDecimal.ONE,null));unitPrices.put(component.sellableId(),p.taxableAmount());componentTotal=componentTotal.add(p.taxableAmount().amount().multiply(component.quantity()));}
        if(componentTotal.signum()<=0)throw new IllegalArgumentException("Bundle component prices must be positive");
        BigDecimal factor=bundleQuote.perBundlePrice().amount().divide(componentTotal,12,RoundingMode.HALF_EVEN);
        UUID bundleRef=UUID.randomUUID();
        try{jdbc.update("INSERT INTO cart_bundle_lines(bundle_ref,cart_id,bundle_id,selected_options,quantity,market,channel,jurisdiction,created_at) VALUES (?,?,?,?::jsonb,?,?,?,?,now())",bundleRef,cartId,request.bundleId(),mapper.writeValueAsString(request.selectedOptions()),request.bundleQuantity(),request.market(),request.channel(),request.jurisdiction());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Bundle configuration could not be stored",e);}
        for(var component:configuration.components()){
            var price=unitPrices.get(component.sellableId());if(!price.currency().equals(bundleQuote.perBundlePrice().currency()))throw new IllegalArgumentException("Bundle price currency must match component prices");BigDecimal quantity=component.quantity().multiply(request.bundleQuantity());
            BigDecimal adjusted=price.amount().multiply(factor).setScale(Math.max(0,price.currency().getDefaultFractionDigits()),RoundingMode.HALF_EVEN);
            jdbc.update("INSERT INTO cart_items(cart_id,variant_id,quantity,unit_price,currency,bundle_ref,bundle_id) VALUES (?,?,?,?,?,?,?)",cartId,component.sellableId(),quantity,adjusted,price.currency().getCurrencyCode(),bundleRef,request.bundleId());
        }
        return view(cartId);
    }

    @DeleteMapping("/items/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID variantId, JwtAuthenticationToken auth) {
        UUID user=userId(auth);
        jdbc.update("DELETE FROM cart_items WHERE bundle_ref IN (SELECT i.bundle_ref FROM cart_items i JOIN carts c ON c.id=i.cart_id WHERE c.user_id=? AND i.variant_id=? AND i.bundle_ref IS NOT NULL)",user,variantId);
        jdbc.update("DELETE FROM cart_bundle_lines b USING carts c WHERE b.cart_id=c.id AND c.user_id=? AND NOT EXISTS (SELECT 1 FROM cart_items i WHERE i.bundle_ref=b.bundle_ref)",user);
        jdbc.update("DELETE FROM cart_items i USING carts c WHERE i.cart_id=c.id AND c.user_id=? AND i.variant_id=? AND i.bundle_ref IS NULL", user, variantId);
    }

    @PostMapping("/checkout") @Transactional
    public org.springframework.http.ResponseEntity<OrderView> checkout(@RequestBody CheckoutRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        if(idempotencyKey==null||!idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}"))throw new IllegalArgumentException("Idempotency-Key must contain 8 to 128 safe characters");
        OrderView prior=findByIdempotencyKey(userId,idempotencyKey);if(prior!=null)return org.springframework.http.ResponseEntity.ok(prior);
        if(paymentProvider.getIfAvailable()==null&&blank(request.giftCardCode()))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payments are not configured");
        UUID cartId = jdbc.query("SELECT id FROM carts WHERE user_id=? AND expires_at>now()", rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, userId);
        if (cartId == null) throw new IllegalArgumentException("Cart is empty");
        List<CartRow> items = jdbc.query("SELECT id,variant_id,quantity,unit_price,currency,bundle_ref,bundle_id FROM cart_items WHERE cart_id=?", (rs,n) -> new CartRow(rs.getObject("id",UUID.class),rs.getObject("variant_id",UUID.class),rs.getBigDecimal("quantity"),rs.getBigDecimal("unit_price"),rs.getString("currency").trim(),rs.getObject("bundle_ref",UUID.class),rs.getObject("bundle_id",UUID.class)), cartId);
        if(items.isEmpty())throw new IllegalArgumentException("Cart is empty");
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        Market market=marketForCountry(request.shippingCountry());Currency currency=Currency.getInstance(market.currency());
        List<OrderLineItem> orderItems=new java.util.ArrayList<>();List<LineTaxSnapshot> taxLines=new ArrayList<>();List<BundleOrderSnapshot> bundleSnapshots=new ArrayList<>();Map<UUID,BigDecimal> requirements=new HashMap<>();Set<UUID> processedBundles=new java.util.HashSet<>();
        for(CartRow item:items){
            if(item.bundleRef()!=null){if(!processedBundles.add(item.bundleRef()))continue;var bundleLine=jdbc.queryForObject("SELECT selected_options,quantity,market,channel,jurisdiction FROM cart_bundle_lines WHERE bundle_ref=? AND cart_id=?",(rs,n)->new BundleCartRow(rs.getString("selected_options"),rs.getBigDecimal("quantity"),rs.getString("market"),rs.getString("channel"),rs.getString("jurisdiction")),item.bundleRef(),cartId);
                Map<String,Set<UUID>> selections;try{selections=mapper.readValue(bundleLine.selectedOptions(),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Stored bundle configuration is invalid",e);}
                var config=bundles.configure(item.bundleId(),new BundleApplicationService.ConfigureRequest(selections,bundleLine.quantity()));
                var quote=bundles.quote(item.bundleId(),new BundleApplicationService.BundleQuoteRequest(selections,bundleLine.quantity(),market.market(),"WEB",market.jurisdiction()));
                var current=new ArrayList<ComponentPrice>();BigDecimal base=BigDecimal.ZERO;BigDecimal baseTax=BigDecimal.ZERO;
                for(var component:config.components()){BigDecimal qty=component.quantity().multiply(bundleLine.quantity());var pq=pricing.quote(new PricingApplicationService.QuoteRequest(component.sellableId(),market.market(),"WEB",market.jurisdiction(),qty,null));if(!currency.equals(pq.unitListPrice().currency()))throw new IllegalArgumentException("Bundle currency does not match shipping country");base=base.add(pq.taxableAmount().amount());baseTax=baseTax.add(pq.tax().amount());current.add(new ComponentPrice(component.sellableId(),qty,pq));requirements.merge(component.sellableId(),qty,BigDecimal::add);}
                if(base.signum()<=0)throw new IllegalArgumentException("The bundle cannot be repriced because its components have no current taxable value");BigDecimal bundleTotal=quote.totalPrice().amount();BigDecimal factor=bundleTotal.divide(base,12,RoundingMode.HALF_EVEN);subtotal=subtotal.add(base.multiply(factor));tax=tax.add(baseTax.multiply(factor));
                for(var component:current){BigDecimal unit=component.quote().taxableAmount().amount().divide(component.quantity(),12,RoundingMode.HALF_EVEN).multiply(factor).setScale(Math.max(0,currency.getDefaultFractionDigits()),RoundingMode.HALF_EVEN);orderItems.add(new OrderLineItem(component.variantId(),new Quantity(component.quantity()),new Money(unit,currency),item.bundleId()));taxLines.add(LineTaxSnapshot.from(component.variantId(),component.quantity(),component.quote(),item.bundleId(),factor));}
                bundleSnapshots.add(new BundleOrderSnapshot(item.bundleId(),bundleLine.quantity(),selections,config.components().stream().map(c->new BundleComponentSnapshot(c.sellableId(),c.quantity())).toList(),quote.totalPrice().amount(),quote.totalPrice().currency().getCurrencyCode(),factor,market.market(),market.jurisdiction()));
            }else{
                BigDecimal qty=item.quantity();var quote=pricing.quote(new PricingApplicationService.QuoteRequest(item.variantId(),market.market(),"WEB",market.jurisdiction(),qty,null));if(!currency.equals(quote.unitListPrice().currency()))throw new IllegalArgumentException("Cart currency does not match shipping country");subtotal=subtotal.add(quote.taxableAmount().amount());tax=tax.add(quote.tax().amount());orderItems.add(new OrderLineItem(item.variantId(),new Quantity(qty),quote.unitListPrice()));taxLines.add(LineTaxSnapshot.from(item.variantId(),qty,quote,null,BigDecimal.ONE));requirements.merge(item.variantId(),qty,BigDecimal::add);
            }
        }
        if(subtotal.signum()<0)throw new IllegalArgumentException("Order total must not be negative");
        ShippingProvider provider = shippingProvider.getIfAvailable();
        BigDecimal shippingAmount = shippingCost;
        String shippingMethodId = null;
        BigDecimal parcelWeight = null;
        String persistedShippingAddress = request.shippingAddress();
        String persistedBillingAddress=request.billingAddress();
        if (provider != null) {
            if (request.shippingMethodId() == null || request.shippingDetails() == null) throw new IllegalArgumentException("Choose a shipping method and provide a structured delivery address");
            var destination = request.shippingDetails().toAddress(request.shippingCountry());
            if (!provider.capabilities().countries().contains(destination.countryCode())) throw new IllegalArgumentException("Shipping provider does not serve this country");
            var method = provider.methods(destination, currency.getCurrencyCode()).stream()
                    .filter(candidate -> candidate.methodId().equals(request.shippingMethodId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected shipping method is no longer available"));
            parcelWeight = validWeight(request.parcelWeightKg());
            shippingMethodId = method.methodId();
            try { persistedShippingAddress = mapper.writeValueAsString(destination); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Shipping address could not be stored", failure); }
            if(persistedBillingAddress==null||persistedBillingAddress.isBlank())persistedBillingAddress=String.join(", ",destination.recipientName(),destination.addressLine1(),destination.postalCode(),destination.locality(),destination.countryCode());
        }
        UUID orderId = UUID.randomUUID();
        ShippingProvider.ShippingQuote providerQuote = null;
        if (provider != null) {
            var destination = request.shippingDetails().toAddress(request.shippingCountry());
            providerQuote = provider.quote(new ShippingProvider.ShippingRequest(orderId.toString(), shippingMethodId, destination,
                    parcelWeight, subtotal.add(tax), currency.getCurrencyCode()));
            validateShippingQuote(providerQuote, shippingMethodId, currency.getCurrencyCode());
            shippingAmount = providerQuote.amount();
        }
        Money shipping=new Money(shippingAmount,currency);
        var order=Order.checkout(orderId,userId,orderItems,persistedShippingAddress,persistedBillingAddress,new Money(subtotal,currency),new Money(tax,currency),shipping);
        Map<String,Object> taxSnapshot=new HashMap<>();taxSnapshot.put("schemaVersion",2);taxSnapshot.put("calculatedAt",Instant.now());taxSnapshot.put("shippingCountry",request.shippingCountry().toUpperCase(java.util.Locale.ROOT));taxSnapshot.put("market",market.market());taxSnapshot.put("jurisdiction",market.jurisdiction());taxSnapshot.put("currency",currency.getCurrencyCode());taxSnapshot.put("lines",List.copyOf(taxLines));taxSnapshot.put("bundles",List.copyOf(bundleSnapshots));
        if(providerQuote!=null){taxSnapshot.put("shippingMethodId",providerQuote.methodId());taxSnapshot.put("shippingAmount",providerQuote.amount());taxSnapshot.put("shippingCurrency",providerQuote.currency());taxSnapshot.put("shippingEstimatedDelivery",providerQuote.estimatedDelivery());taxSnapshot.put("shippingParcelWeightKg",parcelWeight);}
        String taxSnapshotJson;try{taxSnapshotJson=mapper.writeValueAsString(taxSnapshot);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Order tax snapshot could not be serialized",e);}
        var inserted=jdbc.query("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key,shipping_method_id,shipping_parcel_weight_kg) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?) ON CONFLICT(user_id,idempotency_key) DO NOTHING RETURNING id",(rs,n)->rs.getObject(1,UUID.class),order.getId().value(),userId,order.status().name(),order.subtotal().amount(),order.tax().amount(),order.shipping().amount(),order.total().amount(),order.total().currency().getCurrencyCode(),order.shippingAddress(),persistedBillingAddress==null?persistedShippingAddress:persistedBillingAddress,java.sql.Timestamp.from(order.createdAt()),request.shippingCountry().toUpperCase(java.util.Locale.ROOT),market.market(),market.jurisdiction(),taxSnapshotJson,idempotencyKey,shippingMethodId,parcelWeight);
        if(inserted.isEmpty()){OrderView winner=findByIdempotencyKey(userId,idempotencyKey);if(winner==null)throw new IllegalStateException("Idempotent order was not visible after insert conflict");return org.springframework.http.ResponseEntity.ok(winner);}
        OrderTimelineEvents.record(jdbc,order.getId().value(),userId,"ORDER_CREATED","Order placed",true);
        order.items().forEach(i->jdbc.update("INSERT INTO order_items(id,order_id,variant_id,quantity,unit_price,currency,bundle_id) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),order.getId().value(),i.variantId(),i.quantity().value(),i.unitPrice().amount(),i.unitPrice().currency().getCurrencyCode(),i.bundleId()));
        var warehouses=inventory.warehouses().stream().filter(InventoryApplicationService.WarehouseView::active).toList();
        for(var requirement:requirements.entrySet()){var selected=warehouses.stream().filter(w->inventory.stockByWarehouse(w.id()).stream().anyMatch(s->s.sellableId().equals(requirement.getKey())&&s.available().compareTo(requirement.getValue())>=0)).findFirst().orElseThrow(()->new IllegalStateException("Insufficient inventory for item "+requirement.getKey()));inventory.reserve(new InventoryApplicationService.ReserveRequest(order.getId().value(),selected.id(),requirement.getKey(),requirement.getValue()));}
        if(!blank(request.giftCardCode())){
            var redemption=GiftCardAccounting.redeem(jdbc,request.giftCardCode(),userId,order.getId().value(),null);
            if(redemption.remainingDue().signum()>0&&paymentProvider.getIfAvailable()==null)
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Gift card was applied only partially and no external payment provider is configured");
        }
        UUID eventId=UUID.randomUUID();
        String eventPayload;
        try{eventPayload=mapper.writeValueAsString(Map.of("schemaVersion",1,"eventId",eventId,"type","ORDER_CREATED","occurredAt",Instant.now(),"orderId",order.getId().value(),"userId",userId,"locale",request.locale()));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Order event could not be serialized",e);}
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload) VALUES (?,'ORDER_CREATED','ORDER',?,?::jsonb)",eventId,order.getId().value().toString(),eventPayload);
        String orderNumber=order.getId().value().toString();
        var confirmation = io.artio.api.notifications.OrderConfirmationTemplates.confirmation(request.locale(), orderNumber);
        jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,dedupe_key,created_at) VALUES (?,?,?,?,?,false,?,now()) ON CONFLICT (dedupe_key) DO NOTHING",
                UUID.randomUUID(),userId,"ORDER_CREATED",confirmation.subject(),confirmation.body(),"order-created:"+orderNumber);
        jdbc.update("DELETE FROM cart_items WHERE cart_id=?",cartId);jdbc.update("DELETE FROM cart_bundle_lines WHERE cart_id=?",cartId);
        String persistedStatus=jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order.getId().value());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(new OrderView(order.getId().value(),persistedStatus,order.subtotal().amount(),order.tax().amount(),order.shipping().amount(),order.total().amount(),order.total().currency().getCurrencyCode(),null,request.shippingCountry().toUpperCase(java.util.Locale.ROOT),market.market(),market.jurisdiction(),taxSnapshot));
    }

    @GetMapping("/orders")
    public List<OrderView> orders(JwtAuthenticationToken auth) {
        return jdbc.query("SELECT id,status,subtotal,tax,shipping,total,currency,tracking_number,shipping_country,market,tax_jurisdiction,tax_snapshot FROM orders WHERE user_id=? ORDER BY created_at DESC", (rs,n) -> {try{return new OrderView(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getString(7).trim(),rs.getString(8),rs.getString(9).trim(),rs.getString(10),rs.getString(11),mapper.readValue(rs.getString(12),new TypeReference<Map<String,Object>>(){}));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Stored order tax snapshot is invalid",e);}}, userId(auth));
    }

    private static BigDecimal validWeight(BigDecimal weight) {
        if (weight == null || weight.signum() <= 0 || weight.compareTo(new BigDecimal("1000")) > 0 || weight.stripTrailingZeros().scale() > 3)
            throw new IllegalArgumentException("Parcel weight must be greater than 0 and no more than 1000 kg");
        return weight.setScale(3, RoundingMode.UNNECESSARY);
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static void validateShippingQuote(ShippingProvider.ShippingQuote quote, String method, String currency) {
        if (quote == null || !method.equals(quote.methodId()) || quote.amount() == null || quote.amount().signum() < 0
                || quote.amount().compareTo(new BigDecimal("1000000")) > 0 || quote.amount().precision() > 15
                || quote.amount().stripTrailingZeros().scale() > 4 || !currency.equals(quote.currency())) throw new IllegalArgumentException("Shipping provider returned a quote with invalid method, amount or currency");
    }

    private OrderView findByIdempotencyKey(UUID userId,String key){var matches=jdbc.query("SELECT id,status,subtotal,tax,shipping,total,currency,tracking_number,shipping_country,market,tax_jurisdiction,tax_snapshot FROM orders WHERE user_id=? AND idempotency_key=?",(rs,n)->{try{return new OrderView(rs.getObject("id",UUID.class),rs.getString("status"),rs.getBigDecimal("subtotal"),rs.getBigDecimal("tax"),rs.getBigDecimal("shipping"),rs.getBigDecimal("total"),rs.getString("currency").trim(),rs.getString("tracking_number"),rs.getString("shipping_country").trim(),rs.getString("market"),rs.getString("tax_jurisdiction"),mapper.readValue(rs.getString("tax_snapshot"),new TypeReference<Map<String,Object>>(){}));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Stored order tax snapshot is invalid",e);}},userId,key);return matches.stream().findFirst().orElse(null);}

    private UUID ensureCart(UUID userId) {
        UUID id = jdbc.query("SELECT id FROM carts WHERE user_id=? AND expires_at>now() ORDER BY created_at DESC LIMIT 1", rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, userId);
        if (id != null) return id;
        id = UUID.randomUUID();
        jdbc.update("INSERT INTO carts(id,user_id,created_at,expires_at) VALUES (?,?,now(),now()+interval '30 days')", id, userId);
        return id;
    }
    private CartView view(UUID cartId) {
        var items = jdbc.query("SELECT variant_id,quantity,unit_price,currency,bundle_id FROM cart_items WHERE cart_id=?", (rs,n) -> new ItemView(UUID.fromString(rs.getString(1)), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4).trim(),rs.getObject("bundle_id",UUID.class)), cartId);
        return new CartView(cartId, items);
    }
    private static UUID userId(JwtAuthenticationToken auth) { return UUID.fromString(auth.getToken().getSubject()); }

    public record AddItemRequest(UUID variantId, BigDecimal quantity, String market, String channel, String jurisdiction) { }
    public record AddBundleRequest(UUID bundleId,Map<String,Set<UUID>> selectedOptions,BigDecimal bundleQuantity,String market,String channel,String jurisdiction){}
    public record CheckoutRequest(String shippingAddress, String billingAddress, String shippingCountry, String locale,
                                  String shippingMethodId, ShippingAddressDetails shippingDetails, BigDecimal parcelWeightKg,String giftCardCode) {
        public CheckoutRequest(String shippingAddress, String billingAddress, String shippingCountry) {
            this(shippingAddress, billingAddress, shippingCountry, "en", null, null, null,null);
        }
        public CheckoutRequest(String shippingAddress, String billingAddress, String shippingCountry, String locale) { this(shippingAddress,billingAddress,shippingCountry,locale,null,null,null,null); }
    }
    public record ShippingAddressDetails(String recipientName, String postalCode, String locality, String addressLine1, String addressLine2) {
        ShippingProvider.Address toAddress(String country) {
            if (recipientName == null || recipientName.isBlank() || postalCode == null || postalCode.isBlank() || locality == null || locality.isBlank()
                    || addressLine1 == null || addressLine1.isBlank()) throw new IllegalArgumentException("Recipient, postal code, locality and street address are required");
            return new ShippingProvider.Address(country.toUpperCase(java.util.Locale.ROOT),postalCode,locality,addressLine1,addressLine2,recipientName);
        }
    }
    public record ShippingQuoteRequest(String methodId, ShippingProvider.Address destination, BigDecimal parcelWeightKg, BigDecimal declaredValue) { }
    public record ShippingMethodView(String methodId,String name,String estimatedDelivery,BigDecimal flatAmount,String currency) { }
    public record ShippingQuoteView(String methodId,BigDecimal amount,String currency,String estimatedDelivery) { }
    public record ItemView(UUID variantId, BigDecimal quantity, BigDecimal unitPrice, String currency,UUID bundleId) { }
    public record CartView(UUID cartId, List<ItemView> items) { }
    public record OrderView(UUID orderId, String status, BigDecimal subtotal, BigDecimal tax, BigDecimal shipping, BigDecimal total, String currency,String trackingNumber,String shippingCountry,String market,String taxJurisdiction,Map<String,Object> taxSnapshot) { }
    private record LineTaxSnapshot(UUID sellableId,BigDecimal quantity,String currency,BigDecimal unitListPrice,BigDecimal lineListPrice,BigDecimal discount,BigDecimal taxableAmount,boolean pricesIncludeTax,String taxCategory,BigDecimal taxRatePercent,BigDecimal tax,java.time.Instant taxRuleValidFrom,java.time.Instant taxRuleValidUntil,java.time.Instant pricedAt,UUID bundleId,BigDecimal bundleAdjustmentFactor){static LineTaxSnapshot from(UUID id,BigDecimal quantity,io.artio.pricing.PricingQuote quote,UUID bundleId,BigDecimal factor){return new LineTaxSnapshot(id,quantity,quote.unitListPrice().currency().getCurrencyCode(),quote.unitListPrice().amount(),quote.lineListPrice().amount(),quote.discounts().amount(),quote.taxableAmount().amount(),quote.pricesIncludeTax(),quote.taxCategory().name(),quote.taxRatePercent(),quote.tax().amount(),quote.taxRuleValidFrom(),quote.taxRuleValidUntil(),quote.pricedAt(),bundleId,factor);}}
    private record CartRow(UUID id,UUID variantId,BigDecimal quantity,BigDecimal unitPrice,String currency,UUID bundleRef,UUID bundleId){}
    private record BundleCartRow(String selectedOptions,BigDecimal quantity,String market,String channel,String jurisdiction){}
    private record BundleComponentSnapshot(UUID sellableId,BigDecimal quantity){}
    private record BundleOrderSnapshot(UUID bundleId,BigDecimal quantity,Map<String,Set<UUID>> selectedOptions,List<BundleComponentSnapshot> components,BigDecimal configuredTotal,String currency,BigDecimal componentAdjustmentFactor,String market,String jurisdiction){}
    private record ComponentPrice(UUID variantId,BigDecimal quantity,io.artio.pricing.PricingQuote quote){}
    private static Market marketForCountry(String country){return switch(country==null?"":country.toUpperCase(java.util.Locale.ROOT)){case "US"->new Market("US","US","USD");case "CZ"->new Market("CZ","CZ","CZK");case "CH"->new Market("CH","CH","CHF");case "DE","AT","FR","NL","BE","ES","IT","PL","SK"->new Market("EU",country.toUpperCase(java.util.Locale.ROOT),"EUR");default->throw new IllegalArgumentException("Shipping country is not supported");};}
    private record Market(String market,String jurisdiction,String currency){}
    private static void validateQuantity(BigDecimal quantity){if(quantity==null||quantity.signum()<=0||quantity.precision()>15||quantity.stripTrailingZeros().scale()>4)throw new IllegalArgumentException("Quantity must be positive, with at most 15 digits and 4 decimal places");}
}
