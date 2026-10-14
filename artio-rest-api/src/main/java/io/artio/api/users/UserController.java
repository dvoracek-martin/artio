package io.artio.api.users;

import io.artio.users.Permission;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class UserController {
    private final JdbcTemplate jdbc;
    public UserController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/api/users/me")
    public UserView me(JwtAuthenticationToken auth){return find(UUID.fromString(auth.getToken().getSubject()));}

    @GetMapping("/api/admin/users")
    public List<UserView> users(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String activity,
            @RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset){
        if(q.length()>120||limit<1||limit>200||offset<0||offset>1_000_000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid customer search bounds");
        String filter=activity==null?"ALL":activity.toUpperCase(Locale.ROOT);
        if(!Set.of("ALL","RECENT","STALE","NEVER").contains(filter))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"activity must be ALL, RECENT, STALE or NEVER");
        String pattern="%"+q.trim()+"%";
        return jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users "
                        +"WHERE (?='' OR email ILIKE ? OR first_name ILIKE ? OR last_name ILIKE ?) "
                        +"AND (?='ALL' OR (?='NEVER' AND last_login IS NULL) OR (?='RECENT' AND last_login>=now()-interval '30 days') OR (?='STALE' AND last_login<now()-interval '30 days')) "
                        +"ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                (rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),
                q.trim(),pattern,pattern,pattern,filter,filter,filter,filter,limit,offset);
    }

    @PutMapping("/api/admin/users/{id}/active")
    public UserView active(@PathVariable UUID id,@RequestBody ActiveRequest request,JwtAuthenticationToken auth){
        if(id.toString().equals(auth.getToken().getSubject()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrators cannot deactivate their own account");
        int changed=jdbc.update("UPDATE users SET active=? WHERE id=?",request.active(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    @PutMapping("/api/admin/users/{id}/email-verification")
    public UserView verified(@PathVariable UUID id,@RequestBody VerificationRequest request){int changed=jdbc.update("UPDATE users SET email_verified=? WHERE id=?",request.verified(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);}

    @PutMapping("/api/admin/users/{id}/role")
    public UserView role(@PathVariable UUID id,@RequestBody RoleRequest request,JwtAuthenticationToken auth){
        if(!"SUPERADMIN".equals(auth.getToken().getClaimAsString("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a superadmin can assign roles");
        if(!Set.of("CUSTOMER","ADMIN","SUPERADMIN").contains(request.role()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown role");
        if("SUPERADMIN".equals(request.role())&&!"SUPERADMIN".equals(auth.getToken().getClaimAsString("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        int changed=jdbc.update("UPDATE users SET role=? WHERE id=?",request.role(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    @PutMapping("/api/admin/users/{id}/permissions")
    public UserView permissions(@PathVariable UUID id,@RequestBody PermissionsRequest request){
        if(!"SUPERADMIN".equals(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().filter(a->a.getAuthority().equals("ROLE_SUPERADMIN")).map(a->"SUPERADMIN").findFirst().orElse("")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a superadmin can change global permissions");
        try {request.permissions().forEach(p->Permission.valueOf(p.toUpperCase(Locale.ROOT)));}
        catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown permission",e);}
        int changed=jdbc.update("UPDATE users SET permissions=? WHERE id=?",String.join(",",request.permissions()),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    private UserView find(UUID id){var rows=jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users WHERE id=?",(rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return rows.getFirst();}
    private static UserView view(UUID id,String email,String first,String last,String role,String permissions,boolean verified,boolean active,Instant created,Instant lastLogin){return new UserView(id,email,first,last,role,permissions==null||permissions.isBlank()?Set.of():Set.of(permissions.split(",")),verified,active,created,lastLogin);}
    public record UserView(UUID id,String email,String firstName,String lastName,String role,Set<String> permissions,boolean emailVerified,boolean active,Instant createdAt,Instant lastLogin){}
    public record ActiveRequest(boolean active){}public record VerificationRequest(boolean verified){}public record RoleRequest(String role){}public record PermissionsRequest(Set<String> permissions){}
}
