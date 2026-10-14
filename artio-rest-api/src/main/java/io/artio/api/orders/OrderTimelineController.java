package io.artio.api.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class OrderTimelineController {
    private final JdbcTemplate jdbc;
    public OrderTimelineController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/admin/orders/{orderId}/timeline")
    public List<TimelineEvent> adminTimeline(@PathVariable UUID orderId) {
        requireOrder(orderId);
        return jdbc.query("SELECT id,event_type,message,actor_id,customer_visible,occurred_at FROM order_timeline_events WHERE order_id=? ORDER BY occurred_at,id",
                (rs,row)->new TimelineEvent(rs.getObject("id",UUID.class),rs.getString("event_type"),rs.getString("message"),rs.getObject("actor_id",UUID.class),rs.getBoolean("customer_visible"),rs.getTimestamp("occurred_at").toInstant()),orderId);
    }

    @GetMapping("/api/orders/{orderId}/timeline")
    public List<TimelineEvent> customerTimeline(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        UUID customerId=userId(auth);
        if(jdbc.query("SELECT 1 FROM orders WHERE id=? AND user_id=?",(rs,row)->rs.getInt(1),orderId,customerId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        return jdbc.query("SELECT id,event_type,message,actor_id,customer_visible,occurred_at FROM order_timeline_events WHERE order_id=? AND customer_visible ORDER BY occurred_at,id",
                (rs,row)->new TimelineEvent(rs.getObject("id",UUID.class),rs.getString("event_type"),rs.getString("message"),rs.getObject("actor_id",UUID.class),true,rs.getTimestamp("occurred_at").toInstant()),orderId);
    }

    @PostMapping("/api/admin/orders/{orderId}/notes")
    public TimelineEvent addStaffNote(@PathVariable UUID orderId,@RequestBody NoteRequest request,JwtAuthenticationToken auth) {
        if(request==null||request.message()==null||request.message().isBlank()||request.message().length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A note of 1–2000 characters is required");
        requireOrder(orderId);UUID actor=userId(auth);UUID id=UUID.randomUUID();Instant now=Instant.now();String message=request.message().trim();
        jdbc.update("INSERT INTO order_timeline_events(id,order_id,actor_id,event_type,message,customer_visible,occurred_at) VALUES (?,?,?,'STAFF_NOTE',?,false,?)",id,orderId,actor,message,java.sql.Timestamp.from(now));
        return new TimelineEvent(id,"STAFF_NOTE",message,actor,false,now);
    }
    private void requireOrder(UUID id){if(jdbc.query("SELECT 1 FROM orders WHERE id=?",(rs,row)->rs.getInt(1),id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");}
    private static UUID userId(JwtAuthenticationToken auth){return UUID.fromString(auth.getToken().getSubject());}
    public record NoteRequest(String message){}
    public record TimelineEvent(UUID id,String eventType,String message,UUID actorId,boolean customerVisible,Instant occurredAt){}
}
