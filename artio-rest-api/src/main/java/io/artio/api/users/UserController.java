package io.artio.api.users;

import io.artio.users.Permission;
import io.artio.api.security.TenantContext;
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
    public List<UserView> users(){return jdbc.query("SELECT u.id,u.email,u.first_name,u.last_name,u.role,u.permissions,u.email_verified,u.active,u.created_at,u.last_login FROM users u JOIN tenant_memberships m ON m.user_id=u.id WHERE m.tenant_id=? ORDER BY u.created_at DESC LIMIT 500",(rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),TenantContext.requireCurrent());}

    @PutMapping("/api/admin/users/{id}/active")
    public UserView active(@PathVariable UUID id,@RequestBody ActiveRequest request,JwtAuthenticationToken auth){
        if(id.toString().equals(auth.getToken().getSubject()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrators cannot deactivate their own account");
        int changed=jdbc.update("UPDATE users SET active=? WHERE id=? AND EXISTS(SELECT 1 FROM tenant_memberships WHERE user_id=users.id AND tenant_id=?)",request.active(),id,TenantContext.requireCurrent());if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found in this tenant");return find(id);
    }

    @PutMapping("/api/admin/users/{id}/email-verification")
    public UserView verified(@PathVariable UUID id,@RequestBody VerificationRequest request){int changed=jdbc.update("UPDATE users SET email_verified=? WHERE id=? AND EXISTS(SELECT 1 FROM tenant_memberships WHERE user_id=users.id AND tenant_id=?)",request.verified(),id,TenantContext.requireCurrent());if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found in this tenant");return find(id);}

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
        int changed=jdbc.update("UPDATE users SET permissions=? WHERE id=? AND EXISTS(SELECT 1 FROM tenant_memberships WHERE user_id=users.id AND tenant_id=?)",String.join(",",request.permissions()),id,TenantContext.requireCurrent());if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found in this tenant");return find(id);
    }

    private UserView find(UUID id){var rows=jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users WHERE id=?",(rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return rows.getFirst();}
    private static UserView view(UUID id,String email,String first,String last,String role,String permissions,boolean verified,boolean active,Instant created,Instant lastLogin){return new UserView(id,email,first,last,role,permissions==null||permissions.isBlank()?Set.of():Set.of(permissions.split(",")),verified,active,created,lastLogin);}
    public record UserView(UUID id,String email,String firstName,String lastName,String role,Set<String> permissions,boolean emailVerified,boolean active,Instant createdAt,Instant lastLogin){}
    public record ActiveRequest(boolean active){}public record VerificationRequest(boolean verified){}public record RoleRequest(String role){}public record PermissionsRequest(Set<String> permissions){}
}
