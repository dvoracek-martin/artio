package io.artio.api.users;

import io.artio.api.security.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TenantController {
    private final JdbcTemplate jdbc;
    public TenantController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/api/tenants/current")
    public TenantView current(){return find(TenantContext.requireCurrent());}

    @GetMapping("/api/tenants")
    public List<TenantView> mine(JwtAuthenticationToken auth){
        UUID user=user(auth);
        return jdbc.query("SELECT t.id,t.name,t.domain,t.plan,t.default_currency,t.default_locale FROM tenants t JOIN tenant_memberships m ON m.tenant_id=t.id WHERE m.user_id=? AND t.active ORDER BY t.name",
                (rs,n)->new TenantView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5).trim(),rs.getString(6)),user);
    }

    @PostMapping("/api/admin/tenants") @Transactional
    public TenantView create(@RequestBody CreateTenant request){
        requireSuperadmin();
        if(request==null||blank(request.name())||blank(request.domain())||request.name().length()>160||request.domain().length()>255||!request.domain().matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")||request.plan()==null||!Set.of("FREE","STARTER","BUSINESS","ENTERPRISE").contains(request.plan())||request.currency()==null||!request.currency().matches("(?i)[a-z]{3}")||request.locale()==null||!request.locale().matches("[a-z]{2}(?:-[A-Z]{2})?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tenant name, domain, plan, currency or locale");
        UUID id=UUID.randomUUID();
        try{jdbc.update("INSERT INTO tenants(id,name,domain,plan,default_currency,default_locale) VALUES (?,?,?,?,?,?)",id,request.name().trim(),request.domain().toLowerCase(Locale.ROOT),request.plan(),request.currency().toUpperCase(Locale.ROOT),request.locale());}
        catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Tenant domain already exists");}
        return find(id);
    }

    @PostMapping("/api/admin/tenants/{tenantId}/members/{userId}") @Transactional
    public void addMember(@PathVariable UUID tenantId,@PathVariable UUID userId,@RequestBody(required=false) AddMembership request){
        requireSuperadmin();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,userId)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tenants WHERE id=? AND active)",Boolean.class,tenantId)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tenant not found");
        String role=request==null||request.role()==null?"MEMBER":request.role().toUpperCase(Locale.ROOT);
        if(!Set.of("OWNER","ADMIN","MEMBER","SUPPORT").contains(role))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tenant membership role");
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",tenantId,userId,role);
    }

    private TenantView find(UUID id){return jdbc.queryForObject("SELECT id,name,domain,plan,default_currency,default_locale FROM tenants WHERE id=?",(rs,n)->new TenantView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5).trim(),rs.getString(6)),id);}
    private static UUID user(JwtAuthenticationToken auth){return UUID.fromString(auth.getToken().getSubject());}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static void requireSuperadmin(){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SUPERADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a superadmin can manage tenants");}
    public record TenantView(UUID id,String name,String domain,String plan,String currency,String locale){}
    public record CreateTenant(String name,String domain,String plan,String currency,String locale){}
    public record AddMembership(String role){}
}
