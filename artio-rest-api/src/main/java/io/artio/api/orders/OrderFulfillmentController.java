package io.artio.api.orders;

import io.artio.api.inventory.InventoryApplicationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/orders")
public class OrderFulfillmentController {
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;
    private final OrderUpdatePublisher orderUpdates;
    public OrderFulfillmentController(JdbcTemplate jdbc,InventoryApplicationService inventory,OrderUpdatePublisher orderUpdates){this.jdbc=jdbc;this.inventory=inventory;this.orderUpdates=orderUpdates;}

    @PostMapping("/{id}/ship") @Transactional
    public FulfillmentView ship(@PathVariable UUID id,@RequestBody ShipRequest request){
        if(request.trackingNumber()==null||request.trackingNumber().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tracking number is required");
        String status=status(id);
        if(!"PAID".equals(status)&&!"CONFIRMED".equals(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only paid or confirmed orders can be shipped");
        for(var reservation:inventory.reservationsByOrder(id))if(reservation.state()==io.artio.inventory.ReservationState.RESERVED)inventory.allocate(reservation.id());
        jdbc.update("UPDATE orders SET status='SHIPPED',tracking_number=? WHERE id=?",request.trackingNumber(),id);
        UUID userId=jdbc.queryForObject("SELECT user_id FROM orders WHERE id=?",UUID.class,id);
        jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,dedupe_key,created_at) VALUES (?,?,?,'Order shipped',?,false,?,?) ON CONFLICT (dedupe_key) DO NOTHING",UUID.randomUUID(),userId,"ORDER_SHIPPED","Your order "+id+" is on the way. Tracking: "+request.trackingNumber(),"order-shipped:"+id,java.sql.Timestamp.from(Instant.now()));
        orderUpdates.publish(id);
        return new FulfillmentView(id,"SHIPPED",request.trackingNumber());
    }

    @PostMapping("/{id}/deliver")
    public FulfillmentView deliver(@PathVariable UUID id){
        if(!"SHIPPED".equals(status(id)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only shipped orders can be delivered");
        jdbc.update("UPDATE orders SET status='DELIVERED' WHERE id=?",id);
        orderUpdates.publish(id);
        return new FulfillmentView(id,"DELIVERED",jdbc.queryForObject("SELECT tracking_number FROM orders WHERE id=?",String.class,id));
    }
    private String status(UUID id){var statuses=jdbc.query("SELECT status FROM orders WHERE id=?",(rs,n)->rs.getString(1),id);if(statuses.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");return statuses.getFirst();}
    public record ShipRequest(String trackingNumber){} public record FulfillmentView(UUID orderId,String status,String trackingNumber){}
}
