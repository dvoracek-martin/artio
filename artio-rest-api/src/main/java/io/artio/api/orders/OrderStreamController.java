package io.artio.api.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class OrderStreamController {
    private final JdbcTemplate jdbc;private final SimpMessagingTemplate messaging;
    public OrderStreamController(JdbcTemplate jdbc,SimpMessagingTemplate messaging){this.jdbc=jdbc;this.messaging=messaging;}
    @MessageMapping("/orders/{orderId}/subscribe")
    public void subscribe(@DestinationVariable UUID orderId,org.springframework.messaging.simp.stomp.StompHeaderAccessor headers){
        if(!(headers.getUser() instanceof JwtAuthenticationToken auth))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        UUID user=UUID.fromString(auth.getToken().getSubject());UUID tenant=UUID.fromString(auth.getToken().getClaimAsString("tenant_id"));
        var rows=jdbc.query("SELECT o.status,o.total,o.currency,o.tracking_number,o.created_at FROM orders o JOIN tenant_memberships m ON m.user_id=o.user_id JOIN tenants t ON t.id=m.tenant_id WHERE o.id=? AND o.user_id=? AND m.tenant_id=? AND t.active",(rs,n)->new OrderUpdate(orderId,rs.getString(1),rs.getBigDecimal(2),rs.getString(3).trim(),rs.getString(4),rs.getTimestamp(5).toInstant()),orderId,user,tenant);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        messaging.convertAndSendToUser(user.toString(),"/queue/orders/"+orderId,rows.getFirst());
    }
    public record OrderUpdate(UUID orderId,String status,BigDecimal total,String currency,String trackingNumber,Instant updatedAt){}
}
