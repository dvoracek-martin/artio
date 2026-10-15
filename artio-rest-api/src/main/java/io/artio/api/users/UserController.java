package io.artio.api.users;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
        requireSuperadmin(auth,"Only a superadmin can assign roles");
        if(request==null||request.role()==null||!Set.of("CUSTOMER","ADMIN","SUPERADMIN").contains(request.role()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown role");
        if(id.toString().equals(auth.getToken().getSubject()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrators cannot change their own role");
        int changed=jdbc.update("UPDATE users SET role=?,permissions=CASE WHEN ?='ADMIN' AND btrim(permissions)='' THEN 'NONE' ELSE permissions END WHERE id=?",request.role(),request.role(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    @PutMapping("/api/admin/users/{id}/permissions")
    public UserView permissions(@PathVariable UUID id,@RequestBody PermissionsRequest request){
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SUPERADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a superadmin can change global permissions");
        if(request==null||request.permissions()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Permissions are required");
        Set<String> normalized;
        try {normalized=request.permissions().stream().map(p->Permission.valueOf(p.toUpperCase(Locale.ROOT)).name()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
        catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown permission",e);}
        String stored=normalized.isEmpty()?"NONE":normalized.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        int changed=jdbc.update("UPDATE users SET permissions=? WHERE id=?",stored,id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    private static void requireSuperadmin(JwtAuthenticationToken auth,String message){
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SUPERADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,message);
    }

    private UserView find(UUID id){var rows=jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users WHERE id=?",(rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return rows.getFirst();}
    private static UserView view(UUID id,String email,String first,String last,String role,String permissions,boolean verified,boolean active,Instant created,Instant lastLogin){Set<String> granted;if("SUPERADMIN".equals(role)||("ADMIN".equals(role)&&(permissions==null||permissions.isBlank()||"*".equals(permissions.trim()))))granted=java.util.Arrays.stream(Permission.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());else if(permissions==null||permissions.isBlank()||"NONE".equalsIgnoreCase(permissions.trim()))granted=Set.of();else granted=Set.of(permissions.split(","));return new UserView(id,email,first,last,role,granted,verified,active,created,lastLogin);}
    public record UserView(UUID id,String email,String firstName,String lastName,String role,Set<String> permissions,boolean emailVerified,boolean active,Instant createdAt,Instant lastLogin){}
    public record ActiveRequest(boolean active){}public record VerificationRequest(boolean verified){}public record RoleRequest(String role){}public record PermissionsRequest(Set<String> permissions){}
}
