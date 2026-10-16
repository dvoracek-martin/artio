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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class UserController {
    private final JdbcTemplate jdbc;
    public UserController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/api/v1/users/me")
    public UserView me(JwtAuthenticationToken auth){return find(UUID.fromString(auth.getToken().getSubject()));}

    @GetMapping("/api/v1/admin/users")
    public List<UserView> users(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String activity,
            @RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset){
        if(q.length()>120||limit<1||limit>200||offset<0||offset>1_000_000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid customer search bounds");
        String filter=activity==null?"ALL":activity.toUpperCase(Locale.ROOT);
        if(!Set.of("ALL","RECENT","STALE","NEVER").contains(filter))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"activity must be ALL, RECENT, STALE or NEVER");
        String pattern="%"+q.trim()+"%";
        return jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users "
                        +"WHERE role='CUSTOMER' AND (?='' OR email ILIKE ? OR first_name ILIKE ? OR last_name ILIKE ?) "
                        +"AND (?='ALL' OR (?='NEVER' AND last_login IS NULL) OR (?='RECENT' AND last_login>=now()-interval '30 days') OR (?='STALE' AND last_login<now()-interval '30 days')) "
                        +"ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                (rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),
                q.trim(),pattern,pattern,pattern,filter,filter,filter,filter,limit,offset);
    }

    @GetMapping("/api/v1/superadmin/users")
    public List<UserView> manageableUsers(@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,JwtAuthenticationToken auth){
        requireSuperadmin(auth,"Only a superadmin can list managed accounts");
        if(q.length()>120||limit<1||limit>200||offset<0||offset>1_000_000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid account search bounds");
        String pattern="%"+q.trim()+"%";
        return jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users "
                        +"WHERE (role<>'SUPERADMIN' OR id=?) AND (?='' OR email ILIKE ? OR first_name ILIKE ? OR last_name ILIKE ?) "
                        +"ORDER BY role,email,id LIMIT ? OFFSET ?",
                (rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),
                UUID.fromString(auth.getToken().getSubject()),q.trim(),pattern,pattern,pattern,limit,offset);
    }

    @GetMapping("/api/v1/admin/customers/{id}")
    public CustomerDetail customer(@PathVariable UUID id){
        return jdbc.query("SELECT u.id,u.email,u.first_name,u.last_name,u.email_verified,u.active,u.created_at,u.last_login,"
                        +"count(o.id) AS order_count,max(o.created_at) AS last_order_at "
                        +"FROM users u LEFT JOIN orders o ON o.user_id=u.id WHERE u.id=? AND u.role='CUSTOMER' "
                        +"GROUP BY u.id,u.email,u.first_name,u.last_name,u.email_verified,u.active,u.created_at,u.last_login",
                (rs,n)->new CustomerDetail(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),
                        rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant(),rs.getLong("order_count"),
                        rs.getTimestamp("last_order_at")==null?null:rs.getTimestamp("last_order_at").toInstant())
        ).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Customer not found"));
    }

    @PutMapping("/api/v1/admin/customers/{id}/profile")
    public CustomerDetail updateCustomer(@PathVariable UUID id,@RequestBody CustomerProfileRequest request){
        if(request==null||request.firstName()==null||request.firstName().isBlank()||request.firstName().trim().length()>255
                ||request.lastName()==null||request.lastName().isBlank()||request.lastName().trim().length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter first and last names up to 255 characters");
        int changed=jdbc.update("UPDATE users SET first_name=?,last_name=? WHERE id=? AND role='CUSTOMER'",
                request.firstName().trim(),request.lastName().trim(),id);
        if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Customer not found");
        return customer(id);
    }

    @PutMapping("/api/v1/superadmin/users/{id}/profile")
    @Transactional
    public UserView updateManagedProfile(@PathVariable UUID id,@RequestBody ManagedProfileRequest request,JwtAuthenticationToken auth){
        requireSuperadmin(auth,"Only a superadmin can edit account profiles");
        if(request==null||request.firstName()==null||request.firstName().isBlank()||request.firstName().trim().length()>255
                ||request.lastName()==null||request.lastName().isBlank()||request.lastName().trim().length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter first and last names up to 255 characters");
        String normalizedEmail;
        try{normalizedEmail=new io.artio.users.Email(request.email()).value();}
        catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid email address");}
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE email=? AND id<>?)",Boolean.class,normalizedEmail,id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"That email address is already in use");
        int changed;
        try{changed=jdbc.update("UPDATE users SET first_name=?,last_name=?,email=?,email_verified=CASE WHEN email<>? THEN false ELSE email_verified END,token_version=CASE WHEN email<>? THEN token_version+1 ELSE token_version END WHERE id=? AND role<>'SUPERADMIN'",
                    request.firstName().trim(),request.lastName().trim(),normalizedEmail,normalizedEmail,normalizedEmail,id);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw new ResponseStatusException(HttpStatus.CONFLICT,"That email address is already in use");}
        if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Managed account not found");
        jdbc.update("UPDATE artio_email_change_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL",id);
        return find(id);
    }

    @PutMapping("/api/v1/admin/users/{id}/active")
    public UserView active(@PathVariable UUID id,@RequestBody ActiveRequest request,JwtAuthenticationToken auth){
        if(id.toString().equals(auth.getToken().getSubject()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrators cannot deactivate their own account");
        int changed=jdbc.update("UPDATE users SET active=? WHERE id=?",request.active(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    @PutMapping("/api/v1/admin/users/{id}/email-verification")
    public UserView verified(@PathVariable UUID id,@RequestBody VerificationRequest request){int changed=jdbc.update("UPDATE users SET email_verified=? WHERE id=?",request.verified(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);}

    @PutMapping("/api/v1/admin/users/{id}/role")
    public UserView role(@PathVariable UUID id,@RequestBody RoleRequest request,JwtAuthenticationToken auth){
        requireSuperadmin(auth,"Only a superadmin can assign roles");
        if(request==null||request.role()==null||!Set.of("CUSTOMER","ADMIN","SUPERADMIN").contains(request.role()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown role");
        if(id.toString().equals(auth.getToken().getSubject()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrators cannot change their own role");
        int changed=jdbc.update("UPDATE users SET role=?,permissions=CASE WHEN ?<>'ADMIN' OR btrim(permissions)='' THEN 'NONE' ELSE permissions END WHERE id=?",request.role(),request.role(),id);if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return find(id);
    }

    @PutMapping("/api/v1/admin/users/{id}/permissions")
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

    @PutMapping("/api/v1/admin/users/{id}/access")
    @Transactional
    public UserView access(@PathVariable UUID id,@RequestBody AccessRequest request,JwtAuthenticationToken auth){
        requireSuperadmin(auth,"Only a superadmin can assign account access");
        if(request==null||request.role()==null||!Set.of("CUSTOMER","ADMIN","SUPERADMIN").contains(request.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown role");
        if(request.permissions()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Permissions are required");

        var currentRoles=jdbc.query("SELECT role FROM users WHERE id=? FOR UPDATE",(rs,n)->rs.getString("role"),id);
        if(currentRoles.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");
        if(id.toString().equals(auth.getToken().getSubject())&&!request.role().equals(currentRoles.getFirst()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Administrators cannot change their own role");

        String storedPermissions;
        if("ADMIN".equals(request.role())){
            Set<String> normalized;
            try {normalized=request.permissions().stream().map(p->Permission.valueOf(p.toUpperCase(Locale.ROOT)).name()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
            catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown permission",e);}
            storedPermissions=normalized.isEmpty()?"NONE":normalized.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        }else{
            storedPermissions="SUPERADMIN".equals(request.role())?"*":"NONE";
        }
        jdbc.update("UPDATE users SET role=?,permissions=? WHERE id=?",request.role(),storedPermissions,id);
        return find(id);
    }

    private static void requireSuperadmin(JwtAuthenticationToken auth,String message){
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SUPERADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,message);
    }

    private UserView find(UUID id){var rows=jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users WHERE id=?",(rs,n)->view(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),rs.getString("last_name"),rs.getString("role"),rs.getString("permissions"),rs.getBoolean("email_verified"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_login")==null?null:rs.getTimestamp("last_login").toInstant()),id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");return rows.getFirst();}
    static UserView view(UUID id,String email,String first,String last,String role,String permissions,boolean verified,boolean active,Instant created,Instant lastLogin){Set<String> granted;if("SUPERADMIN".equals(role)||("ADMIN".equals(role)&&(permissions==null||permissions.isBlank()||"*".equals(permissions.trim()))))granted=java.util.Arrays.stream(Permission.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());else if(permissions==null||permissions.isBlank()||"NONE".equalsIgnoreCase(permissions.trim()))granted=Set.of();else granted=java.util.Arrays.stream(permissions.split(",")).map(String::trim).filter(value->!value.isEmpty()).map(value->Permission.valueOf(value.toUpperCase(Locale.ROOT)).name()).collect(java.util.stream.Collectors.toUnmodifiableSet());return new UserView(id,email,first,last,role,granted,verified,active,created,lastLogin);}
    public record UserView(UUID id,String email,String firstName,String lastName,String role,Set<String> permissions,boolean emailVerified,boolean active,Instant createdAt,Instant lastLogin){}
    public record CustomerDetail(UUID id,String email,String firstName,String lastName,boolean emailVerified,boolean active,Instant createdAt,Instant lastLogin,long orderCount,Instant lastOrderAt){}
    public record CustomerProfileRequest(String firstName,String lastName){}
    public record ManagedProfileRequest(String firstName,String lastName,String email){}
    public record ActiveRequest(boolean active){}public record VerificationRequest(boolean verified){}public record RoleRequest(String role){}public record PermissionsRequest(Set<String> permissions){}public record AccessRequest(String role,Set<String> permissions){}
}
