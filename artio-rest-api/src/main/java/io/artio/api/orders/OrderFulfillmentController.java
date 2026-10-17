package io.artio.api.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.api.inventory.InventoryApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.commerce.ShippingProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/orders")
public class OrderFulfillmentController {
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;
    private final ObjectProvider<ShippingProvider> shippingProvider;
    private final ObjectMapper mapper;
    public OrderFulfillmentController(JdbcTemplate jdbc,InventoryApplicationService inventory,OrderUpdatePublisher orderUpdates,ObjectProvider<ShippingProvider> shippingProvider,ObjectMapper mapper){this.jdbc=jdbc;this.inventory=inventory;this.orderUpdates=orderUpdates;this.shippingProvider=shippingProvider;this.mapper=mapper;}

    @PostMapping("/{id}/ship") @Transactional
    public FulfillmentView ship(@PathVariable UUID id,@RequestBody ShipRequest request){
        String status=jdbc.query("SELECT status FROM orders WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
        if(!"PAID".equals(status)&&!"CONFIRMED".equals(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only paid or confirmed orders can be shipped");
        ShippingProvider provider=shippingProvider.getIfAvailable();
        if(provider!=null&&!provider.capabilities().shipmentCreation())provider=null;
        String trackingNumber=request.trackingNumber();String shipmentReference=null;String labelUrl=null;
        ShippingProvider.ProviderOwner owner=null;
        if(provider!=null){
            ShippingOrder order=jdbc.query("SELECT shipping_address,shipping_country,shipping_method_id,shipping_parcel_weight_kg,subtotal+tax AS declared_value,currency FROM orders WHERE id=?",(rs,n)->new ShippingOrder(rs.getString("shipping_address"),rs.getString("shipping_country").trim(),rs.getString("shipping_method_id"),rs.getBigDecimal("shipping_parcel_weight_kg"),rs.getBigDecimal("declared_value"),rs.getString("currency").trim()),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
            if(order.methodId()!=null){
                if(order.weightKg()==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order shipping data is incomplete");
                if(!provider.capabilities().shipmentCreation())throw new ResponseStatusException(HttpStatus.CONFLICT,"Configured shipping provider cannot create shipments");
                try{
                    ShippingProvider.Address destination=mapper.readValue(order.address(),ShippingProvider.Address.class);
                    String methodId=order.methodId();UUID selectedInstanceId=null;int separator=methodId.indexOf(':');
                    if(separator>0){try{selectedInstanceId=UUID.fromString(methodId.substring(0,separator));methodId=methodId.substring(separator+1);}catch(IllegalArgumentException legacyProvider){methodId=methodId.substring(separator+1);}}
                    var owned=provider.createShipmentOwned(new ShippingProvider.ShipmentRequest(id.toString(),methodId,destination,order.weightKg(),order.declaredValue(),order.currency(),"ship-order-"+id),selectedInstanceId);
                    var result=owned.result();owner=owned.owner();
                    shipmentReference=result.shipmentReference();labelUrl=result.labelUrl();if(result.trackingReference()!=null&&!result.trackingReference().isBlank())trackingNumber=result.trackingReference();
                }catch(ResponseStatusException failure){throw failure;}catch(Exception failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Shipping provider could not create the shipment");}
            }
        }
        if(trackingNumber==null||trackingNumber.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Shipping provider did not return a tracking number");
        UUID shipmentId=UUID.randomUUID();
        jdbc.update("INSERT INTO order_shipments(id,order_id,tracking_number,shipment_reference,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                shipmentId,id,trackingNumber,shipmentReference,owner==null?null:owner.instanceId(),owner==null?null:owner.releaseId(),
                owner==null?null:owner.configurationGeneration(),owner==null?null:owner.externalAccountFingerprint(),owner==null?"{}":writeJson(owner.scope()));
        jdbc.update("INSERT INTO order_shipment_items(shipment_id,order_item_id,quantity) SELECT ?,id,quantity FROM order_items WHERE order_id=?",shipmentId,id);
        for(var reservation:inventory.reservationsByOrder(id))if(reservation.state()==io.artio.inventory.ReservationState.RESERVED)inventory.allocate(reservation.id());
        jdbc.update("UPDATE orders SET status='SHIPPED',tracking_number=?,shipment_reference=?,shipping_label_url=? WHERE id=?",trackingNumber,shipmentReference,labelUrl,id);
        UUID userId=jdbc.queryForObject("SELECT user_id FROM orders WHERE id=?",UUID.class,id);
        OrderTimelineEvents.record(jdbc,id,null,"ORDER_SHIPPED","Order shipped. Tracking: "+trackingNumber,true);
        jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,dedupe_key,created_at) VALUES (?,?,?,'Order shipped',?,false,?,?) ON CONFLICT (dedupe_key) DO NOTHING",UUID.randomUUID(),userId,"ORDER_SHIPPED","Your order "+id+" is on the way. Tracking: "+trackingNumber,"order-shipped:"+id,java.sql.Timestamp.from(Instant.now()));
        orderUpdates.publish(id);
        return new FulfillmentView(id,"SHIPPED",trackingNumber,shipmentReference,labelUrl);
    }

    @PostMapping("/{id}/shipments") @Transactional
    public ShipmentView createPartialShipment(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,@RequestBody CreateShipmentRequest request){
        String status=jdbc.query("SELECT status FROM orders WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
        if(!List.of("PAID","CONFIRMED","PARTIALLY_SHIPPED").contains(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only paid or confirmed orders can be fulfilled");
        boolean providerShipment=request!=null&&request.shippingMethodId()!=null&&!request.shippingMethodId().isBlank();
        if(request==null||request.shipmentReference()!=null&&request.shipmentReference().length()>255
                ||request.items()==null||request.items().isEmpty()||request.items().size()>100
                ||providerShipment&&(request.trackingNumber()!=null&&!request.trackingNumber().isBlank()||idempotencyKey==null||!idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}"))
                ||!providerShipment&&(request.trackingNumber()==null||request.trackingNumber().isBlank()||request.trackingNumber().length()>255)
                ||request.shippingMethodId()!=null&&request.shippingMethodId().length()>120
                ||request.parcelWeightKg()!=null&&(request.parcelWeightKg().signum()<=0||request.parcelWeightKg().precision()>12
                        ||request.parcelWeightKg().stripTrailingZeros().scale()>3||request.parcelWeightKg().compareTo(new BigDecimal("1000"))>0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide either a tracking number or a carrier method with an idempotency key and 1–100 shipment lines");
        if(idempotencyKey!=null&&!idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key must contain 8 to 128 safe characters");
        HashSet<UUID> unique=new HashSet<>();List<ShipmentOrderLine> shipmentLines=new java.util.ArrayList<>();
        for(var item:request.items()){
            if(item==null||item.orderItemId()==null||item.quantity()==null||item.quantity().signum()<=0||item.quantity().precision()>15||item.quantity().stripTrailingZeros().scale()>4||!unique.add(item.orderItemId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Shipment lines must have unique order items and valid positive quantities");
        }
        String fingerprint=shipmentFingerprint(request);
        if(idempotencyKey!=null){
            var prior=jdbc.query("SELECT id,request_fingerprint FROM order_shipments WHERE order_id=? AND idempotency_key=?",
                    (rs,n)->new Object[]{rs.getObject("id",UUID.class),rs.getString("request_fingerprint")},id,idempotencyKey);
            if(!prior.isEmpty()){
                if(!fingerprint.equals(((String)prior.getFirst()[1]).trim()))throw new ResponseStatusException(HttpStatus.CONFLICT,"This idempotency key was used for a different shipment");
                return shipmentView(id,(UUID)prior.getFirst()[0]);
            }
        }
        UUID shipmentId=idempotencyKey==null?UUID.randomUUID():UUID.nameUUIDFromBytes((id+":shipment:"+idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(var item:request.items()){
            var line=jdbc.query("SELECT variant_id,quantity,bundle_id FROM order_items WHERE id=? AND order_id=?",(rs,n)->new ShipmentOrderLine(rs.getObject("variant_id",UUID.class),rs.getBigDecimal("quantity"),rs.getObject("bundle_id",UUID.class)),item.orderItemId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Shipment line does not belong to this order"));
            java.math.BigDecimal shipped=jdbc.queryForObject("SELECT COALESCE(sum(si.quantity),0) FROM order_shipment_items si JOIN order_shipments s ON s.id=si.shipment_id WHERE s.order_id=? AND si.order_item_id=?",java.math.BigDecimal.class,id,item.orderItemId());
            if(item.quantity().compareTo(line.quantity().subtract(shipped))>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Shipment quantity exceeds the unfulfilled quantity");
            shipmentLines.add(line);
        }
        String trackingNumber=request.trackingNumber()==null||request.trackingNumber().isBlank()?null:request.trackingNumber().trim();
        String shipmentReference=request.shipmentReference();String labelUrl=null;ShippingProvider.ProviderOwner owner=null;
        if(providerShipment){
            ShippingProvider provider=shippingProvider.getIfAvailable();
            if(provider==null||!provider.capabilities().shipmentCreation())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No carrier is configured to create shipment labels");
            var order=jdbc.query("SELECT shipping_address,shipping_country,shipping_method_id,shipping_parcel_weight_kg,subtotal+tax AS declared_value,currency FROM orders WHERE id=?",
                    (rs,n)->new ShippingOrder(rs.getString("shipping_address"),rs.getString("shipping_country").trim(),rs.getString("shipping_method_id"),rs.getBigDecimal("shipping_parcel_weight_kg"),rs.getBigDecimal("declared_value"),rs.getString("currency").trim()),id).getFirst();
            BigDecimal weight=request.parcelWeightKg()!=null?request.parcelWeightKg():order.weightKg();
            if(weight==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"A parcel weight is required to create a carrier label");
            ShippingMethodSelection selected=shippingMethod(request.shippingMethodId());
            ShippingProvider.Address destination;
            try{destination=mapper.readValue(order.address(),ShippingProvider.Address.class);}
            catch(Exception invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"The order has no valid delivery address");}
            if(!provider.capabilities().countries().isEmpty()&&!provider.capabilities().countries().contains(order.country()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected carrier does not serve the order destination");
            try{
                boolean methodAvailable=provider.methods(destination,order.currency()).stream().anyMatch(method->method.methodId().equals(request.shippingMethodId()));
                if(!methodAvailable)throw new ResponseStatusException(HttpStatus.CONFLICT,"The selected carrier method is no longer available");
                var providerRequest=new ShippingProvider.ShipmentRequest(shipmentId.toString(),selected.providerMethodId(),destination,weight,
                        order.declaredValue(),order.currency(),"ship-shipment-"+shipmentId);
                var created=provider.createShipmentOwned(providerRequest,selected.instanceId());
                owner=created.owner();var result=created.result();trackingNumber=result.trackingReference();shipmentReference=result.shipmentReference();labelUrl=result.labelUrl();
            }catch(ResponseStatusException failure){throw failure;}
            catch(RuntimeException failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"The selected carrier could not confirm shipment creation; retry with the same Idempotency-Key");}
        }
        if(trackingNumber==null&&shipmentReference==null&&labelUrl==null)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"The carrier returned no shipment reference, tracking number or label");
        try{jdbc.update("INSERT INTO order_shipments(id,order_id,tracking_number,shipment_reference,shipping_method_id,label_url,idempotency_key,request_fingerprint,provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope) "
                        +"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",shipmentId,id,trackingNumber,shipmentReference,providerShipment?request.shippingMethodId():null,labelUrl,idempotencyKey,
                idempotencyKey==null?null:fingerprint,owner==null?null:owner.instanceId(),owner==null?null:owner.releaseId(),owner==null?null:owner.configurationGeneration(),
                owner==null?null:owner.externalAccountFingerprint(),owner==null?"{}":writeJson(owner.scope()));}
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw new ResponseStatusException(HttpStatus.CONFLICT,"This tracking number or idempotency key is already assigned to the order");}
        for(int index=0;index<request.items().size();index++){
            var item=request.items().get(index);var line=shipmentLines.get(index);
            jdbc.update("INSERT INTO order_shipment_items(shipment_id,order_item_id,quantity) VALUES (?,?,?)",shipmentId,item.orderItemId(),item.quantity());
            inventory.allocate(id,line.variantId(),item.quantity());
        }
        boolean complete=Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id=? AND oi.quantity > COALESCE((SELECT sum(si.quantity) FROM order_shipment_items si JOIN order_shipments s ON s.id=si.shipment_id WHERE s.order_id=oi.order_id AND si.order_item_id=oi.id),0))",Boolean.class,id));
        String nextStatus=complete?"SHIPPED":"PARTIALLY_SHIPPED";
        if(complete)for(var reservation:inventory.reservationsByOrder(id))if(reservation.state()==io.artio.inventory.ReservationState.RESERVED)inventory.allocate(reservation.id());
        jdbc.update("UPDATE orders SET status=?,tracking_number=?,shipment_reference=?,shipping_label_url=? WHERE id=?",nextStatus,trackingNumber,shipmentReference,labelUrl,id);
        OrderTimelineEvents.record(jdbc,id,null,"ORDER_SHIPMENT_CREATED","Shipment created"+(trackingNumber==null?"":". Tracking: "+trackingNumber),true);
        if(complete)OrderTimelineEvents.record(jdbc,id,null,"ORDER_SHIPPED","All order items have shipped",true);
        orderUpdates.publish(id);
        return new ShipmentView(shipmentId,id,nextStatus,trackingNumber,shipmentReference,labelUrl,request.items(),complete);
    }

    @GetMapping("/{id}/shipments")
    public List<ShipmentView> shipments(@PathVariable UUID id){
        if(jdbc.query("SELECT 1 FROM orders WHERE id=?",(rs,n)->rs.getInt(1),id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        return jdbc.query("SELECT id,order_id,tracking_number,shipment_reference,label_url,created_at FROM order_shipments WHERE order_id=? ORDER BY created_at,id",(rs,n)->{
            UUID shipmentId=rs.getObject("id",UUID.class);List<ShipmentItemRequest> items=jdbc.query("SELECT order_item_id,quantity FROM order_shipment_items WHERE shipment_id=? ORDER BY order_item_id",(item,row)->new ShipmentItemRequest(item.getObject("order_item_id",UUID.class),item.getBigDecimal("quantity")),shipmentId);
            String orderStatus=status(id);
            return new ShipmentView(shipmentId,rs.getObject("order_id",UUID.class),orderStatus,rs.getString("tracking_number"),rs.getString("shipment_reference"),rs.getString("label_url"),items,"SHIPPED".equals(orderStatus));
        },id);
    }

    @PostMapping("/{id}/shipments/{shipmentId}/tracking/refresh")
    @Transactional
    public TrackingView refreshShipmentTracking(@PathVariable UUID id,@PathVariable UUID shipmentId){
        var rows=jdbc.query("SELECT o.status,s.tracking_number,s.provider_instance_id,s.provider_release_id,s.provider_configuration_generation,"
                        +"s.provider_account_fingerprint,s.provider_scope::text FROM orders o JOIN order_shipments s ON s.order_id=o.id WHERE o.id=? AND s.id=?",
                (rs,n)->new ShipmentTrackingOrder(rs.getString("status"),rs.getString("tracking_number"),providerOwner(rs)),id,shipmentId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipment not found");
        ShipmentTrackingOrder shipment=rows.getFirst();
        if(shipment.trackingNumber()==null||shipment.trackingNumber().isBlank())throw new ResponseStatusException(HttpStatus.CONFLICT,"This shipment has no tracking reference");
        ShippingProvider provider=shippingProvider.getIfAvailable();
        if(provider==null||shipment.owner()==null&&!provider.capabilities().tracking())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The selected carrier does not provide tracking updates");
        ShippingProvider.TrackingResult result;
        try{result=provider.track(shipment.owner(),shipment.trackingNumber()).result();}
        catch(RuntimeException failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Carrier tracking could not be refreshed");}
        if(result==null||!shipment.trackingNumber().equals(result.trackingReference())||result.status()==null||result.status().isBlank()
                ||result.status().length()>80||result.lastLocation()!=null&&result.lastLocation().length()>200)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Carrier returned invalid tracking data");
        Instant occurredAt=null;
        if(result.occurredAt()!=null&&!result.occurredAt().isBlank())try{occurredAt=Instant.parse(result.occurredAt());}
        catch(java.time.format.DateTimeParseException invalid){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Carrier returned an invalid tracking timestamp");}
        jdbc.update("INSERT INTO shipment_tracking_events(id,order_id,tracking_reference,carrier_status,last_location,occurred_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),id,result.trackingReference(),result.status(),result.lastLocation(),occurredAt==null?null:java.sql.Timestamp.from(occurredAt));
        OrderTimelineEvents.record(jdbc,id,null,"TRACKING_UPDATED","Carrier status: "+result.status(),true);
        if("SHIPPED".equals(shipment.orderStatus())&&allTrackedShipmentsDelivered(id)){
            jdbc.update("UPDATE orders SET status='DELIVERED' WHERE id=? AND status='SHIPPED'",id);
            OrderTimelineEvents.record(jdbc,id,null,"ORDER_DELIVERED","All tracked shipments have been delivered",true);
        }
        return new TrackingView(id,result.trackingReference(),result.status(),result.lastLocation(),occurredAt,Instant.now());
    }
    private boolean allTrackedShipmentsDelivered(UUID orderId){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM order_shipments WHERE order_id=?) AND NOT EXISTS(SELECT 1 FROM order_shipments s "
                +"WHERE s.order_id=? AND s.tracking_number IS NOT NULL AND COALESCE((SELECT upper(e.carrier_status) FROM shipment_tracking_events e "
                +"WHERE e.order_id=s.order_id AND e.tracking_reference=s.tracking_number ORDER BY e.checked_at DESC LIMIT 1),'')<>'DELIVERED')",
                Boolean.class,orderId,orderId));
    }

    @PostMapping("/{id}/deliver")
    @Transactional
    public FulfillmentView deliver(@PathVariable UUID id){
        if(!"SHIPPED".equals(status(id)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only shipped orders can be delivered");
        jdbc.update("UPDATE orders SET status='DELIVERED' WHERE id=?",id);
        OrderTimelineEvents.record(jdbc,id,null,"ORDER_DELIVERED","Order delivered",true);
        orderUpdates.publish(id);
        return new FulfillmentView(id,"DELIVERED",jdbc.queryForObject("SELECT tracking_number FROM orders WHERE id=?",String.class,id),null,null);
    }
    @PostMapping("/{id}/tracking/refresh") @Transactional
    public TrackingView refreshTracking(@PathVariable UUID id) {
        var rows=jdbc.query("SELECT status,tracking_number FROM orders WHERE id=?",(rs,n)->new TrackingOrder(rs.getString(1),rs.getString(2)),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        TrackingOrder order=rows.getFirst();
        if(!"SHIPPED".equals(order.status())&& !"DELIVERED".equals(order.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only shipped orders have carrier tracking");
        if(order.trackingNumber()==null||order.trackingNumber().isBlank())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tracking reference is unavailable");
        ShippingProvider provider=shippingProvider.getIfAvailable();
        ShippingProvider.ProviderOwner owner=jdbc.query("SELECT provider_instance_id,provider_release_id,provider_configuration_generation,provider_account_fingerprint,provider_scope::text FROM order_shipments WHERE order_id=? AND tracking_number=? ORDER BY created_at DESC LIMIT 1",
                (rs,n)->providerOwner(rs),id,order.trackingNumber()).stream().findFirst().orElse(null);
        if(provider==null||owner==null&&!provider.capabilities().tracking())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The configured carrier does not provide tracking updates");
        ShippingProvider.TrackingResult result;
        try{result=provider.track(owner,order.trackingNumber()).result();}
        catch(RuntimeException failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Carrier tracking could not be refreshed");}
        if(result==null||!order.trackingNumber().equals(result.trackingReference())||result.status()==null||result.status().isBlank()
                ||result.status().length()>80||result.lastLocation()!=null&&result.lastLocation().length()>200)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Carrier returned invalid tracking data");
        Instant occurredAt=null;
        if(result.occurredAt()!=null&&!result.occurredAt().isBlank())try{occurredAt=Instant.parse(result.occurredAt());}
        catch(java.time.format.DateTimeParseException invalid){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Carrier returned an invalid tracking timestamp");}
        jdbc.update("INSERT INTO shipment_tracking_events(id,order_id,tracking_reference,carrier_status,last_location,occurred_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),id,order.trackingNumber(),result.status(),result.lastLocation(),occurredAt==null?null:java.sql.Timestamp.from(occurredAt));
        OrderTimelineEvents.record(jdbc,id,null,"TRACKING_UPDATED","Carrier status: "+result.status(),true);
        if("DELIVERED".equalsIgnoreCase(result.status())&&"SHIPPED".equals(order.status())&&allTrackedShipmentsDelivered(id)){
            jdbc.update("UPDATE orders SET status='DELIVERED' WHERE id=? AND status='SHIPPED'",id);
            OrderTimelineEvents.record(jdbc,id,null,"ORDER_DELIVERED","Carrier confirmed delivery",true);
        }
        return new TrackingView(id,result.trackingReference(),result.status(),result.lastLocation(),occurredAt,Instant.now());
    }
    @GetMapping("/{id}/tracking")
    public java.util.List<TrackingView> trackingHistory(@PathVariable UUID id){
        if(jdbc.query("SELECT 1 FROM orders WHERE id=?",(rs,n)->rs.getInt(1),id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        return jdbc.query("SELECT id,order_id,tracking_reference,carrier_status,last_location,occurred_at,checked_at FROM shipment_tracking_events WHERE order_id=? ORDER BY checked_at DESC LIMIT 100",
                (rs,n)->new TrackingView(rs.getObject("order_id",UUID.class),rs.getString("tracking_reference"),rs.getString("carrier_status"),rs.getString("last_location"),rs.getTimestamp("occurred_at")==null?null:rs.getTimestamp("occurred_at").toInstant(),rs.getTimestamp("checked_at").toInstant()),id);
    }
    @GetMapping("/{id}/label")
    public ResponseEntity<byte[]> shippingLabel(@PathVariable UUID id) {
        String label=jdbc.query("SELECT shipping_label_url FROM orders WHERE id=?",rs->rs.next()?rs.getString(1):null,id);
        return labelResponse(label,"artio-shipping-label-"+id+".pdf");
    }
    @GetMapping("/{id}/shipments/{shipmentId}/label")
    public ResponseEntity<byte[]> shipmentLabel(@PathVariable UUID id,@PathVariable UUID shipmentId) {
        String label=jdbc.query("SELECT label_url FROM order_shipments WHERE order_id=? AND id=?",rs->rs.next()?rs.getString(1):null,id,shipmentId);
        return labelResponse(label,"artio-shipment-label-"+shipmentId+".pdf");
    }
    private ResponseEntity<byte[]> labelResponse(String label,String filename){
        if(label==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipping label not found");
        String prefix="data:application/pdf;base64,";
        if(!label.startsWith(prefix)||label.length()>2_100_000)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipping label not found");
        try{
            byte[] pdf=java.util.Base64.getDecoder().decode(label.substring(prefix.length()));
            if(pdf.length==0||pdf.length>1_500_000||pdf[0]!='%'||pdf[1]!='P'||pdf[2]!='D'||pdf[3]!='F')throw new IllegalArgumentException();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename="+filename)
                    .header("X-Content-Type-Options","nosniff").cacheControl(org.springframework.http.CacheControl.noStore()).body(pdf);
        }catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Stored shipping label is invalid");}
    }
    private ShipmentView shipmentView(UUID orderId,UUID shipmentId){
        var rows=jdbc.query("SELECT order_id,tracking_number,shipment_reference,label_url FROM order_shipments WHERE order_id=? AND id=?",
                (rs,n)->new ShipmentView(shipmentId,rs.getObject("order_id",UUID.class),status(orderId),rs.getString("tracking_number"),
                        rs.getString("shipment_reference"),rs.getString("label_url"),List.of(),false),orderId,shipmentId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"The idempotency key belongs to a different shipment");
        ShipmentView row=rows.getFirst();
        List<ShipmentItemRequest> items=jdbc.query("SELECT order_item_id,quantity FROM order_shipment_items WHERE shipment_id=? ORDER BY order_item_id",
                (rs,n)->new ShipmentItemRequest(rs.getObject("order_item_id",UUID.class),rs.getBigDecimal("quantity")),shipmentId);
        boolean complete="SHIPPED".equals(row.orderStatus())||"DELIVERED".equals(row.orderStatus());
        return new ShipmentView(row.id(),row.orderId(),row.orderStatus(),row.trackingNumber(),row.shipmentReference(),row.labelUrl(),items,complete);
    }
    private String shipmentFingerprint(CreateShipmentRequest request){
        try{
            String json=mapper.writeValueAsString(new ShipmentFingerprint(request.trackingNumber(),request.shipmentReference(),request.shippingMethodId(),
                    request.parcelWeightKg(),request.items().stream().sorted(java.util.Comparator.comparing(item->item.orderItemId().toString())).toList()));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(Exception failure){throw new IllegalStateException("Shipment request could not be fingerprinted",failure);}
    }
    private static ShippingMethodSelection shippingMethod(String value){
        int separator=value.indexOf(':');if(separator<=0||separator==value.length()-1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Carrier method must include its provider identity");
        String provider=value.substring(0,separator),method=value.substring(separator+1);
        if(!method.matches("[A-Za-z0-9._-]{1,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Carrier method is invalid");
        try{return new ShippingMethodSelection(UUID.fromString(provider),method);}
        catch(IllegalArgumentException pluginId){return new ShippingMethodSelection(null,method);}
    }
    private String status(UUID id){var statuses=jdbc.query("SELECT status FROM orders WHERE id=?",(rs,n)->rs.getString(1),id);if(statuses.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");return statuses.getFirst();}
    private ShippingProvider.ProviderOwner providerOwner(ResultSet rs)throws SQLException{
        UUID instanceId=rs.getObject("provider_instance_id",UUID.class);if(instanceId==null)return null;
        Number release=(Number)rs.getObject("provider_release_id");
        try{return new ShippingProvider.ProviderOwner(instanceId,release==null?null:release.longValue(),rs.getInt("provider_configuration_generation"),
                rs.getString("provider_account_fingerprint")==null?null:rs.getString("provider_account_fingerprint").trim(),
                mapper.readValue(rs.getString("provider_scope"),new TypeReference<java.util.Map<String,String>>(){}));}
        catch(Exception invalid){throw new SQLException("Persisted carrier owner scope is invalid",invalid);}
    }
    private String writeJson(Object value){try{return mapper.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("Carrier owner scope could not be serialized",invalid);}}
    public record ShipRequest(String trackingNumber){} public record FulfillmentView(UUID orderId,String status,String trackingNumber,String shipmentReference,String labelUrl){}
    public record CreateShipmentRequest(String trackingNumber,String shipmentReference,List<ShipmentItemRequest> items,String shippingMethodId,BigDecimal parcelWeightKg){}
    public record ShipmentItemRequest(UUID orderItemId,java.math.BigDecimal quantity){}
    public record ShipmentView(UUID id,UUID orderId,String orderStatus,String trackingNumber,String shipmentReference,String labelUrl,List<ShipmentItemRequest> items,boolean orderFulfilled){}
    private record ShipmentFingerprint(String trackingNumber,String shipmentReference,String shippingMethodId,BigDecimal parcelWeightKg,List<ShipmentItemRequest> items){}
    private record ShippingMethodSelection(UUID instanceId,String providerMethodId){}
    private record ShipmentOrderLine(UUID variantId,java.math.BigDecimal quantity,UUID bundleId){}
    public record TrackingView(UUID orderId,String trackingReference,String status,String lastLocation,Instant occurredAt,Instant checkedAt){}
    private record TrackingOrder(String status,String trackingNumber){}
    private record ShippingOrder(String address,String country,String methodId,BigDecimal weightKg,BigDecimal declaredValue,String currency){}
    private record ShipmentTrackingOrder(String orderStatus,String trackingNumber,ShippingProvider.ProviderOwner owner){}
}
