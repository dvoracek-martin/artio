package io.artio.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    public NotificationController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping public List<NotificationView> list(JwtAuthenticationToken auth){return jdbc.query("SELECT id,type,subject,body,is_read,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 100",(rs,n)->new NotificationView(rs.getObject("id",UUID.class),rs.getString("type"),rs.getString("subject"),rs.getString("body"),rs.getBoolean("is_read"),rs.getTimestamp("created_at").toInstant()),userId(auth));}
    @PutMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void markRead(@PathVariable UUID id,JwtAuthenticationToken auth){jdbc.update("UPDATE notifications SET is_read=true WHERE id=? AND user_id=?",id,userId(auth));}
    private static UUID userId(JwtAuthenticationToken auth){return UUID.fromString(auth.getToken().getSubject());}
    public record NotificationView(UUID id,String type,String subject,String body,boolean read,Instant createdAt){}
}
