package io.artio.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditLogController {
    private final JdbcTemplate jdbc;
    public AuditLogController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    public List<AuditEntry> search(@RequestParam(required=false) UUID userId,@RequestParam(required=false) String action,
                                   @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
                                   @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){
        if(page<0||size<1||size>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        return jdbc.query("SELECT id,user_id,action,path,status,ip_address,user_agent,occurred_at FROM audit_logs WHERE (?::uuid IS NULL OR user_id=?) AND (?::text IS NULL OR action=?) AND (?::timestamptz IS NULL OR occurred_at>=?) AND (?::timestamptz IS NULL OR occurred_at<=?) ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                (rs,n)->new AuditEntry(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getString("action"),rs.getString("path"),rs.getInt("status"),rs.getString("ip_address"),rs.getString("user_agent"),rs.getTimestamp("occurred_at").toInstant()),
                userId,userId,action,action,from,from,to,to,size,(long)page*size);
    }
    public record AuditEntry(UUID id,UUID userId,String action,String path,int status,String ipAddress,String userAgent,Instant occurredAt){}
}
